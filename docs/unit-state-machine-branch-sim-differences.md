
# Simulation differences: `unit-state-machine` vs `main`

The `unit-state-machine` branch replaces the controller stack in `model/behaviour/` with a different
way of deciding what units and ships do. This document lists every way the branch's lockstep
simulation behaves differently from `main` (merge base `234d49fb`), and what each difference means
in play.

This comes from reading the code. No event log has been replayed on both builds, so treat each item
as a strong finding rather than a confirmed one. `SIM_VERSION` is 1011 on both `main` and the
branch, so as things stand, clients of the two builds would be allowed into the same multiplayer
game. Every item below is a way such a game could desync.

## How the branch decides, compared with main

On `main`, each `Selectable` has a stack of `Controller`s. The top one decides, and new work is
pushed on top of whatever is there. A unit's `IdleController` (or its tower or ship equivalent)
sits at the bottom. Player and AI commands clear the stack down to that bottom controller
(`initTarget` → `clearControllerStack()`) and push the new one. Stuns, spells and death are pushed
on top and force a decision (`forceDecide`). Some other pushes don't clear anything: a tower
occupant's attack, a hunt spotted on a walk, a harvest inside a gather. Each controller keeps its
own give-up counters (`MAX_TRIES = 1`).

On the branch, a unit's state is a fixed set of slots instead of a stack:

- a **posture**: what it does with nothing else to do (stand idle and watch for enemies on land or
  in a tower, or crew a ship as a fighter or a rower). Only mounting and unmounting change it.
- at most one **order**: walk, defend, hunt, attack, gather, repair, enter, build or transfer. A new
  order always replaces the old one. Sub-tasks, such as the hunt an aggressive walk breaks off for
  or the harvest inside a gather, are held inside the order rather than stacked above it.
- an **interruption** slot each for dying, a stun and a spell. Dying runs ahead of a stun, which
  runs ahead of a spell, which runs ahead of the order. An interruption suspends the order rather
  than replacing it, and a new stun or spell replaces the one already in its slot.

Other differences in how the branch works:

- A task that becomes current again, because it was just given or because a stun or spell above it
  ended, gets its retry budget back.
- The posture's activity is set aside rather than thrown away while something runs above it, so
  its scan timer survives orders and interruptions. Main has the same effect, because the idle
  controller stays at the bottom of the stack.
- Activities report back as being in the middle of something, at a point where they can be
  replaced, or done. These match main's `UNINTERRUPTIBLE`, `INTERRUPTIBLE` and `DONE`.

Ships have a simpler machine, either docked or on a voyage (slide off the shore, sail, work loose
when stuck). Ships behave the same as on `main`.

## Orders, stuns and delivery

### 1. Stunned or casting units carry on with their order

- **main:** a stun or spell is pushed on top of the order's controller. When it pops, the order
  decides again with its give-up counters as they were. Most orders get one walk attempt
  (`MAX_TRIES = 1`), and a unit stunned partway through its walk has already used it. So when the
  stun ends, `WalkController`, `HuntController`, `EnterController` and the rest give up and pop.
  The unit stops where it was stunned and goes idle. The same happens to an AI chieftain that casts
  while carrying out an order (`doMagic(..., false)`).
- **branch:** when the stun or spell ends, the order gets its retry budget back and carries on.
- **consequences:**
  - A group walked across the map and hit by a stun spell keeps walking afterwards, where on main
    the stunned units stopped and had to be re-ordered.
  - Gatherers, builders and repairers stunned mid-walk carry on with their job instead of going
    idle.
  - Stun spells are therefore weaker as a disruption tool than on main: they stop units only for
    the stun's duration.
  - AI chieftains carry on with their order after casting.

### 2. Towers switch targets for good, and attacks no longer stack

- **main:** `LandBuilding.setTarget` with an attack calls `unit.pushController(new
  AttackController(...))` on the tower's occupant. It pushes without clearing anything, so every
  attack command adds another `AttackController` on top of the earlier ones. The stack is only
  cleared when the occupant leaves the tower or dies, so it grows without limit. When the current
  target dies or goes out of range, `AttackController.decide` pops, and the controller beneath
  decides: the tower goes back to the previous target if it is alive and in range, otherwise pops
  again.
- **branch:** an attack command replaces the occupant's order, so the tower attacks the new target
  until it dies or leaves range and then goes back to watching.
