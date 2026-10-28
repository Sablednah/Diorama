# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

**Diorama** (*SableCraft Diorama*, mod id `diorama`) — an **isometric web map** of a Minecraft
world, rendered on the server, in the spirit of Dynmap's HD maps and the Minecraft Overviewer. You
open it in a browser. Part of the SableCraft series; siblings live in `../`.

It exists because the owner loved Dynmap's isometric look, and **no officially supported mod draws
isometric maps on NeoForge 26.x** (checked 2026-10-25): official Dynmap has no NeoForge build past
1.21.x (only an unofficial community port), BlueMap is a free-flying WebGL 3D view rather than
fixed isometric tiles, and squaremap is flat 2D. **City World** is the showcase: an iso map is what
a generated city wants to be seen as.

| | |
|---|---|
| Minecraft | **26.2 first** (`main` is the 26.2 line for now); other lines follow once the renderer is right |
| Loader | NeoForge 26.2.0.72 |
| Java | 25 (Gradle itself is launched on the JDK 21 below; foojay provisions 25) |
| Build | Gradle 9.2.1 + ModDevGradle 2.0.144 |
| Licence | MIT. Bundles Leaflet 1.9.4 (BSD-2, `src/main/resources/assets/diorama/web/leaflet/LICENSE`) |
| Package | `com.sablednah.diorama` |

## Owner decisions — do not re-litigate

- **A web map only. No in-client map, ever.** *"JourneyMap does that and does it right and well.
  An iso map would not be right in-client."*
- **Standalone mod**, not part of Standards.
- **Single player works too**: the integrated server runs the same code and serves the page on
  `127.0.0.1`. The player is told the link on join.
- **Textures come from the client jar**, downloaded and extracted on first run if needed. This is
  the fix for Schemy (`../SchematicViewer`, GitHub `Sablednah/Schemy`), whose hand-written block
  geometry and averaged colours were "never perfect". Diorama renders from the game's own
  blockstate and model JSON instead — see below.
- The download is **opt-in** (`assets.acceptDownload`, default false) because the jar is Mojang's
  and under the EULA; that is the owner-of-the-server's call, the same stance BlueMap takes.

## Build & run

```bash
export JAVA_HOME=/mnt/d/Repos/sable/MobHealth-Forge/tools/jdk21
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew compileJava              # fast inner loop
./gradlew build                    # -> build/libs/diorama-<version>+mc26.2.jar
./gradlew runServer                # dev server, game dir run-mc26.2/, port 25600
./gradlew runServer -Pselftest     # same, running SelfTest once assets are loaded
./gradlew runClient                # single player: map on http://127.0.0.1:8124/
```

- Dev server game port **25600** (free in the series table); the map's web port defaults to
  **8124**. Kill a previous `runServer` by matching **this repo's path**, never `fml.modFolders` —
  the sibling mods run dev servers on the same machine.
- `-Pselftest`, not `-D`: a `-D` on the gradlew line lands on Gradle's JVM.
- 26.2 sources for reference: unzip `build/moddev/artifacts/minecraft-patched-26.2.0.72-sources.jar`
  **onto the Linux filesystem** (the scratchpad), not `/mnt/d` — on drvfs it takes many minutes.

## How it works

```
asset/   AssetSource, Assets       jars & packs → bytes; textures (first animation frame)
model/   BlockModels, Quad         BlockState → baked faces, a port of the client's FaceBakery
world/   ChunkSnap, ChunkStore     immutable chunk copies; region-header scan; IOWorker reads
render/  IsoRenderer, View         one ray per pixel; four camera corners
         MapRenderer, TileStore    dirty tiles, worker pool, PNGs, zoom-out pyramid
         BlockTints                the client's BlockColors table, server-side
web/     MapWebServer              JDK HttpServer: page, tiles, /api/config, /api/players
neoforge/                          lifecycle, events, /diorama, Lang, Hidden, SelfTest — the only glue
```

### 1. Every block is drawn from the game's own JSON

