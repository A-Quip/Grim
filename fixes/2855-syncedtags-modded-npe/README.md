# Fix: NPE spam from modded block tags (More Chest Variants) (#2855)

**Issue:** [#2855](https://github.com/GrimAnticheat/Grim/issues/2855)
**Affected platform:** Fabric (any setup with mods that add blocks/items outside PacketEvents' mappings)
**Branch:** `fix/2855-syncedtags-modded-npe`

## Symptom

With mods that register their own blocks/items (reporter used *More Chest Variants*), Grim spams:

```
java.lang.NullPointerException: Cannot invoke
"...StateType$Mapped.getStateType()" because the return value of
"...StateTypes.getMappedById(...)" is null
    at ac.grim.grimac.utils.data.tags.SyncedTags.lambda$new$0(SyncedTags.java:39)
    at ac.grim.grimac.events.packets.PacketServerTags.onPacketSend(PacketServerTags.java:24)
```

every time the server sends its tag data, and the tag sync never completes.

## Root cause

When the server sends a `WrapperPlayServerTags` packet, Grim rebuilds its synced tags. For each id in a tag, `SyncedTag.readTagValues` calls a **remapper** to turn the numeric id into a `StateType`/item:

```java
// SyncedTags (block tags)
trackTags(BLOCK, id -> StateTypes.getById(VERSION.toClientVersion(), id), ...);

// SyncedTag.readTagValues
for (int id : tag.getValues()) {
    values.add(remapper.apply(id));   // <-- throws for modded ids
}
```

For modded blocks/items whose ids are not present in PacketEvents' mappings, `StateTypes.getById(...)` internally calls `getMappedById(...)`, which returns `null`, and the subsequent `.getStateType()` throws `NullPointerException`. Because this happens inside the tags packet handler, it fires repeatedly and aborts the tag sync.

## The fix

Make `SyncedTag.readTagValues` tolerant of ids the remapper can't resolve — for modded/unknown ids, simply skip them instead of crashing. This is the correct degradation: a block/item Grim can't map is one it doesn't simulate anyway, so leaving it out of the tag set is harmless.

```java
public void readTagValues(WrapperPlayServerTags.Tag tag) {
    if (!supported) return;

    // Server is sending tag replacement, clear default values.
    values.clear();
    for (int id : tag.getValues()) {
        final T value;
        try {
            value = remapper.apply(id);
        } catch (Exception e) {
            // Modded/unknown ids are not present in PacketEvents' mappings; skip them
            // instead of aborting the whole tag sync. (#2855)
            continue;
        }
        if (value != null) values.add(value);
    }
}
```

File: `common/src/main/java/ac/grim/grimac/utils/data/tags/SyncedTag.java`

Handling both the thrown-exception case *and* a `null` return covers every remapper used by `SyncedTags` (blocks and items), for any current or future modded id.

## Scope / honest limitation

This fixes the **tag-sync NPE spam** (the `SyncedTags` / `PacketServerTags` crash). The issue report also shows a *second, separate* error when opening the modded chest:

```
java.lang.IllegalArgumentException: Can't resolve #1537 (V_26_2) in 'minecraft:item'
    at com.github.retrooper.packetevents.util.mappings.IRegistry.getByIdOrThrow(...)
    at ...WrapperPlayServerWindowItems.read(...)
```

That one is thrown **inside PacketEvents** while decoding the window-items packet (a modded item id absent from PacketEvents' registry) and is an upstream PacketEvents limitation — it cannot be fixed in this file. This fix removes the tag-sync crash; fully supporting modded item ids in inventory packets requires PacketEvents-side mapping support.

## How to reproduce

1. Fabric 26.2 server with GrimAC + a mod adding new blocks/items (e.g. More Chest Variants + Quad).
2. Join the server; the server sends its tag data on login.
3. Before the fix: repeated `NullPointerException` from `SyncedTags.lambda$new$0`.
4. After the fix: no tag-sync NPE; unmapped modded ids are silently skipped.

## Verification

- Common module compiles cleanly; the built jar in this folder contains the patched `SyncedTag`.
