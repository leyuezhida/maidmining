# 更新日志 / Changelog

版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)；格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

> 本文件记录**玩法层面**的变更，两条版本线（`main` = 1.20.1 Forge，`1.21.1-neoforge` = 1.21.1 NeoForge）内容一致；平台移植细节见 [PORTING.md](PORTING.md)。

---

## [1.1.0] - 2026-11-13

本次更新的主题是**把「什么算工具、什么算目标」从代码里搬到配置里**。1.0.x 只有镐子能挖、只有矿石能找，两件事都写死在 `MiningValidator` 里；现在两张名单都能自由增减，而默认行为与 1.0.2 完全一致。

> **范围说明**：没有新增伐木或任何新任务类型。任务仍然是原来的「挖矿」，行为、状态机、隧道逻辑都没动；改的只是「她认哪些工具、愿意挖哪些方块」这两份判断名单。

### 新增

**可配置的工具（`tools`）**

- **`tools.whitelist`（工具白名单）** —— 允许女仆使用的工具。支持注册名（`minecraft:iron_shovel`）、`#标签`（`#minecraft:pickaxes`）、`modid:*` 通配（`mekanism:*`）。留空则沿用「任何镐子」的内置规则。
- **`tools.blacklist`（工具黑名单）** —— 永不使用，**优先级高于白名单**，用来挡掉木镐之类的垃圾工具。
- **`tools.requireCorrectTool`（工具—方块匹配）** —— 是否保留原版规则：铲子挖不动石头、石镐挖不动钻石矿。默认开启。
- **按目标挑工具** —— 换目标时会重新比较，挖沙砾用铲、挖石头用镐。没有这一条，白名单里同时有铲子和钻石镐时，她会一直拿评分更高的镐去挖沙砾，掉落按镐算——也就是什么都拿不到。
- 白名单一旦填内容就**取代**内置规则（想保留所有镐请自己写 `#minecraft:pickaxes`）。只有这样，「只允许某几把工具」才可能实现。

**可配置的目标方块（`targets`）**

- **`targets.mode`** —— `ORES`（默认，等价于 1.0.2）/ `WHITELIST`（只挖名单里的）/ `ORES_AND_WHITELIST`（矿石与名单的并集，想把沙砾、黏土也纳入目标用这一档）。
- **`targets.whitelist`** —— 追加可挖的方块，支持 `minecraft:gravel` 这类注册名与 `minecraft:*_ore` 这类通配。
- **`targets.blacklist`** —— 永不挖掘，**压过矿石标签**（例如屏蔽铜矿，只留下钻石）。
- **`targets.offhandTargeting`** —— 副手过滤的总开关。

**副手可以指定任意方块**

- 旧版副手只认「矿石材料名」（粗铁/铁矿/铁锭…）。现在副手放**方块物品**同样生效：放黏土就只挖黏土，放沙砾就只挖沙砾。放矿石仍走原来的材料通道，铁矿依然覆盖深板岩铁矿。

**配置热重载**

- 改完配置文件不必重启：配置重载事件会清掉工具/目标缓存，下一次判定立刻用新名单。

**配置文件位置与内置配置界面**

- **配置改为 `COMMON` 类型**：文件从「每个存档一份」的 `world/serverconfig/maidmining-server.toml` 搬到 `config/maidmining-common.toml`，一份配置对所有存档生效，也方便抄给服务器。
- **内置配置界面** —— 游戏内 **Mods → Maid Mining → Config** 直接改：开关、枚举、数值滑块齐全。Forge 1.20.1 本身不提供通用配置界面，这个界面是本模组自带的，不依赖 Cloth Config 等第三方库。
- **名单可视化编辑** —— 工具/目标/垫脚建材名单不再需要手抄注册名：每一条显示图标 + 名称 + 注册名，点「×」移除；「从列表选择…」可从整个物品/方块注册表里搜名称或 id 直接点选，标签（`#minecraft:pickaxes` 之类）也列在最前面，一次选一类。`mod:*` 这类通配仍可在底部输入框手填。
- 界面上改完立刻生效（运行时缓存即时失效），点「完成」落盘。
- **界面只展示玩家会用到的项**：`diag.logLevel`、`diag.showActionSummary`、`compat.fakePlayerMode` 属于调试/管理员开关，不再出现在界面上（配置项仍保留在 toml 里，需要时手改）。观测、兼容两个分组因此不再显示。
- **开关与枚举改为自绘按钮**：原先用 `CycleButton` 的实现点击即崩（玩家反馈，未能取到堆栈）；现在改用普通按钮自己处理「开/关」与「枚举轮换」，行为与文案完全可控。
- 迁移提示：旧的 `world/serverconfig/maidmining-server.toml` 不再被读取；1.1.0 之前没有正式发布，直接从默认值开始即可（想保留旧调参就手工抄到新文件）。

