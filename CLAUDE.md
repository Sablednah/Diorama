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

## Gotchas already paid for

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
- Lighting: everything is drawn at noon. Sky light is in `SectionData` already.
- Quads that extend outside their voxel (rare) are clipped to it.
- Markers: Standards warps/homes, Factions claims, CityWorld districts — via a seam, as with
  JourneyMap (`../SableCraft-Standards/MAP-API.md`).
