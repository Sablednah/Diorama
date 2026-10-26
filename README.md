# SableCraft Diorama

An isometric web map of your Minecraft world, rendered on the server — the classic "diorama" view
of Dynmap's HD maps and the Minecraft Overviewer, for **NeoForge 26.2**.

Open it in a browser. Nobody needs to install anything: it is a web page.

- **Every block drawn from its real model.** Diorama reads the same blockstate and model files the
  game renders from, so stairs, fences, panes, slabs and modded blocks look like themselves.
- **Single player too.** The same jar serves your map on `http://127.0.0.1:8124/` while you play,
  and tells you the link when you join. It is never published to your network.
- **Follows the world.** A block placed, broken, blown up or pushed reaches the map in seconds.
  The copy is budgeted per second and drawing never runs on the server thread, so the map can fall
  behind but the server cannot.
- **Walk round it.** Four camera corners — turn with ⟲/⟳ or Q/E — so the street behind a tower is
  never hidden from every side.
- **Live player positions**, minus anyone the game is hiding: spectators, the invisible, and anyone
  `/vanish`ed by SableCraft Standards
  if it is installed.
- **By night too**, if you want it (`render.night`): lit by torches, lamps and lava under a moon,
  with a day/night switch on the page.
- **Markers and regions** from other mods, one switchable layer each — see [MARKERS-API.md](MARKERS-API.md).
  Built in: **Factions ReForged** territory in banner colours (1.12.0+), **SableCraft Standards**
  warps and `/spawn` (1.12.0+), and **owner regions** marked out with `/diorama region` or shipped
  in a datapack. Outlines follow the ground, so a claim on a hill is outlined on the hill.
- **An info bar** naming the block, biome and region under the cursor, with its real coordinates:
  the server casts the renderer's own ray rather than guessing a height.
- **H** hides every control for a clean view or a screenshot.
- **Links you can send**: the address always names the world, view, place and zoom on screen.
- Eight zoom levels, and a page that says why it is empty rather than looking broken.

## Setting up a dedicated server

A dedicated server has no block textures. Either:

- set `acceptDownload = true` under `[assets]` in `config/diorama-common.toml`, and Diorama fetches
  this version's client jar from Mojang once (checked against Mojang's own checksum) — the jar is
  Mojang's and covered by the Minecraft EULA, which is why this is your call and off by default; or
- put a client jar or a resource pack in `diorama/assets/` yourself.

Then open `http://<your server>:8124/`. The first start draws the whole world once; after that only
changes are redrawn.

| Command | Who | |
|---|---|---|
| `/diorama` | everyone | the map's link |
| `/diorama status` | operators | what it is doing, and why not if it is not |
| `/diorama render [dimension]` | operators | redraw a dimension from scratch |

Set `web.publicUrl` if the server sits behind a proxy, so `/diorama` can give a working link.
Every message is editable in `config/diorama/messages.yml`. `render.views` picks the camera corners;
each one costs as much rendering and disk as the first.

## Building

Java 25, NeoForge 26.2.0.72, ModDevGradle. `./gradlew build`; `./gradlew runServer -Pselftest` runs
the self-test against a real world.

## Licence

MIT. Bundles [Leaflet](https://leafletjs.com/) 1.9.4 (BSD-2-Clause). Ships no Mojang assets.
