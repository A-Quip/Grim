# Fix: Piston events crash with ArrayIndexOutOfBoundsException (#2337, #2031)

**Issues:** [#2337](https://github.com/GrimAnticheat/Grim/issues/2337), [#2031](https://github.com/GrimAnticheat/Grim/issues/2031)
**Affected platform:** Bukkit / Paper / Folia (and forks such as Sakura, Luminol)
**Branch:** `fix/2337-piston-chunk-threadsafety`

## Symptom

A piston firing near a player throws, and the server logs:

```
Could not pass event BlockPistonRetractEvent to GrimAC vX.Y.Z
java.lang.ArrayIndexOutOfBoundsException: Index 486 out of bounds for length 257
    at ...fastutil.longs.Long2ObjectOpenHashMap.containsKey(Long2ObjectOpenHashMap.java:344)
    at ac.grim.grimac.utils.latency.CompensatedWorld.isChunkLoaded(CompensatedWorld.java:603)
    at ac.grim.grimac.platform.bukkit.events.PistonEvent.onPistonRetractEvent(PistonEvent.java:135)
    ...
```

The same trace also appears for `BlockPistonExtendEvent` (`onPistonPushEvent`). It happens semi-randomly, most often around active redstone / piston farms (bamboo farms, etc.).

## Root cause

`PistonEvent` runs on the **server tick thread** (or a Folia **region thread**). For each tracked player it called:

```java
if (isCloseEnough(...) && player.compensatedWorld.isChunkLoaded(chunkX, chunkZ)) { ... }
```

`CompensatedWorld.isChunkLoaded()` reads the `chunks` map, which is a fastutil `Long2ObjectOpenHashMap`:

```java
public final Long2ObjectMap<Column> chunks; // = new Long2ObjectOpenHashMap<>(...)
```

That map is **not thread-safe** and is written on the player's **netty thread** (chunk load/unload packets add and remove entries via `addToCache` / `removeChunk`). Reading `containsKey()` from the event thread while the netty thread is mutating (and internally resizing/rehashing) the open-addressing table produces a corrupted probe index — surfacing as the `ArrayIndexOutOfBoundsException` thrown from deep inside fastutil.

In short: a cross-thread read of a single-thread data structure.

## The fix

Grim already schedules the real work (`activePistons.add(data)`) onto the player's netty thread via `latencyUtils.addRealTimeTaskAsync(...)`. The only thing still touching the chunk map on the wrong thread was the `isChunkLoaded` **gate**.

The fix moves that gate **inside** the scheduled task, so the chunk-map read happens on the netty event-loop thread — the same thread that mutates the map — eliminating the race:

```java
for (GrimPlayer player : GrimAPI.INSTANCE.getPlayerDataManager().getEntries()) {
    if (isCloseEnough(sourcePos, player.compensatedEntities.self.trackedServerPosition.getPos())) {
        final int lastTrans = player.lastTransactionSent.get();
        PistonData data = new PistonData(blockFace, boxes, lastTrans, true, hasSlimeBlock, hasHoneyBlock);
        player.latencyUtils.addRealTimeTaskAsync(lastTrans, () -> {
            if (player.compensatedWorld.isChunkLoaded(chunkX, chunkZ)) {
                player.compensatedWorld.activePistons.add(data);
            }
        });
    }
}
```

The cheap distance gate (`isCloseEnough`) stays on the event thread — it only reads a position reference and never touches the chunk map, so it still avoids scheduling tasks for far-away players. Evaluating `isChunkLoaded` at task-run time is also semantically more correct: it reflects chunk state at the transaction the piston is tied to, rather than at event-fire time.

The change is applied to **both** `onPistonPushEvent` and `onPistonRetractEvent` in
`bukkit/src/main/java/ac/grim/grimac/platform/bukkit/events/PistonEvent.java`.

## How to reproduce

1. Paper/Folia server (1.20.5–26.2) with GrimAC installed.
2. Build a fast redstone contraption that repeatedly extends/retracts pistons near a chunk border (a bamboo/observer farm reliably triggers it), or have players move around while pistons fire near chunk load/unload boundaries.
3. Keep at least one player nearby so the per-player loop runs.
4. Before the fix: intermittent `Could not pass event BlockPiston*Event to GrimAC` with an `ArrayIndexOutOfBoundsException` from `Long2ObjectOpenHashMap.containsKey`.
5. After the fix: no exception; piston lenience continues to work normally.

## Verification

- `./gradlew :bukkit:shadowJar` compiles cleanly.
- The built jar in this folder contains the patched `PistonEvent`.
- Manual review confirms both piston handlers now read the chunk map only from the netty thread.
