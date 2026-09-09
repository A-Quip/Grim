# Upstream: Fabric startup crash — GsonDataComponentValueConverterProvider not found (#2799)

**Issue:** [#2799](https://github.com/GrimAnticheat/Grim/issues/2799)
**Verdict:** ❌ Not a Grim bug — upstream in **PacketEvents** (`com.github.retrooper:packetevents`) / its bundled **Kyori Adventure** serializers.
**Affected:** Fabric 1.21.11 dedicated servers, GrimAC ≥ commit `73c60d0` (2.3.74-1e5bbc3, -d54b49b, …).

## What happens

The server crashes during Fabric's `preLaunch` entrypoint, before Grim's own code runs:

```
Could not execute entrypoint stage 'preLaunch' ... provided by 'packetevents-fabric-intermediary'
  at io.github.retrooper.packetevents.PacketEventsMod
Caused by: java.lang.ExceptionInInitializerError
  at net.kyori.adventure.text.event.DataComponentValueConverterRegistry$ConversionCache.collectConversions(...)
  at com.github.retrooper.packetevents.util.adventure.AdventureConversionInjector.inject(AdventureConversionInjector.java:60)
  at com.github.retrooper.packetevents.PacketEventsAPI.load(PacketEventsAPI.java:84)
  at io.github.retrooper.packetevents.PacketEventsMod.onPreLaunch(PacketEventsMod.java:91)
Caused by: java.util.ServiceConfigurationError:
  net.kyori.adventure.text.event.DataComponentValueConverterRegistry$Provider:
  Provider net.kyori.adventure.text.serializer.gson.impl.GsonDataComponentValueConverterProvider not found
```

## Why it is upstream

- The failing frames are entirely inside **PacketEvents** (`PacketEventsMod.onPreLaunch` → `PacketEventsAPI.load` → `AdventureConversionInjector.inject`) and **Kyori Adventure** (`DataComponentValueConverterRegistry`). No `ac.grim.grimac.*` frame appears until after this point.
- The crash is a `ServiceConfigurationError`: Adventure's `DataComponentValueConverterRegistry` looks up service providers via `ServiceLoader`, and the `adventure-text-serializer-gson` provider (`GsonDataComponentValueConverterProvider`) is missing/unresolved on the classpath that PacketEvents assembles. This is a **shading/packaging problem in the PacketEvents Fabric artifact**, not in Grim.
- It reproduces with only Fabric API + GrimAC installed and triggers in `preLaunch`, i.e. before any Grim initialization Grim could guard.

## What Grim can/can't do

- Grim cannot fix this in its own source: the classes, the `ServiceLoader` lookup, and the `preLaunch` entrypoint all belong to PacketEvents / Adventure.
- The real fix is a **PacketEvents dependency bump** that ships/relocates the Adventure gson serializer provider consistently (or stops requiring it during `inject`). Grim's remediation is therefore to update the bundled PacketEvents version.
- Note: recent GrimAC 2.0 work around sharing Adventure classes with external PacketEvents (e.g. the "Share Adventure classes with external PacketEvents" / "Use native Paper audiences" commits) targets this same class of problem via the dependency/build layer.

## Recommended action

- Track/adopt the PacketEvents release that resolves the Adventure gson `ServiceLoader` provider.
- Close as upstream (PacketEvents) once the dependency is bumped; link to the PacketEvents issue/PR.

## Related

Same PacketEvents + Adventure classpath family as #2852 (identical `DataComponentValueConverterRegistry$Provider` failure) and #2816 / #2856 (`BinaryTagType IncompatibleClassChangeError`).
