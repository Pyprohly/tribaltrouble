# The geometry registry

`assets/geometry/geometry.xml` lists every model the game draws. The geometry converter turns it into binary sprites plus four text files (`attachments.txt`, `skins.txt`, `decorations.txt`, `lights.txt`) that the game reads at startup, so a new entry here needs no Java. The Blender add-on writes these entries; this page is for reading them.

**Keep `assets/schemas/geometry.rng` in step.** The schema declares every attribute below. When a new attribute is added here, add it to the schema in the same commit; the add-on test `every_registry_attribute_is_declared_in_the_schema` fails otherwise.

## Layout

```xml
<geometry>
  <group name="vikings">
    <sprite name="peon" scale="1">
      <skeleton>vikings/peon/peon_skeleton.xml</skeleton>
      <model r="255" g="255" b="255">vikings/peon/peon_mesh.xml
        <texture name="viking_peon" team="viking_peon_team"/>
      </model>
      <model r="255" g="255" b="255">vikings/peon/peon_low_poly_mesh.xml
        <texture name="viking_peon" team="viking_peon_team"/>
      </model>
      <animation name="run" wpc="1" type="loop">vikings/peon/peon_run.xml</animation>
    </sprite>
  </group>
</geometry>
```

A group is a folder of related sprites: `vikings`, `natives`, `misc`. A sprite is one drawable thing. Its first `model` is the close-up mesh, a second one is the far-away mesh. `r g b` tint the model, `255 255 255` for none. Each `texture` names a PNG in `assets/textures/models` without the extension; several textures on one model are tiers the game picks between (rock, iron, rubber). `animation` lines name clip files; `type` is `loop` or `plain` (plays once), `wpc` is world units travelled per cycle for walks.

## Sprite attributes

| Attribute | Value | Meaning |
|---|---|---|
| `name` | letters, digits, underscores | Unique within the group. Becomes the file name of the binary sprite. |
| `scale` | float | Uniform scale applied on load. |
| `base` | a sprite name in the same group | This sprite inherits the base's skeleton and clips and carries none of its own. Required with `slot`. On a unit skin it keeps the clips of the unit it replaces. |
| `slot` | `hat`, `weapon`, `belt`, `magic`, `carried`, `prop` | Makes the sprite an item of its base. A unit shows at most one item per slot; `prop` is for things placed around buildings, trees and scenery. |
| `default` | `true` | Every unit of the base starts wearing this item. Items in a slot cycle default first, then by name. |
| `event` | an event name such as `halloween` | The sprite exists only while that event is on. Without it the sprite is loaded all year. |
| `skin` | a skin name | This sprite is another look for the sprite in `replaces`. With `event` everyone sees it during the event; without one, only players who own the skin. Requires `replaces`. |
| `replaces` | a sprite name in the same group | The stock sprite this skin stands in for. A skin of an item keeps the item's `base` and `slot`. |
| `decoration` | ground names, comma separated: `grass`, `dirt`, `beach`, `snow`, `land` | Map scenery the game scatters on that ground on every map. Cannot combine with `slot` or `skin`. |
| `count` | 1 to 1000 | How many of a decoration one map gets, 20 when absent. Only valid with `decoration`. |

Rules the converter enforces: a `slot` without a `base`, a `skin` without a matching `replaces`, a `count` without a `decoration`, and an item skin whose `base` or `slot` differs from the item it replaces are all build errors.

## Texture attributes

| Attribute | Value | Meaning |
|---|---|---|
| `name` | PNG name | The texture, in `assets/textures/models`. |
| `team` | PNG name | The team color decal, in `assets/textures/teamdecals`. |
| `normal` | PNG name | Reserved for a normal map; nothing writes it yet. |
| `emissive` | PNG name | A glow image added on top of the lit texture. Black adds nothing. |
| `light_color` | `#rrggbb` | With `emissive`, the glow also lights its surroundings at night in this color. |
| `light_strength` | float | Brightness of that light, 1 is normal. |
| `light_reach` | float, meters | How far the light reaches. |

The three `light_` attributes go together and need `emissive`. The converter finds up to four bright spots of the glow image on the mesh and writes them to `lights.txt`; the game draws the eight lights nearest the camera.

## Event stand-ins for carried items

A carried item (`slot="carried"` on the peon: wood, rock, rubber and the paddles) is asked for by name from code. During an event a sprite named `<name>_<event>` with that `event` stands in for `<name>`, so `wood_resource_halloween` replaces `wood_resource` in October. Event skins work on carried items too.

## Which events are on

By date, `autumn` through October and `halloween` from October 16 to 31. The system property `com.oddlabs.tt.event=a,b` overrides the date; `-` turns every event off. Events are render-only: players in one game may see different looks.
