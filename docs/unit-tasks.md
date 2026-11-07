# Unit and ship tasks

How a unit or ship decides what to do, in `com.oddlabs.tt.model.task`. The design follows
the pure-state-machine-with-effects pattern described in
<https://gist.github.com/andymatuschak/d5f0a8730ad601bcccae97e8398e25b2>: state values and decisions
are pure, and a thin controller carries them out.

## State

A unit's whole state is one `UnitState` value: its `TaskSlots` and the `Execution` saying what its
controller is doing for those tasks.

`TaskSlots` holds one task per named slot, in a fixed priority order.

| Slot | Type | Holds | Changed by |
|---|---|---|---|
| Posture | `Posture` | What the unit does with nothing else to do: `Idle` (on land or in a tower), `Fighting` or `Sitting` on a ship | Mounting and unmounting only |
| Order | `Order` | The job it was given: `Walk`, `Defend`, `Hunt`, `Attack`, `Gather`, `Repair`, `Enter`, `Build`, `Transfer` | Players, AIs and buildings; a new order replaces the old one |
| Casting | `Interruption.Casting` | The spell being cast, or waiting for a stun to wear off | Spells; a new spell replaces one not yet cast |
| Stun | `Interruption.Stunned` | A stun, which suspends a spell and restarts it when it wears off | Stuns; a new stun replaces the old one |
| Dying | `Interruption.Dying` | Death, which clears the order, stun and spell | Death only |

Dying runs, else the stun, else the spell, else the order, else the posture. `TaskSlots.primary()` is what the
AI, GUI and triggers see through `Selectable.getPrimaryTask()`: the order, else the interruption,
else the posture.

Tasks that run inside other tasks are fields of their parent, not separate slots: a `Walk`
or `Defend` holds the `Hunt` it broke off for, and the `Harvesting` phase of a `Gather` and the
`Fetching` phase of a `Repair` hold the `Harvest` that fetches supplies. A task finishing hands
control to its parent in the same decision.

Orders with several stages carry them as a sealed phase rather than a set of nullable fields. A
`Gather` is `Approaching`, `Harvesting`, `Delivering` or `Depositing`; a `Repair` is `Mending` or
`Fetching`. Each phase holds the retry count for its own walks.

The `Execution` is `Unstarted` before the first decision, `Running` with the task the activity was started
for and how far it has got (`Activity.State`), `Performing` while a world operation is carried out,
and `Gone` once the unit has left the world.

A ship's state is a `ShipState`: its `Task`, which is `Task.Inert` when docked or a `Voyage` with
its current leg (sliding off the shore, sailing, working loose), and a `ShipState.Execution` saying
whether nothing has run yet, an activity runs, or an operation is being carried out. Buildings and
ship proxies are always `Task.Inert`.

## Events

Everything a unit reacts to is an `Event`. Some come from the world: `Started`, an order, a stun, a
spell, death, mounting and unmounting. Others are the controller reporting back, grouped under
`Event.Report`: `Progressed` (sent when the activity becomes interruptible or stops being so) and
`Finished` from the running activity, and `Performed` or `Removed`
after an operation.
`UnitStateController`'s public methods (`order`, `stun`, `cast`, …) each build one and dispatch it to
the controller's `ReducerLoop`.

Both reducers implement `StateReducer<S, E, F>`, whose `reduce(state, event)` is the gist's
`handleEvent` and the only entry point. A reducer reads the world through the view it was built
with, a `UnitView` or, for a ship, whether it has slid off the shore, which its controller supplies, as the gist's parent machines pass
outside data down to their children.

`UnitStateReducer.reduce` is pure and returns a `Transition` of the next state and a list of
`UnitCommand`s for the controller: `Cancel` or `Release` the running activity, `Start` an activity
for a task's plan, `Perform` an operation, or `Leave` once the unit is out of the world. An event the unit does not react to, such as a stun
while dying or an activity report from a unit that has moved on, returns no commands.

A ship's events are `ShipEvent`s: `Started`, `Sailed` with a new destination and `Docked`, and
the controller's reports under `ShipEvent.Report`, `Finished` and `Performed`. `Sailed` and
`Docked` replace the ship's task, cancel the running activity and decide at once. `ShipStateReducer.reduce` returns a list of
`ShipCommand`s: `Cancel` or `Release` the running activity, `Start` an activity, or `Perform` an
operation.

## Decisions

Inside `reduce`, the current task decides for a `Reason`, the task-level event: the parent
`UnitState` handles lifecycle events itself and turns the rest into a reason for the current task,
as the gist's hierarchical machines do. A ship's voyage decides the same way. Each decision
yields the next task and a plan: an activity to run (`UnitPlan.Run`, `ShipPlan.Run`) or a one-off
change to the world (`UnitPlan.Operation`, `ShipPlan.Operation`). The reducers' `decide` methods
return these as a `Decision` of the task state and its plan, which `replan` turns into commands.
After an operation the controller reports back and the task decides again with `Reason.PERFORMED`.

Reasons say why the task is deciding: `Begin` when it became current, `Completed` when its activity
finished, `Found` and `Spotted` from walks and watches, and `Blocked` and `NoRoute` from ships.
`Begin` restores an order's retry budget, so a task interrupted by a stun or a spell starts over
rather than giving up.

## Execution

`UnitStateController` and `ShipStateController` are the imperative shell, and `Selectable` animates and
inspects them, along with the `InertTaskRunner` of buildings and ship proxies, as a `TaskRunner`.
`UnitStateController` holds the running activity and the posture's, `ShipStateController` the running
activity, and each a `ReducerLoop` that holds the state and reduces events into it. They carry out
the commands and turn plans into `Activity` instances. Activities keep animation, movement and timing, and report what they found through
`Activity.outcome()` instead of calling back into the controller.

Events raised while commands are carried out or while the running activity animates, such as the
mount that entering a tower causes, wait in the `ReducerLoop`'s FIFO queue until the current
reduction is done: each transition runs to completion before the next event is reduced, as in Harel
statecharts. An activity's own report is reduced ahead of anything it caused during its frame,
because it happened first, so a report always describes the activity the reducer last started. The
reducer still ignores a report that no longer applies, such as `Performed` after an event already
moved the unit on.

One rule, in `UnitStateReducer`, decides when the running activity is replaced. A decision is taken as
soon as the running activity is interruptible and waits otherwise. Urgent inputs (stun, spell,
death, mounting) cancel it at once. `UnitStateController` keeps a posture's activity, rather than
cancelling it, while something runs above it, and resumes it when the reducer starts the same
posture again, so idle scan timers and ship boarding progress survive orders and interruptions. How
interruptible a new activity is before its first frame is a property of its plan
(`UnitPlan.Run.initialProgress()`).

All of this is simulation code: the determinism rules in `AGENTS.md` apply. RNG draws happen in
activities, in the order they animate.
