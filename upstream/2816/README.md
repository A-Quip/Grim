# Upstream: Fabric crash — IncompatibleClassChangeError for adventure.nbt.BinaryTagType (#2816)

**Issue:** [#2816](https://github.com/GrimAnticheat/Grim/issues/2816)
**Verdict:** ❌ Not a Grim bug — upstream in **PacketEvents** / **Kyori Adventure** classpath conflict.
**Affected:** Fabric 26.2 (Loader 0.19.3), GrimAC 2.3.74-5d4e4d3 (PacketEvents 2.13.1), alongside mods that ship Adventure (Geyser-Fabric, Floodgate, MiniMOTD, …).

## What happens

Server crashes in PacketEvents' `preLaunch` phase:

```
java.lang.IncompatibleClassChangeError:
  Found interface net.kyori.adventure.nbt.BinaryTagType, but class was expected
  at com.github.retrooper.packetevents.util.adventure.AdventureNbtUtil.buildNbtTagTypes(AdventureNbtUtil.java:73)
```

## Why it is upstream

- The throwing frame is **PacketEvents'** `AdventureNbtUtil.buildNbtTagTypes`. No Grim frame is involved; it happens before Grim initializes.
- `IncompatibleClassChangeError: Found interface … BinaryTagType, but class was expected` means PacketEvents was **compiled against one version** of Kyori Adventure (where `BinaryTagType` was a class) but **runs against a different version** (where it is an interface), because another mod on the classpath (Geyser/Floodgate/MiniMOTD) supplies a conflicting Adventure. This is a **binary-incompatibility / shading conflict** owned by PacketEvents' packaging, not Grim.
- Reporter confirms it appears specifically when Adventure-bundling mods are added next to Grim — the hallmark of a duplicated-library conflict.

## What Grim can/can't do

- Not fixable in Grim source: `BinaryTagType`, `AdventureNbtUtil`, and the `preLaunch` entrypoint are all PacketEvents/Adventure.
- The fix is a **PacketEvents dependency bump** that pins/relocates Adventure so its `BinaryTagType` matches what PacketEvents was compiled against (or defers/guards `buildNbtTagTypes`). GrimAC's remediation is to ship that PacketEvents build. Recent 2.0 commits sharing Adventure with external PacketEvents target this exact conflict class.

## Recommended action

- Adopt the PacketEvents release that resolves the Adventure `BinaryTagType` binary incompatibility.
- Close as upstream (PacketEvents); link the PacketEvents issue/PR. #2856 is the same crash reported via Geyser/Floodgate.

## Related

#2856 (identical `BinaryTagType` crash), #2799 and #2852 (the `DataComponentValueConverterRegistry$Provider` variant of the same Adventure classpath family).