### 调整

- 任务启用条件文案从「需要镐子」改为「需要工具」（键名 `task.maidmining.mining.condition.tool`），与可配置的工具集一致。
- 日志措辞从 "ore" 改为 "target"（`Locked target` / `Skipped bedrock-near target`）：目标不再限于矿石，继续叫 ore 会误导排查。
- 配置项从 35 项七组扩到 **41 项九组**（新增 `tools`、`targets`）。
- `ToolManager` 的等级评分改用 `TieredItem` 而不是 `PickaxeItem`：旧代码下铲子、模组工具一律 0 分，永远排在任何镐子后面。

### 修复

- **补齐两个"只有配置、没有实现"的项**（自查发现）：
  - `safety.minHealthRetreat`（低血撤退）现在真的生效：血量 ≤ 阈值时停止挖掘、往主人身边靠，并在聊天栏提示一次。此前无论怎么调，她都会继续往危险处挖。
  - `travel.followOwnerWhenIdle`（空闲跟随）现在生效：一轮扫描没找到目标、且离主人超过 12 格时，每 20 tick 重新下发一次走位目标。
- **移除死配置 `diag.showActionSummary`**：TLM 的动作摘要取的是任务返回的固定翻译键，这个开关根本控制不到任何东西，留着只会误导。
- **名单编辑页的数据保护**：如果打开时读不到原配置（未加载），现在禁止写回，避免点「完成」把整份名单覆盖成空。

- **头顶的目标不再"够不着"** —— 相邻判定原来要求三轴都在 `adjacentDist` 内，目标在正上方 2 格时（`dy=2, hDist=0`）判定失败，于是去"起塔"；手里没有垫脚建材时会卡满次数直接放弃（日志 `Ascend failed ... cannot ascend`）。现在正上方额外允许一格（玩家站在下面本来就能挖到头顶方块），下方不放开（会悬空掉落，属下降逻辑）。

- **默认工具白名单改为镐类标签**（`#minecraft:pickaxes` / `#forge:tools/pickaxes` / `#c:tools/pickaxes`）。原来的默认是空列表，语义是"空 = 沿用任何镐子，非空 = 整体取代"——玩家只要往里加一项（哪怕加错），镐子就全不能用了（实测：加了一把钻石剑）。现在默认就把镐写进去，界面上的增删都是在这个基础上追加/删除。
  只要列表里还留着镐类标签，内置的"任何镐"兜底识别（标签 / `ToolActions` / "能挖石头"）继续生效，没打标签的模组镐不会掉出去；把镐标签删干净才是严格模式。
  **已有配置文件不会自动更新**（Forge 不重写已存在的文件），需要手动清空该列表或删掉 `config/maidmining-common.toml` 让它重建。

- **地下扫描彻底失效（重要）** —— 扫描器把 `y >> 4` 直接当区块段数组下标，但 1.18+ 主世界最低点是 y=-64（段坐标 -4）：下标整体偏移 4 段，而 y<0 时会算出**负数下标**，循环一次都不执行。症状是"女仆站在 y=-60、身边就有目标，整轮扫描却报告没找到"。现在按 `level.getMinSection()` 换算，并加了回归测试锁住（主世界与超平坦两种基准）。