`BlockModels` reads `blockstates/*.json` (variants, multipart with `OR`/`AND`/`a|b`/`!x`), walks
the model parent chain, and bakes each element face **following `FaceBakery.bakeQuad` step for
step**: `FaceInfo` vertex order, `defaultFaceUV`, element rotation (single-axis *or* 26.x's
x/y/z Euler form) with `rescale`, variant rotation about the block centre, and uvlock. The rotation
maths it calls — `BlockMath`, `Quadrant`, `OctahedralGroup`, `Transformation` — are **common code
on 26.2**, so the server calls the same methods the client does. Do not "simplify" this into
per-block-type geometry; that is exactly what Schemy did.

Blocks whose model is only a particle texture (chests, signs, beds, banners, heads — anything with
an entity renderer) fall back to their **outline shape in their particle texture**. The self-test
logs the full fallback list; a plain cube appearing in it means the dispatch broke.

### 2. Where assets come from, first match wins

1. `diorama/assets/` in the game directory — owner overrides: resource packs (zip or folder), or a
   client jar fetched by hand.
2. Every loaded mod's `JarContents`. Mod jars carry `assets/` even on a dedicated server, so
   **modded blocks get their real models**. In single player the `minecraft` mod file *is* the
   client, so nothing is downloaded.
3. `diorama/cache/client-<version>.jar`, downloaded from Mojang's manifest and SHA-1 checked —
   only if (1) and (2) lack vanilla's models and the owner accepted.

`BlockTints.initColorMaps` fills `GrassColor`/`FoliageColor`/`DryFoliageColor` from the colour-map
PNGs. Those classes are common code whose tables only the client fills; once filled,
`Biome.getGrassColor` (swamp noise included) answers correctly on a dedicated server.

### 3. The projection, and why rays

```
sx = (x − z) · W/2
sy = (x + z) · W/4 − y · W/2        W = pixelsPerBlock (default 32)
```

Every world point behind one pixel lies on a line along **(−1, −1, −1)**, so all rays share one
direction: the voxel walk steps every axis the same way, and the camera sees exactly the up, east
and south faces. Occlusion is free (first opaque texel ends the ray), cut-outs are skipped at
alpha < 8, and translucency composites front to back. Empty 16³ sections are skipped whole.
Water draws once at its surface as a **flat sheet of the biome's water colour** at alpha 190, then
stays transparent while the ray is in it. Not the water texture: repeated per block at map scale it
read as a blue-and-white grid (seen on the first CityWorld render, 2026-10-25).

**Four camera corners** (`View`: `se`, `sw`, `nw`, `ne`, Dynmap's naming — the corner the camera
stands at). Each is a proper quarter-turn into "view space", where the camera is always at
`+x', +z'`, so the projection above never changes: only the ray's world step `(dx, −1, dz)` and the
start point do. Never a mirror — that would put the light on the wrong side and draw every staircase
backwards. Each view is a full tile set, so each costs what the first did; `render.views` picks
them, and a view switched on renders on the next start. The page turns with ⟲/⟳ or Q/E and keeps the
spot in the middle of the screen in the middle.

Ray positions are **doubles**. In floats, a few thousand blocks out the nudge past each voxel
boundary is smaller than one ulp, so a ray stops advancing and spins out its step budget drawing
nothing — invisible near spawn, which is where every test looks.

**Profiled 2026-10-26** (JFR, full overworld render): the top costs were `FluidState.isEmpty` on
air, boxing a `Long` key to find the chunk on every step, and a fluid **tag** lookup. Air now
`continue`s before anything else, the last chunk is cached, and water is a type compare. The ray
walk itself (`Quad.intersect`) was fourth. A full render went 80 s → 16 s.

### 4. Nothing runs on the server thread

- **Saves** arrive through `ChunkDataEvent.Save`, whose `SerializableChunkData` is **already a
  copy** the game made for its own save. We keep that reference — no copying — and the coordinator
  thread turns it into a `ChunkSnap`.
