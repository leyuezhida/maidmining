# 更新日志 / Changelog

版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)；格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

---

## [1.0.1] - 2026-09-20

本次更新围绕**「女仆在地下挖矿会卡住」**做了一次系统性修复，并补上矿石定向与模组矿石支持。

### 修复

**卡住相关（本次重点）**

- **不再被基岩卡住** —— 破坏结果现在会分类（永不可破 / 流体 / 被保护 / 暂时失败）。旧版把四种情况混成一个 `false`，于是对着基岩空转约 6 秒，再叠加错误的恢复动作（向上传送并清零卡住计数），最终形成「贴着基岩无限上爬」的死循环。现在遇到不可破坏的方块会**立即放弃该目标**并换下一个。
- **矿道不再过高** —— 隧道净高改为按女仆碰撞箱推导（**1×2**，与玩家身高一致）。旧版固定挖 3 格高，比需要多挖约 1/3。上台阶处会在头顶局部多挖 1 格作为起跳空间——因为 2 格高的巷道里跳跃会被天花板截断，1 格台阶在物理上迈不上去。
- **修复上坡「原地蹦跶不向前」** —— 上行不再依赖原版寻路（它在 1 格宽隧道里给不出路径，走位目标会被 `MoveToTargetSink` 直接抹掉），改为**逐 tick 直接驱动移动**；跳跃优先，传送仅作兜底，且兜底前会校验落点、兜底后清除累计落差。
- **修复上坡时「空中放方块把自己挡住」** —— 垫脚方块不再于空中放置，也不会放进女仆自身所在的两格身体柱；只有确实需要抬升（高度差 ≥ 2）时才搭台阶，1 格高差直接用「伸手挖」。
- **修复「突然停住」** —— 新增发呆检测：位置连续 20 tick 不变即接管为直接驱动，解决「代码以为在正常掘进、女仆却一步没动」。
- **修复物品复制** —— 收集溢出掉落物时，「只装下一部分」的情况会正确回写剩余数量。旧版不回写，导致背包放不下时物品**同时存在于背包与地面**。
- **修复镐子破损无音效/动画** —— 耐久耗尽时广播破坏事件。
- **修复副手定向过滤失效** —— 见下方「矿石定向」。

**专用服务器安全**

- 任务定义不再引用任何客户端类（旧版在 `MiningTask` 里调用 `net.minecraft.client.*` 来取翻译文本，这在专用服务器上会 `NoClassDefFoundError`，而旧代码只 `catch (Exception)` 捕不到 `Error`）。现在改用 TLM 的默认实现：任务名与描述由 UID 自动生成 i18n key，交给 GUI 翻译。

### 新增

- **模组矿石自动支持** —— 矿石识别改为标签驱动：`#minecraft:ores` / `#forge:ores` / `#c:ores`，以及**任意 `<命名空间>:ores/<材料>` 子标签**。因此镍、铝、铅、锡等遵循 Forge 约定的模组矿石**自动可被搜寻，也自动支持副手定向**，无需为任何模组单独适配。
- **远古残骸支持** —— 按 Forge 的 `forge:ores/netherite_scrap` 约定，副手放 `netherite_scrap` 或远古残骸方块都能定向。
- **副手定向过滤重做** —— 解析顺序：命名模式（`raw_iron` / `raw_iron_block` / `iron_ore` / `deepslate_iron_ore` / `iron_ingot` / `iron_dust` …）→ **物品标签**（`forge:raw_materials/nickel`、`forge:ingots/aluminum` …，覆盖命名不按套路的模组材料）→ 别名表（`lapis_lazuli→lapis`、`quartz→nether_quartz` …）→ 物品名本身；每个候选都与「世界里真实存在的矿石材料」核对。**识别不了时会给出 WARN 提示**，不再静默变成「挖全部」。旧版只认 `raw_*` 和 `*_ore`，放钻石/煤/红石等会静默失效。
- **镐子识别放宽** —— `#minecraft:pickaxes` → `forge/c:tools/pickaxes` → `ToolActions.PICKAXE_DIG`（模组镐主力）→ **「能正确开采石头」兜底**。带镐标签或行为上就是镐的工具都能用。
- **卡住快照日志** —— 无进展时每 5 秒输出一行 `Stuck state=… target=… feet=… dy=… dist=…`，便于定位问题。
- **无进展看门狗** —— 与目标距离连续 20 秒没有缩短即放弃该目标。
- **自动化测试** —— 建立 GameTest 基础设施，落地 13 个用例（破坏分类语义、副手解析 11 种物品、镐子识别 12 项、矿石材料覆盖等），`./gradlew runGameTestServer` 即可跑，无需客户端。
- **全新封面与图标** —— 封面主角为车万女仆的「酒狐」；另附 CurseForge / Modrinth 用的大图与头像（`publish/`）。

### 调整

