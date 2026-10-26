# The markers API

Put things on the map — homes, warps, claims, quest givers — from another mod.

**Status: built 2026-10-26. One consumer: Diorama's own spawn marker.** No other mod reports into
it yet; Standards (warps, homes) and Factions (claims, standards) are the intended first ones, and
that is their sessions' call. Until one does, the seam has only been driven by its own self-test —
the series' "first real input" risk applies in full.

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

## What it is not

Not a way to draw areas (claims as filled regions) — markers are points. Areas are the obvious next
shape, and the decision on how to draw a region on an isometric map (outline at what height?) is
worth making with a real consumer in hand rather than guessed now.
