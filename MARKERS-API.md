# The markers API

Put things on the map — points and areas — from another mod.

**Status: built 2026-10-26; regions added the same day.** Consumers, all inside Diorama:
its own spawn marker, owner regions (`/diorama region` and datapacks), **Factions territory**
(reads `FactionsApi`, Factions 1.12.0+) and **Standards warps and `/spawn`** (reads
`api.places.Places`, Standards 1.12.0+). No outside mod registers a set itself yet.

## Using it

```java
// In a class that is only loaded when Diorama is present:
if (ModList.get().isLoaded("diorama")) DioramaHook.register();

final class DioramaHook {
    static void register() {
        Markers.register(new Markers.MarkerSet() {
            public String id()    { return "standards:warps"; }
            public String label() { return "Warps"; }
            public Collection<Markers.Marker> markers(ResourceKey<Level> level) {
                return warpsIn(level).stream()
                        .map(w -> new Markers.Marker(w.name(), w.name(), w.x(), w.y(), w.z(), 0x6CC4FF))
                        .toList();
            }
        });
    }
}
```

That is all of it. There is no add, move or remove.

## The decisions

- **Pull, not push.** Diorama asks every set for its markers every five seconds, on the server
  thread, and publishes whatever comes back. A set reports its whole truth each time, so nothing
  can go stale: Factions' JourneyMap waypoints multiplied because every push minted a new pin and
  nothing took the old one down, and a captured banner stayed pinned where it no longer stood. A
  pull cannot do that.
- **Replace by id.** A single-player game that opens a second world registers again in the same
  JVM; an append-only registry would draw everything twice. `SelfTest` registers one set twice and
  asserts it appears once.
- **A failing set is dropped, not fatal.** An exception skips that set for one refresh and logs
  once; the other sets still publish. Asserted by the self-test with a set that always throws.
- **`y` is required.** On an isometric map a point lands somewhere different on screen for every
  height. A marker at sea level beside a hilltop warp floats beside it, not on it.
- **Labels are plain text.** The page inserts them with `textContent`, never as HTML, because they
  come from other mods and, through them, from whatever players typed.
- **Each set is a layer** the viewer can switch off; `shownByDefault()` picks whether it starts on.

## Regions

A set can also return `regions(level)`: areas, each a `Markers.Region(id, label, colour, detail,
shape)`. Two shapes, because land comes two ways:

- `Markers.Chunks(Set<Long>)` — whole chunks, packed with `ChunkPos.pack`. Claims.
- `Markers.Polygon(List<double[]{x, z}>, Double y)` — corners in order, like Dynmap's area markers.
  `y` null follows the ground; a number draws it flat at that height.

**How it is drawn, decided with Factions in hand:** an outline, never a fill, laid on the ground.
On an isometric map one x, z is a different screen point at every height, so a border at sea level
slides off a hilltop claim. Every edge is walked a block at a time and placed on the column just
inside the land, with a vertical step wherever the ground steps — the same rule as Factions' border
walls in game. A side shared by two chunks of one region is interior and draws nothing.

Tracing happens on Diorama's coordinator thread, not the server thread, from the chunk copies it
already holds; an outline is cached while its shape is unchanged and re-laid every minute so it
follows building and digging. The page also gets each region's land, so the info bar can name the
region under the cursor.

## Owner regions

`/diorama region create <id> [label]`, then stand at each corner and `/diorama region corner <id>`;
`undo`, `label`, `detail`, `colour #e8a33d`, `height <y>|ground`, `delete`, `list`. Ops only.
Saved to `<world>/diorama/regions.json`.

Datapacks: `data/<namespace>/diorama/regions/<name>.json`, same format, re-read on `/reload`:

```json
{ "label": "Old Town", "dimension": "minecraft:overworld", "colour": "#e8a33d",
  "detail": "Founded by the first three players",
  "corners": [[-40, -80], [40, -80], [40, 0], [-40, 0]] }
```

`"chunks": [[cx, cz], …]` may replace `corners`; `"y": 72` pins the height.

## What the integrations show, and what they refuse to

- **Factions**: territory in the banner colour, "name [tag]", "peaceful · N chunks" — exactly what
  its own JourneyMap layer shows every player. **Not homes, not where a standard stands**: a public
  web map pointing at the flag is a raid planner.
- **Standards**: warps and `/spawn`, which are public by design. **Not homes** — Standards' API does
  not offer them, and should not.

Both are reached by reflection, once, at start; an older mod leaves its layer off and
`/diorama status` says which version is needed rather than the layer silently never appearing.
