# Maid Mining

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-green.svg)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-47.4.22-red.svg)](https://files.minecraftforge.net/)

**Maid Mining** is a [Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid) extension mod that gives maids the ability to autonomously mine ores.

- 3×3 chunk search radius, with configurable reach
- Automatic tunnel digging to reach ores
- Offhand ore filtering (put raw ore in offhand to target specific types)
- **Enchanted pickaxes work as expected** — Fortune increases yield, Silk Touch keeps the block itself
- Cross-mod ore & pickaxe compatibility (Forge/Fabric ore tags)

## Requirements

| Dependency | Version |
|------------|---------|
| Minecraft | 1.20.1 |
| Forge | 47.4.22+ |
| Touhou Little Maid | 1.5.3+ |

## Usage

1. Give your maid an **iron pickaxe or better** (enchanted ones are fine — see below)
2. (Optional) Put a **raw ore** (iron ore, raw iron, etc.) in her **offhand** to target specific ores
3. Switch her task mode to **Mining**
4. She will search for ores and dig tunnels to reach them

### Enchantments

Enchantments on the pickaxe behave the way they would for a player:

| Enchantment | Effect |
|-------------|--------|
| **Fortune** | Increases ore yield (Fortune III can yield up to 4×) |
| **Silk Touch** | Mining an ore yields the ore **block** instead of raw material — renewable, and usable as building material |
| **Efficiency** | No practical effect yet: breaking is still instantaneous. A mining-time model is planned |
| **Mending** | Moot while durability is not consumed (see below) |

The maid **picks the best pickaxe available**, scoring by mining enchantments, then tier, then remaining durability — so a diamond pickaxe will be preferred over a wooden one, and a Silk Touch pickaxe over a plain one of the same tier.

Mining does **not** wear out tools by default, since a maid is expected to keep mining indefinitely. If you want player-like wear, set `dig.damageTool = true`.

## Configuration

Server-side configuration lives in `world/serverconfig/maidmining-server.toml` (35 options in seven groups: search, travel, dig, items, safety, diag, compat). Defaults are deliberately conservative: no lava, no player-placed blocks, cheap scaffolding only, and the bedrock-adjacent-ore rule kept on.

Two options worth knowing about:

| Option | Meaning |
|--------|---------|
| `compat.fakePlayerMode` | `MAID` (default) keeps TLM's normal destruction path. `FAKE_PLAYER` switches to the player code path via a fake player, so most **land-claim / protection mods can intercept** block breaking. Turn this on if your claim plugin doesn't stop her. |
| `search.skipNearBedrock` | Keeps her from locking onto ores she would get stuck on next to bedrock. Kept on by default after field testing showed a churn loop when disabled. |

## Building

```bash
./gradlew jar
```

Output: `build/libs/maidmining-1.0.2.jar`

Run the automated tests headlessly with:

```bash
./gradlew runGameTestServer
```

## License

This project is licensed under the **MIT License** - see [LICENSE](LICENSE) for details.

## Credits

- **leyue** - Author
- **Touhou Little Maid** - The amazing maid mod this extends
- **Minecraft Forge** - Modding platform