- **consequences on main:**
  - A tower given several attack commands works back through all its earlier targets in reverse
    order. A player can't make it forget them except by taking the unit out of the tower.
  - Every command made during the game stays on the stack, holding a reference to its target, even
    a target long dead.
  - Unwinding is recursive: each pop calls `decide()` on the next controller, which may pop in
    turn. With enough dead or out-of-range targets stacked up, the Java call stack could overflow.
    That is plausible but not tested.
- **consequences on the branch:** none of this. A tower attacks the target it was last given, then
  returns to scanning for enemies by itself.

### 3. A second stun replaces the first

- **main:** each stun pushes another `StunController`, so the unit serves the stuns one after the
  other: two overlapping 3 s stuns keep it stunned for 6 s.
- **branch:** a new stun replaces the one in the slot, so the unit is stunned for the new stun's
  duration, counted from when it landed.
- **consequences:**
  - Chaining stuns no longer adds up. Repeated stun spells or stunning weapons on the same target
    keep it stunned only as long as the latest one.
  - A shorter stun landing on a unit with a long stun left shortens the stun.

### 4. Gatherers whose building was destroyed deliver elsewhere

- **main:** `GatherController.getBuilding()` returns the assigned building's entrance even when that
  building is dead. `dropoff()` sees it is dead and sends the gatherer to find another store. On
  arrival, `getBuilding()` still returns the dead building's entrance, so the gatherer is never
  close enough to deliver. It uses up its attempt and pops, idle and still carrying its load.
- **branch:** a gatherer uses its assigned building only while it stands, and otherwise delivers to
  the store its search found, then carries on gathering.
- **consequences:**
  - Losing an armory or quarters no longer leaves its gatherers idle with full hands. They take
    their load to another store and keep working.
  - That changes the economy after a raid: more resources reach storage, and fewer peons sit idle
    to be counted by the AI's idle-peon checks.

### 5. A new order wins over an enemy just spotted on an aggressive walk

- **main:** `WalkBehaviour.scan()` resets the counters of the *current* controller and pushes a
  `HuntController` on top of it. If a new order arrived while the walk was in the middle of a step,
  the new controller is already on top, so the hunt goes above the new order. The unit fights the
  enemy it spotted first, and only then starts the new order, with that order's counters reset by
  the walk.
- **branch:** when the walk reports that it spotted an enemy after the unit has been given a new
  order, the new order starts and the enemy is ignored.
- **consequences:**
  - Units on an attack-move respond to a retreat or redirect order straight away. On main they
    could break off to fight an enemy seen in the same moment, which made retreats from a fight
    unreliable.
  - Units that do start a new order this way no longer get its counters reset as a side effect.

## Random numbers, scan timing and placement

### 6. The world random numbers are drawn at different times, and some are skipped

- **main:** the `Unit` constructor pushes `IdleController`, which decides straight away and draws
  once from `World.getRandom()` to arm its scan timer. Every unit created draws once, at creation.
  Mounting a tower or ship creates a new `IdleController` or `ShipAttackController`, which also
  draws at once.
- **branch:** idle units and ship fighters draw only on their first frame of standing and watching:
  - The rudder peon `ShipHR.Rudder` creates for every ship is mounted before it ever animates, so
    it never draws. That is one draw fewer for every ship built.
  - Units given an order at birth (rally points, `createHarvesters`, quarters transfers) draw only
    when they first go idle, and that can be minutes later. Units that enter a building or die
    before then never draw.
  - Units that mount a tower or ship draw on their next frame instead of during the mount.
- **consequences:**
  - The world RNG is shared by everything in the simulation. Once one draw moves or disappears,
    every later random value differs from main: weapon hit and miss and spread (`ThrowingWeapon`),
    rubber weapons' bonus effects, building-hit and death sounds' choice and pitch, chicken
    movement (`RubberSupply`), supply respawn (`SupplyManager`), ship damage effects, and the
    passive AI's choices.
  - This happens in practically every game: the first trained unit with a rally point, or the
    first ship, is enough.
  - Event logs recorded on `main` won't replay the same on the branch, and a multiplayer game
    between the two builds would desync almost immediately.
  - It isn't a fairness problem in a branch-only game: every client of the same build draws the
    same way.

### 7. New units react to enemies sooner

- **main:** the scan timer armed at creation (1–2 s) doesn't count down while the unit has an order,
  because only the idle behaviour counts it down. A trained unit that walks to its rally point
  arrives with that 1–2 s still on the clock, and stands without reacting until it runs out.
- **branch:** the timer starts at zero, so the unit scans for enemies on its first idle frame.
- **also:** a unit mounting a tower or ship scans at the moment of mounting on main, and one frame
  later on the branch.
