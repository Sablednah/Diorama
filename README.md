# SableCraft Diorama

An isometric web map of your Minecraft world, rendered on the server — the classic "diorama" view
of Dynmap's HD maps and the Minecraft Overviewer, for **NeoForge 26.2**.

Open it in a browser. Nobody needs to install anything: it is a web page.

- **Every block drawn from its real model.** Diorama reads the same blockstate and model files the
  game renders from, so stairs, fences, panes, slabs and modded blocks look like themselves.
- **Single player too.** The same jar serves your map on `http://127.0.0.1:8124/` while you play,
  and tells you the link when you join. It is never published to your network.
- **Follows the world.** Chunks reach the map when the server saves them; nothing runs on the
  server thread, so the map can fall behind but the server cannot.
- **Live player positions**, eight zoom levels, and a page that says why it is empty rather than
  looking broken.

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

## Building

Java 25, NeoForge 26.2.0.72, ModDevGradle. `./gradlew build`; `./gradlew runServer -Pselftest` runs
the self-test against a real world.

## Licence

MIT. Bundles [Leaflet](https://leafletjs.com/) 1.9.4 (BSD-2-Clause). Ships no Mojang assets.
