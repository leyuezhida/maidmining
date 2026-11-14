# Maid Mining

A **Touhou Little Maid** extension that adds an autonomous **mining** task: the maid searches for blocks, digs a tunnel to reach them and collects the drops.

What counts as a valid target — and which tools she may use — is no longer hardcoded. Both are config lists you can extend, and changing them does not need a restart.

> Out of the box she **mines ores only and accepts any pickaxe**, so the default experience is the same as 1.0.x.

## Features

- **Configurable targets** — ores only (default), a whitelist of any blocks (gravel, clay, sand, modded stone…), or ores plus your whitelist
- **Configurable tools** — whitelist / blacklist written as registry ids, `#tags` or wildcards such as `mekanism:*` or `minecraft:*_pickaxe`
- **Built-in config screen** — *Mods → Maid Mining → Config*: sliders, toggles, enums, plus an icon-based list editor with a searchable picker over the whole block/item registry. No Cloth Config needed
- **Offhand targeting now accepts blocks** — put clay in her offhand and she only mines clay; raw ores still narrow by material
- **Per-target tool choice** — gravel gets the shovel, stone gets a pickaxe, so the drop is never empty
- **Enchantments work like they do for a player** — Fortune raises yield, Silk Touch keeps the block itself
- **Automatic search** within a 3×3 chunk radius (48 blocks, configurable)
- **Tunnel digging and scaffold climbing** using cheap filler blocks only — never your diamonds or chests
- **Safe by default** — no lava, no player-placed blocks, no containers, no tool durability consumed unless you ask for it
- **Hot-reloaded config** — edit the file or the screen and the next decision already uses the new lists
- **Cross-mod compatibility** — ore and pickaxe tags (Forge / common tags), plus any modded tool that behaves like a pickaxe

## Downloads

- **Minecraft 1.20.1 + Forge 47.4.22+** → `maidmining-1.1.0-forge-1.20.1.jar`
- **Minecraft 1.21.1 + NeoForge 21.1.219+** → `maidmining-1.1.0-neoforge-1.21.1.jar`

Both need Touhou Little Maid **1.5.3+** for the matching loader, on client and server:

- 1.20.1: `touhoulittlemaid 1.5.3-forge+mc1.20.1`
- 1.21.1: `touhoulittlemaid 1.5.3-neoforge+mc1.21.1`

## How to use

1. Give your maid a tool she accepts (any pickaxe by default)
2. *(Optional)* Put a raw ore **or a block** in her offhand to target one type
3. Open the maid's task menu and select **Mining**
4. She searches for targets and digs tunnels to reach them

## Configuration

The file is `config/maidmining-common.toml` (41 options in nine groups), or edit it in game via *Mods → Maid Mining → Config*.

Example — keep mining ores, add gravel and clay, ignore copper, and allow a shovel too:

```toml
[tools]
    whitelist = ["#minecraft:pickaxes", "minecraft:iron_shovel"]
    blacklist = ["minecraft:wooden_pickaxe"]
    requireCorrectTool = true

[targets]
    mode = "ORES_AND_WHITELIST"   # ORES / WHITELIST / ORES_AND_WHITELIST
    whitelist = ["minecraft:gravel", "minecraft:clay"]
    blacklist = ["minecraft:copper_ore"]
    offhandTargeting = true
```

Lists accept registry ids (`minecraft:gravel`), tags (`#minecraft:ores`) and wildcards (`some_mod:*`). `tools.whitelist` ships with the pickaxe tags, so anything you add is an **addition**; remove every pickaxe tag if you want strictly the listed items.

Two other switches worth knowing: `compat.fakePlayerMode` routes block breaking through a fake player so land-claim plugins can intercept it, and `dig.damageTool` makes mining consume durability like a player would.

## What's new in 1.1.0

- Configurable tool list (whitelist / blacklist / correct-tool rule)
- Configurable target list with three modes
- In-game config screen with a visual, searchable list editor
- Offhand targeting accepts block items
- Config moved to `config/maidmining-common.toml` — one file for all worlds
- Fixed: underground scanning on 1.18+ worlds found nothing below y=0
- Fixed: targets directly above the maid were treated as unreachable
- Fixed: `safety.minHealthRetreat` and `travel.followOwnerWhenIdle` did nothing — both are honoured now

## Notes

- Block breaking is instantaneous, so Efficiency and Mending have no effect by design. Both are still scored, and `dig.damageTool` turns on player-like wear if you want it.
- The maid digs real tunnels — use her in a resource area or a dedicated save.
- **Coming from 1.0.x:** the old `world/serverconfig/maidmining-server.toml` is no longer read. Options moved to `config/maidmining-common.toml`, created with defaults on first launch.
- Protection mods that hook the entity block-break event can stop her. For stricter protection (most land-claim plugins) enable `compat.fakePlayerMode`.

## Links

- Source code: [github.com/leyuezhida/maidmining](https://github.com/leyuezhida/maidmining)
- 1.21.1 NeoForge branch: [1.21.1-neoforge](https://github.com/leyuezhida/maidmining/tree/1.21.1-neoforge)
- Issues: [github.com/leyuezhida/maidmining/issues](https://github.com/leyuezhida/maidmining/issues)

---

Licensed under the **MIT License**.
