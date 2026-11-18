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
control to its parent in the same decision, as the gist's parent state holds its child's as a
payload.

Orders with several stages carry them as a sealed phase rather than a set of nullable fields. A
`Gather` is `Approaching`, `Harvesting`, `Delivering` or `Depositing`; a `Repair` is `Mending` or
`Fetching`. Each phase holds the retry count for its own walks.

The `Execution` is `Unstarted` before the first decision, `Running` with the task the activity was started
for and how far it has got (`Activity.State`: `COMMITTED` mid-action, `YIELDING` at a point where it
may be replaced, or `DONE`), `Performing` while a world operation is carried out,
and `Gone` once the unit has left the world.

A ship's state is a `ShipState`: its `Task`, which is `Task.Inert` when docked or a `Voyage` with
its current leg (sliding off the shore, sailing, working loose), and a `ShipState.Execution` saying
whether nothing has run yet, an activity runs, or an operation is being carried out. Buildings and
ship proxies are always `Task.Inert`.

## Events

Everything a unit reacts to is a `UnitEvent`. Some come from the world: `Started`, an order, a stun, a
spell, death, mounting and unmounting. Others are the controller reporting back, grouped under
`UnitEvent.Report`: `Progressed` (sent when the activity starts or stops yielding) and
`Finished` from the running activity, and `Performed` or `Removed`
after an operation.
`UnitStateController`'s public methods (`order`, `stun`, `cast`, …) each build one and dispatch it to
the controller's `ReducerLoop`.

Both reducers implement `StateReducer<S, E, F>`, whose `reduce(state, event)` is the gist's
`handleEvent` and the only entry point. A reducer reads the world through the view it was built
with, a `UnitView` or, for a ship, whether it has slid off the shore, which its controller supplies, as the gist's parent machines pass
outside data down to their children.

`UnitStateReducer.reduce` is pure and returns a `Transition` of the next state and a list of
`UnitCommand`s for the controller. Two stop the running activity: `Interrupt` cancels it, or sets
it aside if it is the posture's, and `Release` lets go of a finished one. `DiscardPosture` cancels the
posture's activity once the posture is gone. `Run` starts a new activity for a plan, or runs the
posture's again when the controller holds it, and `Perform` carries out an operation. An event the unit does not react to, such as a stun
while dying or an activity report from a unit that has moved on, returns no commands.

A ship's events are `ShipEvent`s: `Started`, `Sailed` with a new destination and `Destroyed`, and
the controller's reports under `ShipEvent.Report`, `Finished` and `Performed`. `Sailed` and
`Destroyed` replace the ship's task, cancel the running activity and decide at once. `ShipStateReducer.reduce` returns a list of
`ShipCommand`s: `Interrupt` or `Release` the running activity, `Run` an activity, or `Perform` an
operation.

## Decisions

Inside `reduce`, the current task decides for a `UnitReason`, the task-level event: the parent
`UnitState` handles lifecycle events itself and turns the rest into a reason for the current task,
as the gist's hierarchical machines do. A ship's voyage decides the same way, for a `ShipReason`. Each decision
yields a `Step`: the next task state and a plan, either an activity to run (`UnitPlan.Run`, `ShipPlan.Run`) or a one-off
change to the world (`UnitPlan.Operation`, `ShipPlan.Operation`), which `replan` turns into commands.
After an operation the controller reports back and the task decides again with `UnitReason.PERFORMED`.

The machines nest as the tasks do. `UnitStateReducer` handles events and picks the slot that decides:
an interruption and the posture decide there, and an order decides in its `OrderReducer`. A
`Gather` or `Repair` that is harvesting hands its `Harvest` to a `HarvestReducer`. Both child
machines are `UnitTaskReducer`s: they take their task and their own kind of reason, and return the
task's next `Step`, or null once it is done and the parent decides instead. The parent narrows its
reason for the child, as the gist's failable `init?` does: `HarvestReason.of` keeps only the end of a
search for a supply, which is the one reason a harvest tells apart.

Reasons say why the task is deciding: `Begin` when it became current, `Completed` when its activity
finished, `Performed` after an operation, and `FoundSupply`, `FoundStore` and `Spotted` from walks and
watches. A ship's `ShipReason` has its own `Begin`, `Completed` and `Performed`, and `Blocked` and
`NoRoute` from sailing. `Begin` restores an order's retry budget, so a task interrupted by a stun or a spell starts over
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

One rule decides when the running activity is replaced. `UnitStateReducer.reconsiderWhenYielding`
takes a decision as soon as the running activity is yielding and waits while it is committed. Urgent
inputs (stun, spell, death, mounting) go through `reconsiderAtOnce`, which interrupts it at once,
committed or not. Either way nothing is replaced
while the running activity is still the current task's. A posture's activity is set aside rather
than cancelled while something runs above it, and run again when the posture does, so idle scan
timers and ship boarding progress survive orders and interruptions. The controller holds it until
the posture is replaced, by mounting or unmounting, or the unit leaves the world; `replan` emits
`DiscardPosture` when it sees the posture change. Whether
a new activity is committed before its first frame is a property of its plan
(`UnitPlan.Run.initialProgress()`).

All of this is simulation code: the determinism rules in `AGENTS.md` apply. RNG draws happen in
activities, in the order they animate.