- **consequences:**
  - Reinforcements arriving at a rally point under attack join the fight straight away instead of
    after a 1–2 s pause.
  - Harvesters and other newly made units also respond the first time they go idle.
  - Small, but it changes the outcome of fights at rally points and base defence.

### 8. Rally-point spacing reserves different cells

- **main:** before sending a new unit to a landscape rally point, the `Unit` constructor reserves
  the walk targets of the owner's units whose top controller is a `WalkController`. That spreads new
  units over free cells instead of piling them onto the same one. A unit on an aggressive walk
  that is busy hunting has `HuntController` on top, so its target isn't reserved.
- **branch:** a unit whose order is a walk counts even while it is hunting something it spotted.
- **consequences:**
  - While units heading to the rally point are fighting on the way, new units are sent to
    different cells than on main.
  - Positions then diverge, along with pathfinding, blocking and everything that follows.

## Edge cases

### 9. Stunned units lose their defence for the whole stun

- **main:** `getDefenseChance()` returns 0 while the top controller is a `StunController`, so a
  stunned unit can't defend itself. Anything that replaced that top controller restored the
  defence, while the stun's behaviour kept running and the unit still stood stunned:
  - any new order (`initTarget` calls `clearControllerStack()`)
  - right-clicking the unit itself, or a `GATHER_REPAIR` order on something it can't gather or
    repair
  - a chieftain casting while stunned. `doMagic` clears the stack for the player, and the AI's
    cast puts `MagicController` on top.

  Tower occupants can't be given orders, so `canExitTower()`, which checks the same thing, wasn't
  affected.
- **branch:** a unit counts as stunned until its stun ends, whatever it is ordered to do.
- **consequences:**
  - On main, clicking a new order onto stunned units gave them back their full defence for the
    rest of the stun, a trick players could use, knowingly or not, in every fight involving
    stuns.
  - On the branch, stunned units take more hits in exactly the cases where a player was issuing
    commands to them.

### 10. A new order no longer cancels a stun or spell that hasn't started

- **main:** `forceDecide` starts the `StunBehaviour` or `MagicBehaviour`, but `last` stays
  `INTERRUPTIBLE` until the unit's next frame. An order arriving before that frame clears the stack
  and decides at once, replacing the new behaviour before it ever runs:
  - a stun is skipped entirely
  - a spell is never cast, though `doMagic` has already spent the chieftain's energy
- **branch:** a stun or spell sits in its own slot, and an order doesn't remove it.
- **consequences:**
  - On main, a unit stunned just after it animated, by something that animated later in the same
    tick, shrugs off the stun completely if its owner gives it any order in the next tick. That
    happens by chance in busy fights.
  - On main, pressing a magic button and giving the chieftain an order in the same tick wastes the
    spell. On the branch the spell is cast and the order follows.

### 11. A stunned chieftain given an order still casts the interrupted spell

- **main:** a stun during a cast leaves `MagicController` under `StunController`, so the spell is
  recast when the stun wears off. A new order during the stun clears both, so the spell is lost,
  though the energy is spent.
- **branch:** the spell is cast when the stun ends, and the order runs after it.
- **consequences:**
  - On main, ordering a stunned chieftain to retreat wastes the spell it was casting. On the branch
    it casts first, delaying the retreat by the cast's duration.
  - `MagicUsedTrigger` (campaign) checks whether the chieftain is casting. On main, the check
    is `getPrimaryController() instanceof MagicController`, which turns false as soon as an order
    clears the stack mid-cast, so the trigger could fire before the spell finished. On the branch
    the check stays true until the cast ends, so the trigger fires later in that case.

### 12. Leaving a tower or ship mid-throw cancels the throw

- **main:** `Unit.unmount()` pushes the new `IdleController` without `forceDecide`. If the unit was
  in the uninterruptible part of an `AttackBehaviour`, the decision waited, so the throw finished
  and the projectile was released after the unit was back on the ground.
- **branch:** unmounting replaces whatever the unit was doing at once, so the throw is cancelled.
- **also:** when main's unmount decided straight away, the new idle controller scanned while the
  unit still stood at the tower or ship position, before `findInitialPosition` moved it. If it
  spotted an enemy, the "walk back" target it recorded was the tower or ship cell. On the branch the
  scan happens after the unit has moved, and the walk back goes to where it landed.