- **改完目标/工具配置后立即重扫** —— 扫描器会记住"配置代数"，玩家一改配置就从最近一列重新开始。此前它只会沿着旧游标继续走，改动前已经扫过的那一列要等一整轮扫描（半径 48 时约 73 秒）再加最长 30 秒退避才会回头看，表现就是"加了目标方块，女仆却半天不动"。
- **空扫会说明原因** —— 整轮扫描没找到目标时打一行 WARN，带上 `mode`、白名单/黑名单、手持工具与副手物品。此前这三种情况在游戏里看起来完全一样，只能靠猜。

### 兼容性

- **默认配置下行为与 1.0.2 完全一致**，老存档、老配置文件都不需要改动：出厂是 `targets.mode = ORES`、两个名单为空、工具沿用「任何镐子」。这条默认值有 GameTest 契约锁住（`defaultConfigMinesOresOnly`），改成更宽的默认会直接变红。
- 旧方法 `MiningValidator.isPickaxe` / `isTargetedOre` 保留为 `@Deprecated` 别名，附属模组不会因此编译失败。
- GameTest 新增覆盖：白/黑名单优先级、三种目标模式、非镐工具的可挖判定、副手方块匹配、`#标签` 与 `modid:*` 解析。

---

## [1.0.2] - 2026-10-04

本次更新的两个重点是**镐子附魔终于生效**与**代码结构重构**。附魔这一项追查出根因：车万女仆的破坏方法在字节码里把「空工具栈」硬编码传给掉落计算，因此 1.0.x 与 1.01 上时运与精准采集**完全没有效果**——玩家给女仆附了好镐，产出却毫无变化。

### 修复

- **补齐两个"只有配置、没有实现"的项**（自查发现）：
  - `safety.minHealthRetreat`（低血撤退）现在真的生效：血量 ≤ 阈值时停止挖掘、往主人身边靠，并在聊天栏提示一次。此前无论怎么调，她都会继续往危险处挖。
  - `travel.followOwnerWhenIdle`（空闲跟随）现在生效：一轮扫描没找到目标、且离主人超过 12 格时，每 20 tick 重新下发一次走位目标。
- **移除死配置 `diag.showActionSummary`**：TLM 的动作摘要取的是任务返回的固定翻译键，这个开关根本控制不到任何东西，留着只会误导。
- **名单编辑页的数据保护**：如果打开时读不到原配置（未加载），现在禁止写回，避免点「完成」把整份名单覆盖成空。

- **头顶的目标不再"够不着"** —— 相邻判定原来要求三轴都在 `adjacentDist` 内，目标在正上方 2 格时（`dy=2, hDist=0`）判定失败，于是去"起塔"；手里没有垫脚建材时会卡满次数直接放弃（日志 `Ascend failed ... cannot ascend`）。现在正上方额外允许一格（玩家站在下面本来就能挖到头顶方块），下方不放开（会悬空掉落，属下降逻辑）。

- **默认工具白名单改为镐类标签**（`#minecraft:pickaxes` / `#forge:tools/pickaxes` / `#c:tools/pickaxes`）。原来的默认是空列表，语义是"空 = 沿用任何镐子，非空 = 整体取代"——玩家只要往里加一项（哪怕加错），镐子就全不能用了（实测：加了一把钻石剑）。现在默认就把镐写进去，界面上的增删都是在这个基础上追加/删除。
  只要列表里还留着镐类标签，内置的"任何镐"兜底识别（标签 / `ToolActions` / "能挖石头"）继续生效，没打标签的模组镐不会掉出去；把镐标签删干净才是严格模式。
  **已有配置文件不会自动更新**（Forge 不重写已存在的文件），需要手动清空该列表或删掉 `config/maidmining-common.toml` 让它重建。

- **地下扫描彻底失效（重要）** —— 扫描器把 `y >> 4` 直接当区块段数组下标，但 1.18+ 主世界最低点是 y=-64（段坐标 -4）：下标整体偏移 4 段，而 y<0 时会算出**负数下标**，循环一次都不执行。症状是"女仆站在 y=-60、身边就有目标，整轮扫描却报告没找到"。现在按 `level.getMinSection()` 换算，并加了回归测试锁住（主世界与超平坦两种基准）。

