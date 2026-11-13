# Unit State Machine Modernization Proposal & Requirements

## 1. Overview & Motivation

[`Selectable`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/model/Selectable.java) entities (`Unit` and `Building`) currently manage their behavior using a mutable pushdown stack (`List<Controller> controller_stack`) paired with a separate frame-by-frame [`Behaviour`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/behaviour/Behaviour.java) object (`current_behaviour`).

While compact, this split architecture relies on implicit stack-index conventions, re-entrant stack mutations during decision callbacks, cyclic references between controllers and behaviours, and tightly coupled world queries that prevent isolated unit testing or state checksumming.

We are evaluating and planning a replacement inspired by Andy Matuschak’s [Composable Pattern for Pure State Machines with Effects](https://gist.github.com/andymatuschak/d5f0a8730ad601bcccae97e8398e25b2) (and insights from its discussion thread), adapted into a **Hybrid Hierarchical State Machine** tailored to Tribal Trouble’s deterministic lockstep simulation.

This document records the subtle behaviors and warts of the legacy system, our architectural decisions, and the mandatory requirements that any detailed design must satisfy.

---

## 2. How the Current System Actually Works (Warts & Surprising Legacy Behaviors)

Developers familiar with the high-level `Controller` / `Behaviour` split may not be aware of several subtle mechanics and quirks that directly affect gameplay and deterministic replay parity:

### 2.1 Synchronous Zero-Tick Transition Cascades (`decide()` Re-entrancy)
In [`Selectable`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/model/Selectable.java#L195-L217), calling `pushController()`, `popController()`, or `swapController()` **immediately and synchronously** calls `decide()`, which in turn invokes `getCurrentController().decide()`.
- A single `decide()` call frequently chains across multiple controllers in **0.0 seconds of simulation time** (within the same tick).
- **Example:** When a peon arrives at a building site, [`PlaceBuildingController.decide()`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/behaviour/PlaceBuildingController.java#L33-L37) places the building and calls `swapController(new RepairController(...))`. That immediately invokes [`RepairController.decide()`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/behaviour/RepairController.java#L32-L62), which sees the peon has no wood and calls `pushController(new HarvestController(...))`. That immediately invokes [`HarvestController.decide()`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/behaviour/HarvestController.java#L41-L51), which creates a `FinderTrackerAlgorithm` and assigns a `WalkBehaviour`—all before `Selectable.animate(dt)` finishes its tick.
- **Implication:** Introducing even a 1-tick delay between chained state transitions will alter pathfinding timing, `UnitGrid` occupancy, and PRNG call order.

### 2.2 The `UNINTERRUPTIBLE` Deferred Decision Latch (`should_decide`)
A `Behaviour.animate(dt)` step returns one of three states: `INTERRUPTIBLE`, `UNINTERRUPTIBLE`, or `DONE`.
- When [`WalkBehaviour`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/behaviour/WalkBehaviour.java#L75-L79) is mid-step between grid cells (`PathTracker.State.OK`), or [`AttackBehaviour`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/behaviour/AttackBehaviour.java#L33-L52) is mid-throw, or [`MagicBehaviour`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/behaviour/MagicBehaviour.java#L40-L66) is casting, it returns `UNINTERRUPTIBLE`.
- If a player issues a new command (calling `initTarget` -> `pushController` -> `decide()`) while `last == Behaviour.State.UNINTERRUPTIBLE`, [`Selectable.decide()`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/model/Selectable.java#L172-L178) **does not switch behaviours immediately**. Instead, it sets `should_decide = true` and waits until the current behaviour returns `INTERRUPTIBLE` or `DONE` on a future tick.
- Only [`Selectable.forceDecide()`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/model/Selectable.java#L189-L193)—called exclusively by [`Unit.stun()`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/model/Unit.java#L414) and fatal [`Unit.hit()`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/model/Unit.java#L393)—bypasses `UNINTERRUPTIBLE`, invokes `current_behaviour.forceInterrupted()`, and forces an immediate transition.

### 2.3 Fragile `getPrimaryController()` (`controller_stack.get(1)`) Indexing
[`Selectable`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/model/Selectable.java#L228-L233) defines two ways to inspect a unit's state:
- `getCurrentController()` returns `controller_stack.getLast()` (top of the stack).
- `getPrimaryController()` returns `controller_stack.size() > 1 ? controller_stack.get(1) : controller_stack.getFirst()`, assuming index `0` is always the default `IdleController`/`NullController` and index `1` is the player's primary order.
- **Warts:**
  - If an **idle** unit (stack size 1: `[IdleController]`) is stunned, `StunController` is pushed at index `1` (`[IdleController, StunController]`). Calling `getPrimaryController()` now returns `StunController`. However, if a **gathering** unit (stack: `[IdleController, GatherController, HarvestController]`) is stunned, `getPrimaryController()` still returns `GatherController`!
  - If an **idle** unit spots an enemy, [`IdleController.decide()`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/behaviour/IdleController.java#L47-L48) pushes *two* controllers: `WalkController(unit_current_tile, aggressive=true)` at index `1` (as a return leash) and `HuntController(enemy)` at index `2`. Because `WalkController` is at index `1`, `getPrimaryController()` reports the unit as `WalkController(aggressive=true)` (`EntityActivity.ATTACKING_MOVING` / `isAggressiveWalking() == true`) while it is actually leashed to its idle post!

### 2.4 Double-Stun Stacking Quirk
When [`Unit.stun(float time)`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/model/Unit.java#L403-L415) is called, it unconditionally calls `pushController(new StunController(this, time))` and `forceDecide()`.
- If a unit is already stunned and gets hit by another stun effect before the first expires, a **second `StunController`** is pushed on top of the first.
- When the second stun finishes and pops, the first `StunController` resumes from where its timer was paused when preempted—meaning sequential stuns have **additive duration** (`time1 + time2`) rather than overlapping (`max(time1, time2)`).

### 2.5 Cross-Unit Inspection & Mutation During Spawn and Combat
- **Rally Point Inspection:** In [`Unit`'s constructor](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/model/Unit.java#L117-L129), when a newly spawned unit is assigned a `LandscapeTarget` rally point, it iterates over all friendly units, checks `if (s.getCurrentController() instanceof WalkController wc)`, and temporarily marks `wc.getTarget()` as occupied in `UnitGrid` so multiple units rallying to the same spot fan out to adjacent tiles.
- **Re-entrant Building Entry (`mount` / `removeNow`):** In [`EnterController.decide()`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/behaviour/EnterController.java#L43), calling `c.enter(unit)` on a Tower calls [`unit.mount(building)`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/model/Unit.java#L219-L229), which calls `clearControllerStack()` and `swapController(new IdleController(..., TOWER_RANGE, false))` *while `EnterController.decide()` is still on the call stack*.

### 2.6 Redundant Controller/Behaviour Pairs and Cyclic Back-Pointers
Five `Controller`/`Behaviour` pairs have no meaningful separation of concerns:
- `DieController` / `DieBehaviour`
- `NullController` / `NullBehaviour`
- `StunController` / `StunBehaviour` (behaviour holds a reference to `StunController` just to decrement its timer via `controller.shouldSleep(dt)`)
- `IdleController` / `IdleBehaviour` (behaviour holds a reference to `IdleController` to decrement `redecide_time`)
- `MagicController` / `MagicBehaviour` (behaviour holds a reference to `MagicController` to set `should_pop = true` when the `PREPARING -> CASTING -> ENDING` animation sequence finishes)

### 2.7 Exact PRNG Consumption Under `AppConfig.LEGACY_DETERMINISTIC`
Multiplayer lockstep and deterministic event-log playback rely on exact call counts and ordering of `World.getRandom()`:
1. [`IdleController.decide()`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/behaviour/IdleController.java#L40-L41): consumes `1x nextFloat()` whenever `redecide_time <= 0` (even for units without `Abilities.ATTACK`!).
2. [`Unit.hit()`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/model/Unit.java#L394-L396): consumes `1x nextFloat()` on death when `AppConfig.LEGACY_DETERMINISTIC` is enabled.
3. [`Unit.stun()`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/model/Unit.java#L404-L412): consumes `5 * (3x nextFloat() + 1x nextInt(4))` when `AppConfig.LEGACY_DETERMINISTIC` is enabled.
4. [`HarvestBehaviour`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/behaviour/HarvestBehaviour.java#L35-L41) & [`RepairBehaviour`](/tribaltrouble/simulation/src/main/java/com/oddlabs/tt/simulation/behaviour/RepairBehaviour.java#L41-L43): consume `1x nextInt(5 or 6)` at the animation release threshold when `AppConfig.LEGACY_DETERMINISTIC` is enabled.

---

## 3. Agreed Architectural Decisions & Requirements

### Decision 1: Unified Hierarchical State Machine (Option B)
We will **unify `Controller` and `Behaviour`** into a single hierarchical state machine (`sealed` hierarchy) rather than maintaining two parallel state hierarchies.
- **Explicit Structural Layers:** Instead of an untyped `List<Controller>` where index `0`, index `1`, and `getLast()` have implicit roles, the state hierarchy will explicitly model:
  1. **Life & Preemption Status:** `Active`, `Stunned` (wrapping the interrupted state), `Dying`, `Mounted` (tower garrison), or `Inert` (`Building` null state).
  2. **Primary Order:** `Idle`, `Walking`, `Defending`, `Gathering`, `PlacingBuilding`, `Repairing`, `Hunting`, `Attacking`, `EnteringBuilding`, `CastingMagic`, `TransferringUnit`.
  3. **Sub-Goal / Execution Phase:** Explicit nested states for multi-phase orders (e.g., `Gathering` -> `Harvesting` vs `DroppingOff`; `Repairing` -> `HarvestingWood` vs `ReturningToRepair`; `Idle` -> `StandingWatch` vs `LeashedPursuit`; `CastingMagic` -> `Preparing`, `Casting`, `Ending`) and reusable `Walking` navigation phases.
- **In-Place Continuous Timers in Leaf States:** To avoid 50Hz heap allocation churn during `animate(float dt)` ticks, discrete state transitions will return new state nodes, while continuous frame-by-frame timers (`anim_time`, `retry_delay_counter`, `PathTracker`) will update in-place within leaf execution states.

### Decision 2: Optional Bug-for-Bug Parity via `AppConfig.LEGACY_DETERMINISTIC`
- **Default Modern Behavior:** Fix structural stack quirks (e.g., `getPrimaryOrder()` always reflects the true primary order even when an idle unit is stunned or on a leashed pursuit; overlapping stuns refresh/take max duration cleanly).
- **`AppConfig.LEGACY_DETERMINISTIC` Mode:** Preserve exact legacy simulation parity (including additive stun durations on stacked stuns, exact `shouldGiveUp` retry counts, zero-tick transition cascades, and identical `World.getRandom()` consumption).

### Decision 3: Hybrid `UnitContext` Boundary (Over Pure Command-List Allocation)
Rather than allocating `(State, List<OutputCommand>)` tuples on every tick or pre-computing expensive spatial queries into `InputEvent`s:
- State transitions and tick steps will accept a narrow, domain-level **`UnitContext`** interface providing read queries (e.g., range checks, target liveness, vicinity scans, pathfinding steps) and actuation methods (e.g., animation switching, supply transfer, projectile spawning, building placement).
- In production, `Selectable` / `Unit` provides the context backed by the live `World` and `UnitGrid`.
- In unit tests, a lightweight fake/spy `UnitContext` can drive every state machine transition deterministically without instantiating `World`, `HeightMap`, or `UnitGrid`.

---

## 4. Mandatory Design Invariants (Lockstep & Safety)

Any concrete class design built from these requirements **MUST** uphold the following invariants:

1. **Zero-Tick Epsilon Transitions:** When a state phase completes or a command arrives during an interruptible window, the state machine must resolve chained immediate transitions synchronously within the same tick until it settles on an active continuous phase (matching legacy `decide()` chaining).
2. **Interruptibility Gate Enforcement:** External player commands arriving while the current leaf phase is `UNINTERRUPTIBLE` must be queued/deferred until the leaf phase reaches an `INTERRUPTIBLE` or `DONE` boundary, whereas forced preemptions (`stun`, `die`, `mount`, `unmount`) must immediately trigger exit cleanup (`onExit` / `forceInterrupted`) and transition.
3. **Commit-Before-Actuate / Terminal Transition Safety:** Because actuating world effects (such as `enterBuilding` calling `mount()` or `removeNow()`, or `attack` killing a unit) can synchronously re-enter `Unit` methods, state transitions must never overwrite terminal states set by re-entrant actuation.
4. **Deterministic Iteration Only:** State transitions and context operations must never iterate over unordered collections (`HashSet`, `Set.of()`, `HashMap`) when performing side effects or PRNG calls.
5. **Deterministic Checksum Support (`StateChecksum`):** Every state in the hierarchy must support deterministic hashing into [`StateChecksum`](/tribaltrouble/base/src/main/java/com/oddlabs/tt/base/event/StateChecksum.java) (using primitive state discriminators, retry counters, and `Distributable` target IDs) so multiplayer lockstep can verify unit behavioral state directly.

---

## 5. Expected Outcomes & Benefits

Once implemented, this modernization provides:
- **Deletion of ~15 legacy `Controller` and redundant `Behaviour` classes**, replaced by a single compiler-checked `sealed` state hierarchy.
- **Elimination of `controller_stack.get(1)` (`getPrimaryController()`)** and ad-hoc coordination flags (`should_pop`, `should_decide`), making UI status queries (`EntityActivity`, tooltips, AI inspection) exhaustive and type-safe.
- **Fast, World-free unit tests** for all unit behaviors, retry counters, and edge cases via `UnitContext`.
- **Full `StateChecksum` coverage** for unit AI states in multiplayer lockstep, plus a clean foundation for future state serialization (save games, replays, or state inspection tools).
