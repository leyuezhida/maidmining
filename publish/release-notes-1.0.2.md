本次更新的两个重点是**镐子附魔终于生效**与**代码结构重构**。

附魔这一项追查出根因：车万女仆的破坏方法在字节码里把「空工具栈」硬编码传给掉落计算，因此在 1.0.x 与 1.01 上时运与精准采集**完全没有效果**——玩家给女仆附了好镐，产出却毫无变化。

## 修复

### 镐子附魔（本次重点）

- **时运（Fortune）生效** —— 掉落现在按女仆手中那把镐的实际附魔计算。旧版产出恒定，时运 III 也只有 1 个。
- **精准采集（Silk Touch）生效** —— 挖矿石直接得到矿石方块（如钻石矿石方块）而不是原矿粒，可再生、还能当建材。旧版永远拿不到方块。
- **挖矿不再消耗镐子耐久** —— 女仆应当能长期连续挖矿，不该把主人的镐子耗光。旧版每挖一个方块扣 1 点耐久，是本模组自己额外加的消耗（车万女仆本身不扣）。如需按玩家规则消耗，可在配置里打开 `dig.damageTool`。
- **会挑最好的镐子用** —— 旧版拿「背包里第一个镐子」就换手，完全不看等级与附魔：背包里同时有木镐和钻石镐时可能一直用木镐挖不动钻石矿。现在按「挖矿相关附魔 → 基础等级 → 剩余耐久」评分择优，精准采集 > 时运 > 效率 > 耐久修补。

### 物品与玩家资产

- **不再吃掉贵重方块垫脚** —— 垫脚建材改为白名单制（圆石、泥土、下界岩等廉价方块），矿石、钻石块、金块、容器一律拒绝。旧版取「第一个有碰撞体积的方块」，会消耗主人背包里的钻石块。
- **背包展示位（第 5 格）受保护** —— 该格是女仆展示给主人看的背包外观，旧版可能被垫脚消耗或被换镐覆盖。现在所有背包读写路径都显式排除它。
- **背包写入统一走车万女仆的入库校验** —— 旧版直接写槽位，绕过了女仆「能否装入」的判断（包括背包黑名单）。现在一律经官方接口写入，尊重全部既有语义。

## 新增

- **完整配置（35 项）** —— 此前全部参数都是硬编码常量，玩家**一个都改不了**。现在分为搜索 / 移动 / 挖掘 / 物品 / 安全 / 观测 / 兼容七组，配置文件在 `世界/serverconfig/maidmining-server.toml`。默认值取保守策略：不进岩浆、不挖玩家方块、垫脚只用廉价方块。
- **搜索不再强制加载区块** —— 旧版扫描时若碰到未加载区块会**同步加载甚至生成**它，在低模拟距离服务器上每轮扫描都可能强制生成几十个区块。现在只扫已加载的部分，跳过未加载区域。
- **扫描性能** —— 先按区块段跳过全空气段、再用调色板预过滤；搜索热路径不再每格分配对象（旧版一轮约 49 万次分配）。挖不到矿时冷却逐次翻倍，不再每 6 秒空转一轮全量扫描。
- **可选的「领地保护兼容模式」** —— 默认关闭。开启后女仆改用假玩家走玩家破坏路径，绝大多数领地/保护模组即可正常拦截（默认模式下它们只能通过另一类事件否决）。适合服务器管理员按需启用。
- **任务启用条件** —— 女仆界面现在会显示「需要镐子」，并可判断是否满足。
- **自动化测试扩充到 24 项** —— 新增附魔适配、物品守恒、展示位保护，以及精准采集的场景覆盖（深板岩变体、下界合金镐、与时运并存时的优先级、从背包换手、非矿石方块）。

## 调整

- **代码结构重构** —— 核心行为类从 954 行拆分为专职组件（破坏执行、垂直移动、垫脚、工具管理、背包读写、会话状态），职责清晰、易维护。**行为语义保持等价**，状态机、失败分类、上行策略均未改变。
- **日志分级** —— 挖到矿这类高频事件降为可选详细日志，默认只输出需要注意的事，避免刷屏。
- **标签重载后缓存自动失效** —— 装了新模组后 `/reload` 即可识别其矿石，不必重启游戏。
- **读取配置更稳健** —— 配置尚未加载时回退到默认值而非崩溃。