**镐子附魔（本次重点）**

- **时运（Fortune）生效** —— 掉落现在按女仆手中那把镐的实际附魔计算。旧版产出恒定，时运 III 也只有 1 个。
- **精准采集（Silk Touch）生效** —— 挖矿石直接得到矿石方块（如钻石矿石方块）而不是原矿粒，可再生、还能当建材。旧版永远拿不到方块。
- **挖矿不再消耗镐子耐久** —— 女仆应当能长期连续挖矿，不该把主人的镐子耗光。旧版每挖一个方块扣 1 点耐久，是本模组自己额外加的消耗（车万女仆本身不扣）。如需按玩家规则消耗，可在配置里打开 `dig.damageTool`。
- **会挑最好的镐子用** —— 旧版拿「背包里第一个镐子」就换手，完全不看等级与附魔：背包里同时有木镐和钻石镐时可能一直用木镐挖不动钻石矿。现在按「挖矿相关附魔 → 基础等级 → 剩余耐久」评分择优，精准采集 > 时运 > 效率 > 耐久修补。

**物品与玩家资产**

- **不再吃掉贵重方块垫脚** —— 垫脚建材改为白名单制（圆石、泥土、下界岩等廉价方块），矿石、钻石块、金块、容器一律拒绝。旧版取「第一个有碰撞体积的方块」，会消耗主人背包里的钻石块。
- **背包展示位（第 5 格）受保护** —— 该格是女仆展示给主人看的背包外观，旧版可能被垫脚消耗或被换镐覆盖。现在所有背包读写路径都显式排除它。
- **背包写入统一走 TLM 的入库校验** —— 旧版直接写槽位，绕过了女仆「能否装入」的判断（包括 TLM 的背包黑名单）。现在一律经官方接口写入，尊重全部既有语义。

### 新增

- **完整配置（35 项）** —— 此前全部参数都是硬编码常量，玩家**一个都改不了**。现在分为搜索 / 移动 / 挖掘 / 物品 / 安全 / 观测 / 兼容七组，配置文件在 `世界/serverconfig/maidmining-server.toml`。默认值取保守策略：不进岩浆、不挖玩家方块、垫脚只用廉价方块。
- **搜索不再强制加载区块** —— 旧版扫描时若碰到未加载区块会**同步加载甚至生成**它，在低模拟距离服务器上每轮扫描都可能强制生成几十个区块。现在只扫已加载的部分，跳过未加载区域。
- **扫描性能** —— 先按区块段跳过全空气段、再用调色板预过滤；搜索热路径不再每格分配对象（旧版一轮约 49 万次分配）。挖不到矿时冷却逐次翻倍，不再每 6 秒空转一轮全量扫描。
- **可选的「领地保护兼容模式」** —— 默认关闭。开启后女仆改用假玩家走玩家破坏路径，绝大多数领地/保护模组即可正常拦截（默认模式下它们只能通过另一类事件 veto）。适合服务器管理员按需启用。
- **任务启用条件** —— 女仆界面现在会显示「需要镐子」，并可判断是否满足。
- **自动化测试扩充到 24 项** —— 新增附魔适配（时运/精准采集/耐久）、物品守恒、展示位保护，
  以及精准采集的场景覆盖（深板岩变体、下界合金镐、与时运并存时的优先级、从背包换手、非矿石方块）。
- **破坏诊断日志** —— 把 `diag.logLevel` 设为 `DEBUG` 后，每次破坏都会打出工具、附魔等级与
  实际产出。附魔是否生效无法从外部现象推断（「挖石头掉圆石」既可能是没附魔、附魔没读到，
  也可能是掉落表被改），有日志才能区分。

### 调整

- **代码结构重构** —— 核心行为类从 954 行拆分为专职组件（破坏执行、垂直移动、垫脚、工具管理、背包读写、会话状态），职责清晰、易维护。**行为语义保持等价**，状态机、失败分类、上行策略均未改变。
- **日志分级** —— 挖到矿这类高频事件降为可选详细日志，默认只输出需要注意的事，避免刷屏。
- **标签重载后缓存自动失效** —— 装了新模组后 `/reload` 即可识别其矿石，不必重启游戏。
- **读取配置更稳健** —— 配置尚未加载时回退到默认值而非崩溃。