- **Live changes** do not wait for a save (up to five minutes). `BlockEvent.NeighborNotifyEvent`
  fires for nearly every real change — placing, breaking, pistons, explosions, flowing fluid, and
  non-`strict` `/setblock` and `/fill` — and only records the chunk. Once a second the tick copies
  up to `render.liveChunksPerSecond` of them with the game's own `SerializableChunkData.copyOf`
  and queues the copy **exactly as if a save had handed it over**, so there is one path from a
  chunk to a tile. The budget is what stops a TNT crater becoming a server-thread stall; the rest
  wait, oldest first. Changes made with no neighbour update (crop growth, `strict` commands) still
  arrive at the next save. Measured: a `/fill` with no save reached its tile in 8 s.
- **Stored chunks** are read through the game's own `ChunkMap.read` (the `IOWorker`, which already
  serialises against pending writes), upgraded with the datafixer if older, and parsed with
  `SerializableChunkData.parse`. We only ever read a region file's 4 KiB **header** ourselves, to
  learn which chunks exist.
- ⚠ **26.1 moved dimension folders**: `DimensionType.getStorageFolder(dim, worldRoot)` gives
  `dimensions/minecraft/overworld/…`. Never hand-build `world/region`.
- Only `ChunkStatus.FULL` chunks render. A half-generated chunk at the edge is not something a
  player saw.

### 5. Tiles

`<world>/diorama/tiles/<namespace_path>/<view>/<level>/<tx>_<ty>.png`, inside the **save**, so each
single-player world has its own map. Level 0 is ray cast; levels 1–8 are 2×2 downsamples. A chunk
marks dirty every tile its screen-space box touches (columns × the span from its lowest column top
to its highest). A tile's ray start height only ever rises (`MapRenderer.tops`), and until a full
render has surveyed every chunk, rays start at the build limit — cheap, because empty sections are
skipped, and it cannot miss a tower.

A world with no tiles gets one full render, unasked, on start. `/diorama render [dimension]`
redraws everything.

### 6. The web side

`com.sun.net.httpserver` — no framework. Every request path is matched against a fixed pattern
before touching disk. Bind address empty means **all interfaces on a dedicated server, 127.0.0.1 in
single player**, so playing alone never publishes your world to the network. Leaflet is
**bundled**, not loaded from a CDN, so single player works offline. Leaflet's `CRS.Simple` with
zoom `native = 8` puts one map pixel on one level-0 tile pixel, so the page converts world →
screen with the renderer's own formula.

The page **says why it is empty** — no textures, still loading, or the full-render progress —
rather than showing a blank map that looks broken (the series rule: *whenever correct behaviour
is to show nothing, say so*).

### 7. Who the map hides, and its words

`Hidden`: spectators, anyone invisible, and anyone `/vanish`ed by SableCraft Standards — a web map
is the easiest way there is to find somebody. Standards is looked up **by reflection, once**,
because Diorama must start without it; only `Vanish.isVanished` is needed, not `VanishEvent`, since
players are re-sampled every second. A Standards whose API moved fails **closed** (hidden).

`Lang`: every player-facing string, from `config/diorama/messages.yml`, merged on upgrade through
`messages.known` exactly as Standards' decision 6 describes. `&` codes are converted in templates
only, never in anything a player typed.

### 8. Single player — verified 2026-10-26 on Vivo

Run on Vivo's private display `:8` (claimed in `~/dev/README.md` there; launcher
`~/dev/dio-client.sh`) with a copy of the dev world: the integrated server took its textures from
the game's own jar (4 asset sources, **no download**), bound **`127.0.0.1:8124` only** — refused
from another machine — sent `Diorama: http://127.0.0.1:8124/` to chat on join, and drew the
overworld in all four views (277 s, sharing 8 cores with an llvmpipe client).

