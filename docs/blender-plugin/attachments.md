# How the game draws registry items

`registry.md` next to this file lists the attributes an entry in `assets/geometry/geometry.xml` can carry. This page is the other half: what the game does with each kind of entry once the converter has run. Everything here is render-only. Nothing a hat, prop, skin or decoration does reaches the simulation, so players in one game may see different looks and replays stay in step.

## Items on units

An item is a sprite with `base` and a `slot`. It inherits the unit's skeleton and clips, so its file holds only the mesh and the game shares the unit's clip data with it.

- At load the game reads `attachments.txt` and wires each item onto every template of its base unit. No Java is involved.
- A unit shows at most one item per slot. `default="true"` items are on from the start.
- An item with one texture uses it for every tier. An item with as many textures as the unit has tiers follows the tier, as the warrior's axe does.
- Items in a slot are ordered default first, then by name. With cheats on (`/iamacheater` in chat), H cycles the `hat` slot on the selected units. It is a cheat because the choice is local: other players will not see it until it travels on the wire.

Carried items (`slot="carried"`: wood, rock, rubber and the two paddles) are the same mechanism, but the simulation decides what a peon holds and the game asks for them by name. They never enter the player-facing slots and H does not touch them.

## Props on buildings and scenery

A prop is a sprite with `slot="prop"` whose `base` is the thing it sits on. It has no bone. It is drawn where it was placed, relative to the base, and follows the base's position, rotation and scale. A falling tree takes its props down with it.

- On a building the `base` is one stage: `quarters`, `quarters_halfbuilt` or `quarters_start`. The prop shows only on that stage. Every prop registered on a stage is drawn; there is nothing to toggle.
- Trees are two sprites, `<kind>_trunk` and `<kind>_crown`, and a prop may sit on either. Trees draw their full mesh at any distance and so do their props.
- Rocks and iron share a mesh with two textures. A prop with two textures follows that; with one, the same texture shows on both.
- The chicken is animated, so its prop inherits the chicken's skeleton and clips and is skinned to a bone like a unit item.
- Other sprites draw their props at their own detail level and leave them out where the sprite is only a dot on the map.
- Props are not picked and do not change the base's bounds.

## Skins

A skin sprite carries `skin` and `replaces`. Wherever the game would draw the stock sprite it draws the skin sprite instead, with the same texture tier, or the first texture when the skin has fewer.

- **Owned skins** (no `event`) belong to a player. Every client draws that player's units, buildings and items with them. Nothing assigns a skin to a player yet; `RacesResources.getSkins(name)` hands one to `Player.setSkins`.
- **Event skins** (`event="halloween"`) belong to nobody and replace the default look for everyone while the event is on. They work on anything, including trees and rocks nobody owns. Where a player owns a skin for the same sprite, the owned skin wins.
- A unit skin must have the clips of the unit it replaces; the game refuses one whose clip list differs.
- A building skin replaces one stage. The stage's own props stay, and props whose `base` is the skin sprite are drawn as well.
- An item skin is listed in `skins.txt` only, never in `attachments.txt`, so nobody wears it as an extra item.
- Skins keep the stock sprite's bounds and clip timing.

## Map decorations

A decoration is a static sprite with `decoration` naming the ground it stands on. The game scatters it over every generated map after trees, rocks and iron are placed.

- A spot must be dry, reachable ground that no tree or resource holds. One decoration takes one unit grid cell, so two never overlap.
- Placement uses its own random generator seeded from the map seed, so a map always gets the same decorations.
- Decorations are drawn at any distance and are not picked. Units walk through them.

## Which events are on

By date, `autumn` through October and `halloween` from October 16 to 31. The system property `com.oddlabs.tt.event=a,b` overrides the date; `-` turns every event off. A sprite with `event` is not loaded at all outside its event.

During an event a carried item named `<name>_<event>` stands in for `<name>`, for example `wood_resource_halloween`. An event skin on the carried item does the same.

## Bones for items

Bone names differ per skeleton and an item must be skinned to the exact string. The add-on resolves these from the **Attach To** dropdown (`ATTACHMENT_POINTS` in `tools/blender/io_tribaltrouble/rig.py`).

| Point | Peon (both races) | Viking warrior | Native warrior | Viking chieftain | Native chieftain | Chicken |
|---|---|---|---|---|---|---|
| head | `peon Head` | `warrior  Head` (two spaces) | `Head` | `Head` | `Head` | `chicken Head` |
| back | `peon Spine2` | `warrior  Spine1` | `Spine2` | `Spine1` | `Spine2` | `chicken Spine` |
| right hand | `peon R Hand` | `warrior  R Hand` | `R Hand` | `R Hand` | `R Hand` | |
| left hand | `peon L Hand` | `warrior  L Hand` | `L Hand` | `L Hand` | `L Hand` | |
| prop1 | `peon Prop1` | `warrior  Prop1` | `Prop1` | `Prop1` | `Prop1` | |
| prop2 | | | | `Prop2` | `Prop2` | |
| prop3 | | | `Prop3` | `Prop3` | `Prop3` | |
| belt | `peon Pelvis` | `warrior  Pelvis` | `Pelvis` | `Pelvis` | `Pelvis` | |

Items are authored in the unit's bind pose; **New Prop...** snaps them there. Two quirks of the source files the tools handle: corner normals are not unit length (the game normalizes at load), and a vertex can list the same bone twice (the game sums the entries).