### 设计取舍（非缺陷）

以下是**有意为之的设计决定**，不是待修复的缺陷：

- **破坏瞬时完成，不模拟挖掘时间** —— 因此效率附魔不产生实际效果（已在选镐评分中预留位置，若将来引入时间模型即可生效）。这让女仆的挖矿节奏保持稳定，也避免"挖到一半被打断"的复杂状态。
- **不实现耐久修补的配套链路** —— 既然默认不消耗耐久，经验修补自然没有意义。喜欢磨损感的服务器管理员可打开 `dig.damageTool`。
- **目标黑名单不随存档持久化** —— 它是**会话级**的短期记忆，作用是"这次别再撞同一面墙了"。重载后清空是合理的：世界已经变了，旧的失败记录未必还有效。
- **遇到不可破坏的方块直接放弃目标，不做绕墙寻路** —— 这是明确的取舍：与其让女仆在深处闷头挖几十格后失败，不如立刻换一个目标。基岩旁的矿默认也会跳过（见上方「调整」）。

---

## English

### [1.0.2] - 2026-10-04

Two focuses: **pickaxe enchantments finally work**, and a **code structure refactor**. The enchantment fix required tracing the root cause: Touhou Little Maid hardcodes an *empty tool stack* when computing block drops, so on 1.0.x and 1.01 **Fortune and Silk Touch had no effect whatsoever** — enchant a maid's pickaxe and nothing changed.

**Fixed**

- **Fortune applies.** Drops are now computed with the pickaxe actually in hand. Previously yield was constant and Fortune III still gave a single item.
- **Silk Touch applies.** Mining ores yields the block itself (e.g. diamond ore) instead of raw diamonds — renewable, and usable as building material.
- **Mining no longer consumes pickaxe durability.** The maid is meant to mine continuously without wearing out the player's tools. Durability was an extra cost this mod added on top of TLM's own behaviour. Set `dig.damageTool` to restore player-like wear.
- **The best pickaxe is chosen.** The old code equipped the *first* pickaxe it found, ignoring tier and enchantments — so a maid could keep using a wooden pickaxe while a diamond one sat in her inventory. Tools are now scored by mining enchantments → tier → remaining durability.
- **Valuable blocks are no longer consumed for scaffolding.** Scaffolding uses an allowlist (cobblestone, dirt, netherrack, …); ores, diamond/gold/iron blocks and containers are refused.
- **The display slot (slot 5) is protected.** It is the bag appearance shown to the player and could previously be consumed or overwritten.
- **Inventory writes go through TLM's own validation**, respecting its backpack blacklist and `canInsertItem` rules.

**Added**

- **35 configuration options** in seven groups, written to `world/serverconfig/maidmining-server.toml`. Previously every parameter was a hardcoded constant that players could not change at all. Defaults are conservative: no lava, no player blocks, cheap scaffolding only.
- **Scanning never force-loads chunks** — previously it could synchronously load or generate them, potentially generating dozens of chunks per pass on low-simulation-distance servers.
- **Faster scanning** — skips all-air sections, uses palette pre-filtering, and allocates nothing per block (was ~489k allocations per pass). Empty scans now back off exponentially.
- **Optional claim-protection compatibility mode** (off by default) that breaks blocks via a fake player so most land-claim mods can intercept them.
- **Task enable condition** ("needs a pickaxe") shown in the maid GUI.
- **24 GameTests**, covering enchantments, item conservation, display-slot protection, and Silk Touch
  across its variants (deepslate ores, netherite pickaxe, coexisting Fortune, equipping from backpack).
- **Break diagnostics** — with `diag.logLevel = DEBUG`, every break logs the tool, its enchantment
  levels and the actual drops. Whether an enchantment applied cannot be inferred from the outside.

**Changed**