- **「基岩邻近的矿跳过」重新默认开启** —— 该规则曾一度移除（改为遇到基岩立即放弃）。实测发现深层（y ≤ -60）会出现「连续锁定 → 撞同一面基岩墙 → 放弃」的碎裂循环，女仆表现为站着反复搜索，故按实测反馈重新启用。
- **构建** —— 开启编译期 `-Xlint:deprecation -Xlint:unchecked`，清理 5 处已弃用 API，构建输出零警告。

### 已知问题

- 使用背包里的方块垫脚时，目前会取「第一个可站立的方块」，**可能消耗贵重方块**（计划 1.1.0 加入建材白名单/估值）。
- 背包的「展示位」（第 5 格）尚未从垫脚/存储范围中排除（计划 1.1.0）。
- 目标黑名单只在内存中，**不随存档持久化**（计划 1.1.0）。
- 遇到不可破坏的方块只会**放弃目标**，尚无「绕墙寻路」（计划 1.2.0）。
- 破坏仍是瞬时完成，未模拟挖掘时间；时运/精准采集暂不生效（计划 1.2.0）。

---

## English

### [1.0.1] - 2026-09-20

A systematic fix for **"the maid gets stuck while mining underground"**, plus ore-targeting and modded-ore support.

**Fixed**

- **No longer stuck on bedrock** — block breaking now returns a *classified* result (unbreakable / fluid / protected / temporary). The old code collapsed all four into a single `false`, so the maid ground against bedrock for ~6 seconds and then teleported upward while resetting its stuck counter, producing an endless "climbing along bedrock" loop. Unbreakable blocks now make her abandon that target immediately.
- **Tunnel is no longer 3 blocks tall** — clearance is derived from the maid's hitbox (**1×2**, matching player height) instead of a hardcoded 3, saving ~1/3 of the digging. A single extra block is carved locally at step-ups, because a jump inside a 2-high corridor hits the ceiling and cannot clear a full block.
- **Fixed "hopping in place instead of moving forward"** — step-ups no longer rely on vanilla pathfinding (which fails in a 1-wide tunnel; `MoveToTargetSink` then erases the walk target). Movement is now driven directly every tick, jumping first, with a collision-validated teleport only as a last resort.
- **Fixed "placing a block mid-air and blocking herself"** — scaffolds are never placed while airborne, nor inside the maid's own body column.
- **Fixed "suddenly standing still"** — added a stall detector: if the position does not change for 20 ticks, movement is taken over by the direct driver.
- **Fixed an item duplication bug** — when only part of a ground drop fits in the maid's inventory, the remainder is now written back to the entity. Previously it was not, so items existed both in the inventory and on the ground.
- **Fixed missing pickaxe break sound/animation.**
- **Dedicated-server safety** — the task class no longer references any `net.minecraft.client.*` type (the old code did, to look up translations, which could throw `NoClassDefFoundError` on a dedicated server).

**Added**

- **Modded ore support via tags** — ores are recognised through `#minecraft:ores`, `#forge:ores`, `#c:ores` and **any `<namespace>:ores/<material>` sub-tag**, so ores from other mods (nickel, aluminium, lead, tin, …) are found and targetable automatically, with no per-mod code.
- **Ancient debris support** — `netherite_scrap` (or the ancient debris block) in the offhand targets ancient debris, following Forge's `forge:ores/netherite_scrap` convention.
- **Offhand targeting rewritten** — resolution by naming pattern → **item tags** (`forge:raw_materials/…`, `forge:ingots/…`) → alias table → raw item name, each validated against the ore materials that actually exist. Unrecognised items now log a WARN instead of silently mining everything.
- **Broader pickaxe detection** — vanilla tag → `forge`/`c` tool tags → `ToolActions.PICKAXE_DIG` → "can correctly mine stone" fallback.
- **Stall snapshot logging**, a **no-progress watchdog**, and **13 GameTests** runnable headlessly via `./gradlew runGameTestServer`.
- **New cover art and mod icon** (plus large images for CurseForge / Modrinth under `publish/`).

**Changed**

- Re-enabled the "skip ores next to bedrock" rule by default after field testing showed a lock/abandon churn loop near the bedrock layer.
- Build now enables `-Xlint:deprecation -Xlint:unchecked`; five deprecated API usages were cleaned up.

**Known issues**

- Scaffolding may consume valuable blocks from the inventory (configurable whitelist planned).
- The maid's "display slot" (slot 5) is not yet excluded from scaffolding/storage use.
- Target blacklists are not persisted to the save file.
- Blocked paths are abandoned rather than routed around (path planning planned).
- Breaking is still instantaneous; Fortune/Silk Touch do not apply yet.

---

## [1.0.0] - 2026-08-12

首个发布版本：为车万女仆新增「挖矿」行为模式——自动搜索周围 3×3 区块内的矿石，挖隧道前往并采集；支持副手放置原矿定向挖掘。

Initial release: adds a **Mining** task for Touhou Little Maid — searches for ores within a 3×3 chunk radius, digs tunnels to reach them, and supports offhand ore filtering.