- **consequences:**
  - Units ordered out of a tower, or landing from a ship, mid-attack no longer get their last shot
    off. That is one hit fewer, sometimes the killing one.
  - On main, units that left a tower and chased an enemy would afterwards try to walk back to the
    tower itself. On the branch they walk back to where they stepped out.

### 13. Fighters on a ship no longer scan before boarding

- **main:** `ShipAttackController.decide` ran its first scan at mount time, before the 1 s boarding
  animation, and could push an `AttackController` at once. The fighter then started attacking from
  its seat before it had finished boarding.
- **branch:** a fighter scans only once boarded: after 1 s, or as soon as the ship moves.
- **consequences:**
  - Warriors boarding a ship next to enemies start shooting up to 1 s later than on main.
  - It also removes one of the RNG draws counted in item 6.

### 14. A freshly started attack can't be replaced in its first tick

- **main:** a new behaviour can be replaced before its first frame, because `last` still holds the
  state the previous behaviour returned. An order arriving between the start of an attack and the
  attack's first frame cancels it.
- **branch:** an attack counts as in the middle of something from the moment it starts, so an order
  arriving then waits for the whole attack cycle, about 2 s, as it would one frame later on main.
- The same goes for a cast, a stun and death, but orders don't replace those anyway (see item 10).
- **consequences:**
  - A move order given right as a unit starts attacking now waits for the attack to finish,
    instead of sometimes being obeyed at once.
  - Which one happens on main depends on whether the unit started its attack before or after the
    order in the same tick. The branch is consistent, at the cost of retreats sometimes taking up
    to 2 s longer.

### 15. A different unit animation value for one attack

- **main:** when an aggressive walk is blocked and spots an enemy in range, `WalkBehaviour.scan()`
  pushes the hunt, which starts an `AttackBehaviour` and sets the throwing animation. Then the same
  frame's `doRetry()` calls `switchToIdleAnimation()`, so that attack runs with the idle animation.
- **branch:** the idle animation is set first, then the attack starts and sets the throwing
  animation.
- **consequences:**
  - On main, a blocked unit attacking this way stands in its idle pose while its attack lands. The
    branch shows the throw.
  - `Unit.doAnimate` feeds `animation` into the global checksum, so in this situation the checksum
    differs from main even though the damage dealt is the same.

### 16. A new spell replaces one not yet cast

- **main:** a second `doMagic` during a cast pushes another `MagicController` and interrupts the
  first spell. When the second spell is done, the first `MagicController` decides again and casts
  its spell from the start.
- **branch:** the second spell replaces the first, so only the second is cast.
- **consequences:** `doMagic` empties both energy bars, so a second cast can't normally happen
  during the first. This is unlikely to be reachable without cheats.

### 17. Which group a casting, stunned chieftain is sorted into

- **main:** a chieftain with no order that is stunned mid-cast has the stack
  `[Idle, Magic, Stun]`, so `getPrimaryController()` returns the `MagicController`, the second
  entry.
- **branch:** a chieftain in that state reports the stun as what it is doing, because the stun runs
  ahead of the spell.
- **consequences:** `Player.classifyUnits()` puts the chieftain in the "stunned" group instead of
  the "casting" group. No AI or trigger code tests for either group, so there is no known gameplay
  effect.

## Checked and matching main

- **Ships:** sliding off the shore, sailing, working loose, giving up after three failed attempts,
  arriving, finding no route, a new destination mid-voyage, and destruction all behave as with
  `SailController`. `Ship.isMoving()` gives the same answer at every step.
- **Hunt, attack from a tower or ship, enter, build then repair, repair with wood fetching,
  transfer, walk and defend:** the same decisions as the matching controllers on `main`, with the
  same one-attempt budgets.
- **Gather:** the same round trip and attempt resets between the harvest and delivery legs as
  `GatherController` and `HarvestController`, and the same "store is full, so walk in" handover,
  apart from item 4.
- **Right-click on self, `GATHER_REPAIR` with nothing to do, invalid actions:** as on main, where
  `initTarget` clears the stack without deciding, whatever the unit was doing is left to finish
  before it goes idle. A walk left to finish that spots an enemy hunts it with no walk back, as
  main's `scan()` does when it pushes the hunt straight onto the idle controller.
- **What the AI, GUI and triggers see a unit doing** (`getPrimaryController()` on main): the same in
  every case except items 11 and 17. `Player.classifyUnits()` groups the same units together
  apart from item 17. The grouping key strings changed, but they are only compared with each other.
- **Idle scan timers:** frozen while an order or interruption runs and resumed afterwards, as on
  main.