- **Refactored the core behaviour** from 954 lines into focused components (digging, vertical movement, scaffolding, tool selection, inventory access, session state). **Behaviour is semantically unchanged.**
- **Log levels** — per-ore events are now opt-in verbose logging to avoid spam.
- **Caches invalidate on tag reload** — `/reload` is enough after installing a new mod.
- **Configuration reads degrade gracefully** instead of crashing if loaded too early.

**Design trade-offs (not defects)**

The following are **intentional design decisions**, not bugs awaiting a fix:

- **Breaking is instantaneous, with no mining-time simulation** — so Efficiency has no practical effect. Its slot in the pickaxe scoring is reserved should a time model ever be added. This keeps the maid's rhythm predictable and avoids the complexity of "interrupted mid-dig" states.
- **No Mending support to go with it** — since durability is not consumed by default, Mending has nothing to repair. Servers that prefer wear can enable `dig.damageTool`.
- **Target blacklists are not persisted** — they are *session-level* short-term memory, there to stop her hitting the same wall repeatedly within one run. Clearing them on reload is correct: the world has changed, and stale failures may no longer apply.
- **Unbreakable blocks cause an immediate target change, no pathfinding around them** — a deliberate trade-off: rather than having the maid tunnel dozens of blocks before failing, she moves on to the next target at once. Ores next to bedrock are skipped by default too (see "Changed" above).

---

## [1.0.1] - 2026-09-20

本次更新围绕**「女仆在地下挖矿会卡住」**做了一次系统性修复，并补上矿石定向与模组矿石支持。

### 修复

- **补齐两个"只有配置、没有实现"的项**（自查发现）：
  - `safety.minHealthRetreat`（低血撤退）现在真的生效：血量 ≤ 阈值时停止挖掘、往主人身边靠，并在聊天栏提示一次。此前无论怎么调，她都会继续往危险处挖。
  - `travel.followOwnerWhenIdle`（空闲跟随）现在生效：一轮扫描没找到目标、且离主人超过 12 格时，每 20 tick 重新下发一次走位目标。
- **移除死配置 `diag.showActionSummary`**：TLM 的动作摘要取的是任务返回的固定翻译键，这个开关根本控制不到任何东西，留着只会误导。
- **名单编辑页的数据保护**：如果打开时读不到原配置（未加载），现在禁止写回，避免点「完成」把整份名单覆盖成空。

- **头顶的目标不再"够不着"** —— 相邻判定原来要求三轴都在 `adjacentDist` 内，目标在正上方 2 格时（`dy=2, hDist=0`）判定失败，于是去"起塔"；手里没有垫脚建材时会卡满次数直接放弃（日志 `Ascend failed ... cannot ascend`）。现在正上方额外允许一格（玩家站在下面本来就能挖到头顶方块），下方不放开（会悬空掉落，属下降逻辑）。

- **默认工具白名单改为镐类标签**（`#minecraft:pickaxes` / `#forge:tools/pickaxes` / `#c:tools/pickaxes`）。原来的默认是空列表，语义是"空 = 沿用任何镐子，非空 = 整体取代"——玩家只要往里加一项（哪怕加错），镐子就全不能用了（实测：加了一把钻石剑）。现在默认就把镐写进去，界面上的增删都是在这个基础上追加/删除。
  只要列表里还留着镐类标签，内置的"任何镐"兜底识别（标签 / `ToolActions` / "能挖石头"）继续生效，没打标签的模组镐不会掉出去；把镐标签删干净才是严格模式。
  **已有配置文件不会自动更新**（Forge 不重写已存在的文件），需要手动清空该列表或删掉 `config/maidmining-common.toml` 让它重建。

- **地下扫描彻底失效（重要）** —— 扫描器把 `y >> 4` 直接当区块段数组下标，但 1.18+ 主世界最低点是 y=-64（段坐标 -4）：下标整体偏移 4 段，而 y<0 时会算出**负数下标**，循环一次都不执行。症状是"女仆站在 y=-60、身边就有目标，整轮扫描却报告没找到"。现在按 `level.getMinSection()` 换算，并加了回归测试锁住（主世界与超平坦两种基准）。

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
