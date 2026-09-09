# Upstream issues (not fixable in Grim's source)

These open Grim 2.0 issues are **not Grim bugs**. They all crash inside **PacketEvents'**
`preLaunch` while initializing its bundled **Kyori Adventure** classes, before any GrimAC
code runs, due to an Adventure version/classpath conflict with other mods. The fix is a
**PacketEvents dependency bump** (relocate/pin Adventure), not a change to Grim.

| Issue | Crash | Trigger |
|---|---|---|
| [#2799](2799/README.md) | `ServiceConfigurationError: GsonDataComponentValueConverterProvider not found` | GrimAC alone on Fabric 1.21.11 |
| [#2816](2816/README.md) | `IncompatibleClassChangeError: adventure.nbt.BinaryTagType` | GrimAC + Adventure-bundling mods (Geyser/Floodgate/MiniMOTD) |
| [#2852](2852/README.md) | `DataComponentValueConverterRegistry$Provider` load failure | GrimAC + Spark |
| [#2856](2856/README.md) | `IncompatibleClassChangeError: adventure.nbt.BinaryTagType` (dup of #2816) | GrimAC + Geyser + Floodgate |

**Common signature:** the top of every stack trace is `com.github.retrooper.packetevents.*`
(`AdventureNbtUtil` / `AdventureConversionInjector`) or `net.kyori.adventure.*`, and the crash
happens at Fabric's `preLaunch` entrypoint provided by `packetevents-fabric-*` — never in
`ac.grim.grimac.*`. "Grim alone is fine, mod X alone is fine, both together crash" confirms a
duplicated-library classpath conflict rather than a defect in Grim's logic.

**Recommended action:** adopt the PacketEvents release that resolves Adventure relocation/
`ServiceLoader` handling, then close these as upstream (link the PacketEvents issue/PR). Recent
GrimAC 2.0 commits about sharing Adventure classes with external PacketEvents target this same
class of problem at the build/dependency layer.