## 已知问题

- 破坏仍是瞬时完成，**未模拟挖掘时间**；因此效率附魔暂不产生实际效果（已在选镐评分中预留位置，计划 1.2.0 引入时间模型）。
- 耐久修补因「默认不消耗耐久」而无实际意义。
- 目标黑名单仍只在内存中，不随存档持久化（计划 1.2.0）。
- 遇到不可破坏的方块只会放弃目标，尚无「绕墙寻路」（计划 1.2.0）。

---

## English

Two focuses: **pickaxe enchantments finally work**, and a **code structure refactor**. The enchantment fix required tracing the root cause: Touhou Little Maid hardcodes an *empty tool stack* when computing block drops, so on 1.0.x and 1.01 **Fortune and Silk Touch had no effect at all** — enchant a maid's pickaxe and nothing changed.

### Fixed

- **Fortune applies.** Drops are now computed with the pickaxe actually in hand. Previously yield was constant and Fortune III still gave a single item.
- **Silk Touch applies.** Mining ores yields the block itself (e.g. diamond ore) instead of raw diamonds — renewable, and usable as building material.
- **Mining no longer consumes pickaxe durability.** The maid is meant to mine continuously without wearing out the player's tools. Durability was an extra cost this mod added on top of TLM's own behaviour. Set `dig.damageTool` to restore player-like wear.
- **The best pickaxe is chosen.** The old code equipped the *first* pickaxe it found, ignoring tier and enchantments — so a maid could keep using a wooden pickaxe while a diamond one sat in her inventory. Tools are now scored by mining enchantments, then tier, then remaining durability.
- **Valuable blocks are no longer consumed for scaffolding.** Scaffolding uses an allowlist (cobblestone, dirt, netherrack, …); ores, diamond/gold/iron blocks and containers are refused.
- **The display slot (slot 5) is protected.** It is the bag appearance shown to the player and could previously be consumed or overwritten.
- **Inventory writes go through TLM's own validation**, respecting its backpack blacklist and `canInsertItem` rules.

### Added

- **35 configuration options** in seven groups, written to `world/serverconfig/maidmining-server.toml`. Previously every parameter was a hardcoded constant that players could not change at all.
- **Scanning never force-loads chunks** — previously it could synchronously load or generate them, potentially generating dozens of chunks per pass on low-simulation-distance servers.
- **Faster scanning** — skips all-air sections, uses palette pre-filtering, and allocates nothing per block (was ~489k allocations per pass). Empty scans now back off exponentially.
- **Optional claim-protection compatibility mode** (off by default) that breaks blocks via a fake player so most land-claim mods can intercept them.
- **Task enable condition** ("needs a pickaxe") shown in the maid GUI.
- **24 GameTests**, covering enchantments, item conservation, display-slot protection, and Silk Touch across its variants.
- **Break diagnostics** — with `diag.logLevel = DEBUG`, every break logs the tool, its enchantment levels and the actual drops.

### Changed

- **Refactored the core behaviour** from 954 lines into focused components (digging, tunnel advance, vertical movement, scaffolding, tool selection, inventory access, session state). **Behaviour is semantically unchanged.**
- **Log levels** — per-ore events are now opt-in verbose logging to avoid spam.
- **Caches invalidate on tag reload** — `/reload` is enough after installing a new mod.
- **Configuration reads degrade gracefully** instead of crashing if loaded too early.

### Known issues

- Breaking is still instantaneous, so **Efficiency has no practical effect yet** (its slot in the pickaxe scoring is reserved for the future mining-time model).
- Mending is moot while durability is not consumed by default.
- Target blacklists are still not persisted to the save file.
- Blocked paths are abandoned rather than routed around.

---

## Requirements

| Dependency | Version |
|------------|---------|
| Minecraft | 1.20.1 |
| Forge | 47.4.22+ |
| Touhou Little Maid | 1.5.3+ |

安装：把 `maidmining-1.0.2.jar` 放进 `mods/`。需要 **1.5.3 或更高**版本的车万女仆。
