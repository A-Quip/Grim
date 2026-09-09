# Upstream: Fabric startup crash with Spark — DataComponentValueConverterRegistry$Provider (#2852)

**Issue:** [#2852](https://github.com/GrimAnticheat/Grim/issues/2852)
**Verdict:** ❌ Not a Grim bug — upstream in **PacketEvents** / bundled **Kyori Adventure** serializers. Same root cause as #2799.
**Affected:** Fabric 26.2, GrimAC 2.3.74 + **Spark** 1.10.x (fabric-api present). Grim alone or Spark alone start fine; the two together crash.

## What happens

```
[main/INFO]: Loading packetevents...
[main/ERROR]: A mod crashed on startup!
net.fabricmc.loader.impl.FormattedException: ... Could not execute entrypoint stage 'preLaunch'
  ... provided by 'packetevents-fabric-official'
Caused by: Encountered an exception loading a provider for interface
  net.kyori.adventure.text.event.DataComponentValueConverterRegistry$Provider
```

## Why it is upstream

- The crash is in **PacketEvents'** `preLaunch` entrypoint while loading an Adventure `ServiceLoader` provider — identical failure mode to #2799. No Grim frame is involved.
- Spark bundles/loads its own copy of Kyori Adventure. With Spark also on the classpath, the Adventure `DataComponentValueConverterRegistry$Provider` `ServiceLoader` lookup that PacketEvents performs resolves inconsistently (duplicate/mismatched Adventure classes), throwing during provider loading. This is a **classpath/shading conflict between PacketEvents' Adventure and Spark's Adventure**, which Grim does not control.
- "Grim alone doesn't crash, Spark alone doesn't crash, both together crash" is the signature of a duplicated-library classpath conflict, not a defect in either mod's own logic.

## What Grim can/can't do

- Not fixable in Grim source — the conflicting classes belong to PacketEvents and Spark's shaded Adventure.
- Resolved by a **PacketEvents dependency bump** that relocates/handles Adventure so it no longer clashes with another mod's Adventure (the same remediation as #2799 / #2816).

## Recommended action

- Adopt the PacketEvents version that fixes Adventure `ServiceLoader`/relocation handling.
- Close as upstream (PacketEvents), noting the Spark-Adventure interaction; link the PacketEvents issue/PR.

## Related

#2799 (identical provider error), #2816 and #2856 (`BinaryTagType` variant of the same Adventure classpath conflict).