`./gradlew runClient -PquickPlay=<save>` opens a world with no menus, **except** two screens that
quick-play waits behind, each needing one click: the first-launch accessibility screen
(*Continue*), and, for a world with experimental settings (the CityWorld dev world has them),
*I Know What I'm Doing!*. Stop the client when done, by its working directory
(`/proc/<pid>/cwd` = `…/Diorama/runClient-mc26.2`), never by a pattern in your own command line.

### 8½. Night — opt-in, `render.night`

Off by default, so the day map the owner signed off on ("that's the iso map I wanted") is
untouched: day tiles take no light at all. On, every view gets a `<view>-night` tile set and the
page a ☀/☾ switch (N), and night rides in the link's view segment (`#…/nw-night/…`).

A face is lit by the block the ray crossed **just before** it — the air in front of it, which is
what the game lights that face by — so there are no normals to carry and slabs and other partial
shapes come out right. Colour is the brighter of torchlight (warm) and moonlight (cool, ×0.32) on
the game's own curve `f / (4 − 3f)`.

Light costs heap: `ChunkSnap` keeps the `DataLayer`s (up to 4 KiB a section) **only when night is
on**. A section with no stored sky layer reads as open sky when it holds no blocks and dark when it
does — the game stores nothing for the air above terrain. A chunk saved without light
(`isLightOn` false) draws moonlit-dark; nothing to be done about that short of relighting it.

### 8¾. Roofed worlds — the nether, cut open

A dimension whose **type** says `hasCeiling` is cut at `minY + logicalHeight − 10` (y 118 in the
vanilla nether, under the 123–127 bedrock). In every **column**, everything above the topmost
cavern under the cut — a run of at least four air blocks — is roof and is not drawn
(`ChunkSnap.roof`). Decided per dimension type rather than by name, so a roofless nether
(CityWorld's `ruined_nether`) is drawn from the sky like any other world. Column tops mean "first
floor under the roof" there, which is what region outlines and the pick follow.

Two versions it took, both visible on the first screenshot:

- **Per ray** ("draw nothing until the ray has crossed air") drew flat-topped prisms of netherrack
  the height of the cut: a ray that started in a cavern reaching y 118 had already "seen air" and
  drew the side of the roof hanging over the lower cavern next door.
- **Any air** counted a two-block bubble in the roof as a cavern, leaving a pillar of rock standing
  under it beside neighbours cut to the real floor. Hence the four-block run.

What remains — dark cliffs where the topmost cavern jumps between stacked levels — is the nether's
own layering, not a cut fault. Verified 2026-10-26 on a 4,096-chunk vanilla nether (`world-vanilla` on the
dev server): the self-test picks 49 points round spawn, all under the cut, none on bedrock.

Cross-section walls of netherrack are expected at the edge of generated land and wherever a
cavern's wall faces the camera — the same cut-away sides the overworld diorama has.

### 9. Markers — `api/Markers`, see `MARKERS-API.md`

**Pull, not push**: every five seconds the tick asks each registered set for its whole truth, so
there is nothing to take down and nothing to go stale (the JourneyMap pins that multiplied).
**Replace by id**, for the second world in one JVM. A throwing set is dropped for one refresh and
logged once. Diorama's own `SpawnMarkers` is the first consumer, so the path is exercised on every
server. Labels reach the page through `textContent`, never HTML.

### 10. Checking the page itself

The page has been looked at, not only its API: headless Chrome on Vivo, through an SSH reverse
tunnel to the dev server's web port (WSL's cached Chromium lacks system libraries):

```bash
ssh -i ~/.ssh/vivo_ed25519 -R 18124:localhost:8124 sable@192.168.7.246 \
  'google-chrome --headless=new --no-sandbox --user-data-dir=/tmp/dio-chrome --window-size=1280,800 \
   --virtual-time-budget=12000 --screenshot=/tmp/page.png "http://localhost:18124/#minecraft_overworld/nw/0/0/7"'
```

