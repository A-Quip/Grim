# Fix: NullPointerException on Floodgate/Geyser bedrock login (#2645)

**Issue:** [#2645](https://github.com/GrimAnticheat/Grim/issues/2645)
**Affected platform:** Fabric (Floodgate + Geyser; a bedrock player joining)
**Branch:** `fix/2645-config-null-login-guard`

## Symptom

When a Bedrock player connects through Floodgate/Geyser, Grim throws out of the PacketEvents listener:

```
PacketEvents caught an unhandled exception while calling your listener.
java.lang.NullPointerException: Cannot invoke
"ac.grim.grimac.api.config.ConfigManager.getBooleanElse(String, boolean)"
because the return value of
"ac.grim.grimac.manager.config.BaseConfigManager.getConfig()" is null
    at ac.grim.grimac.events.packets.PacketPlayerJoinQuit.onUserLogin(PacketPlayerJoinQuit.java:54)
    at com.github.retrooper.packetevents.event.UserLoginEvent.call(...)
    ...
```

## Root cause

`BaseConfigManager` holds the loaded config in a field that starts as `null` and is only set when `load(...)` runs during Grim's init:

```java
@Getter private ConfigManager config = null;   // null until load() is called
```

`PacketPlayerJoinQuit.onUserLogin` dereferenced it unconditionally:

```java
if (GrimAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse("debug-pipeline-on-join", false)) { ... }
// ...
if (platformPlayer.hasPermission("grim.spectate")
        && GrimAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse("spectators.hide-regardless", false)) { ... }
```

For Floodgate/Geyser bedrock logins on Fabric, `onUserLogin` is reached while `getConfig()` is still `null` (the login is processed before Grim's config has finished loading), so `getConfig().getBooleanElse(...)` NPEs and the exception escapes the listener.

## The fix

Read the config **once** and null-guard every use, so a not-yet-loaded config degrades to defaults instead of throwing:

```java
final ConfigManager config = GrimAPI.INSTANCE.getConfigManager().getConfig();

if (config != null && config.getBooleanElse("debug-pipeline-on-join", false)) {
    LogUtil.info("Pipeline: " + ChannelHelper.pipelineHandlerNamesAsString(event.getUser().getChannel()));
}
// ...
if (platformPlayer.hasPermission("grim.spectate")
        && config != null && config.getBooleanElse("spectators.hide-regardless", false)) {
    ...
}
```

File: `common/src/main/java/ac/grim/grimac/events/packets/PacketPlayerJoinQuit.java`

The rest of `onUserLogin` (adding the user, toggle prefetch/apply, join hooks) does not depend on `getConfig()`, so it continues to run normally; only the two config-gated behaviours fall back to their defaults when the config isn't loaded yet.

## Scope / honest note

This is a **defensive guard** that eliminates the reported NPE and makes the login path robust. It does *not* attempt to answer the deeper question of **why** the config is still `null` for these specific Floodgate/Geyser logins (a possible init-ordering interaction on Fabric). Regardless of that root cause, the login handler should never NPE on an unset config — that is what this change guarantees. If maintainers want to additionally ensure config is always loaded before logins are processed, that would be a separate, larger change.

## How to reproduce

1. Fabric 1.21.11 server with GrimAC + Floodgate + Geyser.
2. Join with a Bedrock client through Geyser.
3. Before the fix: `NullPointerException` at `PacketPlayerJoinQuit.onUserLogin` (`getConfig()` is null).
4. After the fix: no NPE; the bedrock login is handled and config-gated options use defaults if the config isn't loaded yet.

## Verification

- Fabric module compiles cleanly; the built jar in this folder contains the patched `PacketPlayerJoinQuit`.
