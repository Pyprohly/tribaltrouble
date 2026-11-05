# Unit and ship tasks

How a unit or ship decides what to do, in `com.oddlabs.tt.model.behaviour`. The design follows
the pure-state-machine-with-effects pattern described in
<https://gist.github.com/andymatuschak/d5f0a8730ad601bcccae97e8398e25b2>: state values and decisions
are pure, and a thin controller carries them out.

## State

A unit's whole task state is one `UnitState` value with three named slots. There is no stack.

| Slot | Type | Holds | Changed by |
|---|---|---|---|
| Posture | `Posture` | What the unit does with nothing else to do: `Idle` (on land or in a tower), `Fighting` or `Sitting` on a ship | Mounting and unmounting only |
| Order | `Order` | The job it was given: `Walk`, `Defend`, `Hunt`, `Attack`, `Gather`, `Repair`, `Enter`, `Build`, `Transfer` | Players, AIs and buildings; a new order replaces the old one |
| Interruptions | `List<Interruption>` | `Stunned`, `Casting`, `Dying`, first to last | Stuns, spells, death |

The first interruption runs, else the order, else the posture. `UnitState.primary()` is what the
AI, GUI and triggers see through `Selectable.getPrimaryTask()`: the order, else the interruption,
else the posture.

Tasks that run inside other tasks are fields of their parent, not separate stack entries: a `Walk`
or `Defend` holds the `Hunt` it broke off for, and `Gather` and `Repair` hold the `Harvest` that
fetches supplies. A task finishing hands control to its parent in the same decision.

A ship's state is a `Task`: `Task.Inert` when docked, or a `Voyage` with its current leg (sliding
off the shore, sailing, working loose). Buildings and ship proxies are always `Task.Inert`.

## Decisions

`UnitReducer.decide(state, event, view)` and `ShipReducer.decide(state, event, slid)` are pure.
They read the world through `UnitView` and change nothing. Each returns the next state and a plan:
either an activity to run (`UnitPlan.Activity`) or a one-off change to the world
(`UnitPlan.Operation`), after which the unit decides again with `Event.PERFORMED`.

Events say why the task is deciding: `Begin` when it became current, `Completed` when its behaviour
finished, `Found` and `Spotted` from walks and watches, and `Blocked` and `NoRoute` from ships.
`Begin` restores an order's retry budget, so a task interrupted by a stun or a spell starts over
rather than giving up.

## Execution

`UnitController` and `ShipController` are the imperative shell. They hold the state, turn plans into
`Behaviour` instances, and perform operations. Behaviours keep animation, movement and timing, and
report what they found through `Behaviour.outcome()` instead of calling back into the controller.

One rule decides when the running behaviour is replaced. A decision is taken as soon as the running
behaviour is interruptible and waits otherwise. Urgent inputs (stun, spell, death, mounting)
cancel it at once. A posture's behaviour is parked, not cancelled, while something runs above it,
so idle scan timers and ship boarding progress survive orders and interruptions.

All of this is simulation code: the determinism rules in `AGENTS.md` apply. RNG draws happen in
behaviours, in the order they animate.