`--dump-dom` instead of `--screenshot` gives Leaflet's computed positions, which is how the first
marker bug was found: the DOM had it dead centre while the screen had it in the corner, because a
CSS `rotate` on the marker applies *before* Leaflet's `translate3d` and swung the whole position
45° about the page origin. Rotate an inner element. The screenshot's bottom ~70 px is headless
Chrome's window frame, not missing tiles. Links are `#world/view/x/z/zoom`, x/z at sea level.

## Gotchas already paid for

- ⚠ **An unscanned `ChunkStore` used to answer "no such chunk".** A chunk saved at boot redrew its
  tiles before the region files were scanned, so every neighbour looked missing: grey cut-away
  walls of stone, and an all-empty redraw *deleted* a good tile, leaving a hole. Only a full render
  scanned, and a world that has been surveyed once never full-renders again on start, so the
  damage stayed until `/diorama render`. `get()` now scans on first use. The self-test asks a fresh,
  never-scanned store for the spawn chunk.
- ⚠ **A restart used to forget two things, and a big map paid for both.** Each tile's ray-start
  height lived only in memory, so after a restart every ray started at the build limit and a tile
  read more chunks than the cache holds: 4 tiles a second instead of 23 on a 65,536-chunk CityWorld
  map. And the queue of tiles waiting to be drawn lived only in memory, so a crash or restart threw
  it away — 38,000 tiles of freshly generated land, and a night view a quarter drawn, that nothing
  would ever have filled. Now `heights.bin` (each chunk's height span) and `pending.bin` (the
  queue) sit beside the tiles; a map drawn before them is surveyed once on start (41,685 chunks in
  37 s, nothing redrawn); and every start queues any tile a known chunk should have drawn that has
  no file. Tiles that drew empty at the world's edge come back through that check each start, by
  design — few, and cheap.
- ⚠ **One pass must never draw everything.** `renderDirty` once took each view's whole backlog,
  and saved chunks are taken in only between passes — so a 183,000-tile pass held the coordinator
  for five hours, during which a whole dimension sat at "0 drawn" and **every chunk saved meanwhile
  waited in a memory queue that the next restart threw away**: 35,244 freshly generated chunks
  never reached the map. And the batch was removed from the queue as it started, so the status
  read "0 waiting" through it. Now: 512 tiles per view per pass (every world takes turns), tiles
  being drawn count as waiting and are saved with the queue, `stop()` takes in the last saves
  before writing `heights.bin`, and every start reads any chunk on disk with no recorded heights
  (`learnUnknown`) — so a crash costs a redraw, never a hole.
- ⚠ **A disk read must not overwrite a fresher save.** `ChunkStore.get` read a chunk from its
  region file while the save of the same chunk arrived through `accept()`, then `put` the older
  copy over it — often the half-generated one, which reads as "no chunk". The tile drew empty and
  was deleted: nine single-tile holes in 16,384 freshly generated chunks. `putIfAbsent` now; the
  fresher copy wins.
- ⚠ **Never hop to the server thread from the web thread and wait.** A dedicated server pauses
  its tick after 60 s empty (`pause-when-empty-seconds`), and an empty server is exactly when people
  browse the map: the CityWorld lookup timed out on every request until it called the
  (documented thread-safe) API directly.

- ⚠ **Gradle's file-system watching does not work on `/mnt/d`** (drvfs sends no inotify). A newly
  added class compiled as `cannot find symbol` from the class that used it. `gradle.properties`
  sets `org.gradle.vfs.watch=false`; do not remove it.
- ⚠ **Never `./gradlew --stop`** to clear a daemon here: it stops every daemon of that Gradle
  version on the machine, sibling mods' dev servers included.
- ⚠ **drvfs file times run ~40 s behind WSL's clock.** A "was this tile rewritten since T" check
  by mtime said no while the tile had been redrawn. Compare contents (`md5sum`), not times.
- `runServer` ends `BUILD FAILED` when the server is killed. That is the kill, not a build fault.

## Known gaps, in rough priority

- A top-down flat view for very low zooms.
- Quads that extend outside their voxel (rare) are clipped to it.
