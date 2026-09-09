# Fix: Update checker crashes Fabric server on startup (#2835)

**Issue:** [#2835](https://github.com/GrimAnticheat/Grim/issues/2835)
**Affected platform:** Fabric (setups with a conflicting Adventure/text pipeline, e.g. `adventure-platform-fabric`)
**Branch:** `fix/2835-updatechecker-crash-guard`

## Symptom

GrimAC takes the **entire server down** during startup:

```
java.lang.NoClassDefFoundError: net/kyori/adventure/util/Buildable$Builder
    at ac.grim.grimac.platform.fabric.mc261.Fabric261ConversionUtil.toNativeText(Fabric261ConversionUtil.java:50)
    at ac.grim.grimac.platform.fabric.sender.FabricOfficialSenderFactory.sendMessage(FabricOfficialSenderFactory.java:57)
    at ac.grim.grimac.command.commands.GrimVersion.checkForUpdatesAsync(GrimVersion.java:41)
    at ac.grim.grimac.manager.init.start.UpdateChecker.start(UpdateChecker.java:10)
    at ac.grim.grimac.manager.InitManager.start(InitManager.java:85)
    at ac.grim.grimac.GrimAPI.start(GrimAPI.java:103)
    ...
    Caused by: java.lang.ClassNotFoundException: net.kyori.adventure.util.Buildable$Builder
```

## Root cause

Two layers:

1. **Trigger (upstream/environment):** On some Fabric setups another mod ships a different version of Kyori Adventure, so when Grim renders a `Component` to native text (`Fabric261ConversionUtil.toNativeText`) a required Adventure class is missing → `NoClassDefFoundError`. This classpath conflict is an Adventure-shading problem being addressed at the dependency/build level and cannot be fully resolved in this file.

2. **Amplifier (Grim-side, fixable here):** The update checker runs synchronously during `InitManager.start` and sends its "Grim Version: …" message *before* the async HTTP work. `GrimVersion.checkForUpdatesAsync` line 41 calls `sender.sendMessage(...)` on the startup thread. Because `NoClassDefFoundError` is a `Throwable`/`Error` (not an `Exception`), nothing catches it, so a **cosmetic version message failing takes down the whole server**.

A non-essential, best-effort feature like the update/version notice must never abort server startup.

## The fix

Wrap the update-check invocation so any failure (including `Error`s such as `NoClassDefFoundError`) is logged and swallowed instead of propagating out of `InitManager.start`:

```java
@Override
public void start() {
    if (GrimAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse("check-for-updates", true)) {
        try {
            GrimVersion.checkForUpdatesAsync(GrimAPI.INSTANCE.getPlatformServer().getConsoleSender());
        } catch (Throwable t) {
            // The update checker is a best-effort, non-essential feature and must never abort
            // server startup. On some Fabric setups a conflicting Adventure/text pipeline throws
            // NoClassDefFoundError while rendering the console message; log and continue. (#2835)
            LogUtil.error("Skipping GrimAC update check due to an error while sending the version message.", t);
        }
    }
}
```

File: `common/src/main/java/ac/grim/grimac/manager/init/start/UpdateChecker.java`

`Throwable` is used deliberately: the failure is a `NoClassDefFoundError`, which a plain `catch (Exception)` would miss.

## Scope / honest limitation

This makes Grim **resilient** — the server boots and Grim runs even when the text pipeline is broken; only the version message is skipped (a warning is logged). It does **not** fix the underlying Adventure classpath conflict itself (that is upstream/dependency work; recent 2.0 commits around sharing Adventure classes with external PacketEvents target that root cause). The goal of this change is specifically: *a broken update notice should never crash the server.*

## How to reproduce

1. Fabric 26.2 server with GrimAC and a mod that loads a conflicting Adventure version (e.g. `adventure-platform-fabric-7.1.1`).
2. Start the server with `check-for-updates: true` (default).
3. Before the fix: server crashes during init with `NoClassDefFoundError: net/kyori/adventure/util/Buildable$Builder` in the update-checker path.
4. After the fix: server starts normally; a single warning is logged that the update check was skipped.

## Verification

- Common module compiles cleanly; the built jar in this folder contains the patched `UpdateChecker`.
