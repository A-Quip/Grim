# Fix: Fabric NPE in replaceNativePlayer during player transfer/respawn (#2508)

**Issue:** [#2508](https://github.com/GrimAnticheat/Grim/issues/2508)
**Affected platform:** Fabric
**Branch:** `fix/2508-fabric-replacenativeplayer-npe`

## Symptom

Under player transfer / dimension-change / reconnect pressure, Grim throws and the failure cascades into `Ticking player` packet errors:

```
Caused by: java.lang.NullPointerException: Cannot invoke
"ac.grim.grimac.platform.api.player.PlatformPlayer.replaceNativePlayer(Object)"
because the return value of
"ac.grim.grimac.platform.api.player.PlatformPlayerCache.getPlayer(java.util.UUID)" is null
    at ac.grim.grimac.platform.fabric.player.FabricPlatformPlayerFactory.replaceNativePlayer(FabricPlatformPlayerFactory.java:120)
    at net.minecraft.class_3222.handler$...$grimac$onRestoreFrom(class_3222.java:...)
```

## Root cause

When a `ServerPlayer` instance is swapped (respawn, dimension change, transfer), Grim's `ServerPlayerMixin` calls `replaceNativePlayer` to point the cached `PlatformPlayer` at the new native handle:

```java
@Override
public void replaceNativePlayer(@NotNull UUID uuid, @NotNull FabricServerPlayerHandle serverPlayerEntity) {
    super.cache.getPlayer(uuid).replaceNativePlayer(serverPlayerEntity);
}
```

`cache.getPlayer(uuid)` returns `null` when there is no cache entry for that UUID yet — which happens during the transfer/respawn window (the player object is being restored before Grim has an active tracked player for it, or after it was already removed). The unconditional dereference then NPEs, and because it happens inside a Mixin on the server tick path, subsequent ticking of that player also fails.

## The fix

Guard the lookup: if there is no cached `PlatformPlayer` for the UUID, there is nothing to re-point, so skip safely instead of dereferencing `null`.

```java
@Override
public void replaceNativePlayer(@NotNull UUID uuid, @NotNull FabricServerPlayerHandle serverPlayerEntity) {
    PlatformPlayer platformPlayer = super.cache.getPlayer(uuid);
    // During transfer/respawn the native ServerPlayer can be swapped before Grim has a tracked
    // player for this UUID (or after it was removed). Nothing to re-point in that case. (#2508)
    if (platformPlayer == null) return;
    platformPlayer.replaceNativePlayer(serverPlayerEntity);
}
```

File: `fabric/shared/src/main/java/ac/grim/grimac/platform/fabric/player/FabricPlatformPlayerFactory.java`

This turns a hard crash + cascading tick failures into a safe no-op for the (legitimate) case where the player is not currently tracked.

## How to reproduce

1. Fabric server (1.21.11) with GrimAC (reporter used a setup with WorldThreader).
2. Have players repeatedly change dimension / portal and reconnect within a short window.
3. Before the fix: intermittent NPE at `FabricPlatformPlayerFactory.replaceNativePlayer`, often the first failure in a chain of `Ticking player` errors.
4. After the fix: no NPE; the swap is skipped when the player isn't tracked.

## Verification

- Fabric module compiles cleanly.
- Built jar in this folder contains the patched `FabricPlatformPlayerFactory`.
