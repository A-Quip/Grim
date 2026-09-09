# Upstream: Server crash running GrimAC with Geyser/Floodgate (#2856)

**Issue:** [#2856](https://github.com/GrimAnticheat/Grim/issues/2856)
**Verdict:** ❌ Not a Grim bug — upstream in **PacketEvents** / **Kyori Adventure** classpath conflict. Same root cause as #2816.
**Affected:** Fabric 26.2, GrimAC 2.3.74-961fa54 with Geyser + Floodgate (also LuckPerms, AntiXRay, Lithium, …).

## What happens

Crash log ([mclo.gs/FUzgccx](https://mclo.gs/FUzgccx)) shows the server dying during PacketEvents' `preLaunch`:

```
java.lang.IncompatibleClassChangeError:
  Found interface net.kyori.adventure.nbt.BinaryTagType, but class was expected
  at com.github.retrooper.packetevents.util.adventure.AdventureNbtUtil.buildNbtTagTypes(AdventureNbtUtil.java:73)
```

## Why it is upstream

- Byte-for-byte the same failure as #2816: the throwing frame is **PacketEvents'** `AdventureNbtUtil.buildNbtTagTypes`, and the error is a Kyori Adventure **binary incompatibility** (`BinaryTagType` interface-vs-class mismatch).
- Here the conflicting Adventure comes from **Geyser/Floodgate**, which bundle their own Adventure. PacketEvents ends up linking against the wrong `BinaryTagType`. This is a packaging/shading conflict in PacketEvents + the other mods' Adventure — outside Grim's code.
- The reported repro ("use grimac, geyser and floodgate; start the server") is again the duplicated-library signature.

## What Grim can/can't do

- Not fixable in Grim source — identical to #2816.
- Resolved by the **PacketEvents dependency bump** that fixes Adventure `BinaryTagType` linkage/relocation; GrimAC ships that build.

## Recommended action

- Treat as a duplicate of #2816 and close both as upstream (PacketEvents) once the dependency is bumped.
- If you want Bedrock support to keep working meanwhile, note that this crash is independent of Grim's own Geyser handling — it aborts before Grim loads.

## Related

#2816 (same `BinaryTagType` crash), #2799, #2852 (same Adventure classpath family, provider-lookup variant).
