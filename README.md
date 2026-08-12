# Maid Mining

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-green.svg)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-47.4.22-red.svg)](https://files.minecraftforge.net/)

**Maid Mining** is a [Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid) extension mod that gives maids the ability to autonomously mine ores.

- 3×3 chunk search radius
- Automatic tunnel digging to reach ores
- Scaffold climbing for vertical navigation
- Offhand ore filtering (put raw ore in offhand to target specific types)
- Cross-mod ore & pickaxe compatibility (Forge/Fabric ore tags)

## Requirements

| Dependency | Version |
|------------|---------|
| Minecraft | 1.20.1 |
| Forge | 47.4.22+ |
| Touhou Little Maid | 1.5.3+ |

## Usage

1. Give your maid an **iron pickaxe or better**
2. (Optional) Put a **raw ore** (iron ore, raw iron, etc.) in her **offhand** to target specific ores
3. Switch her task mode to **Mining**
4. She will search for ores and dig tunnels to reach them

## Building

```bash
./gradlew jar
```

Output: `build/libs/maidmining-1.0.0.jar`

## License

This project is licensed under the **MIT License** - see [LICENSE](LICENSE) for details.

## Credits

- **leyue** - Author
- **Touhou Little Maid** - The amazing maid mod this extends
- **Minecraft Forge** - Modding platform
