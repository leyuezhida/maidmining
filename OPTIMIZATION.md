# 女仆挖矿 · 优化框架（Optimization Framework）

> **文档定位**：本文档是 `maidmining` 模组后续所有优化的**唯一目标清单与验收依据**。
> 任何代码改动都必须先对应到本文档中的一个条目编号（`MM-xxx`）；新发现的问题先登记再修。
>
> - 适用代码基线：`maidmining` v1.0.0（提交于 `build/libs/maidmining-1.0.0.jar`，2026-08-12 构建）
> - 目标平台：Minecraft 1.20.1 / Forge 47.4.22 / 车万女仆 TLM 1.5.3
> - 编写日期：2026-09-20
> - 证据等级说明：`【码】`=源码可读证据（文件:行）　`【测】`=实机日志/运行证据　`【推】`=由常量推导的算术结论　`【字节码】`=依赖库 `.class` 指令流证据（见附录 D）　`【待核实】`=需实测或反编译确认
>
> ⚠️ **发布须知**：本文档包含尚未修复的缺陷（含一个物品复制漏洞）~~与安全边界问题~~。
> **2026-10-04 更新**：物品复制漏洞（MM-501）、展示位（MM-509）、专用服务器崩溃（MM-901）、
> 岩浆（MM-401）、垫脚贵重方块（MM-502）均已修复；`OPTIMIZATION.md` 已可随仓库公开
> （但仍建议发布前确认无敏感信息）。

---

## 0. 如何使用本文档

### 0.1 条目格式

每个优化项固定五个字段，缺一不可：

| 字段 | 含义 |
|------|------|
| **现状证据** | 源码 `文件:行` 或日志/实测数据。没有证据的条目不允许进入清单 |
| **优化目标** | 改完之后"应该是什么样"，描述行为而非实现 |
| **验收标准** | 可执行、可观察、可判定的通过条件（命令/日志/断言/数值） |
| **优先级** | P0 / P1 / P2 / P3，定义见 0.2 |
| **工作量** | S（<半天）/ M（1–2 天）/ L（3–5 天）/ XL（>1 周） |

### 0.2 优先级定义

| 级别 | 判定标准 | 处理时限 |
|------|---------|---------|
| **P0** | 数据安全（复制/丢失/坏档）、崩溃风险、可被玩家利用的漏洞、把女仆弄死且玩家无法避免 | 1.0.1 热修，优先级高于一切新功能 |
| **P1** | 功能不符合宣称、明显浪费/卡顿、服务器不友好、无法配置导致无法使用 | 1.1.0 |
| **P2** | 行为质量、体验、可观测性、扩展能力 | 1.2.0 |
| **P3** | 长期演进（新采矿模式、多女仆协作、生态集成） | 2.0.0 / 视需求 |

### 0.3 变更控制规则

1. **一条改动 = 一个编号**。提交信息里写 `MM-401: 岩浆不再视为可通行`。
2. 修 bug 时若发现新问题：先加入本文档（哪怕只有一行），再决定是否本轮修。
3. 每个里程碑结束时回填"实测值"列（见 §2 的 KPI 表），形成可对比的历史基线。
4. **禁止在没有回归测试的情况下修 P0 物品/持久化相关缺陷**（见 MM-1003）。

---

## 1. 现状基线（As-Is）

### 1.1 代码结构

| 文件 | 行数 | 职责 | 主要问题 |
|------|-----:|------|---------|
| `MaidMiningMod.java` | 20 | 主类、事件总线注册 | 无配置、无指令、无事件监听 |
| `MaidExtension.java` | 19 | 向 TLM `TaskManager` 注册任务 | 无 |
| `task/MiningTask.java` | 78 | 任务 UID/图标/名称/描述/行为装配 | 引用客户端类（P0）、i18n 双份事实源、条件描述为空 |
| `mining/MiningConfig.java` | 41 | 全部可调参数 | **全是 `static final` 常量，玩家无法配置**；缺少安全/物品/性能/兼容类参数 |
| `mining/MiningValidator.java` | 184 | 矿石/镐子/可挖/基岩判定 | 热路径无缓存、基岩规则过激、副手过滤语义不完整、多个方法死代码 |
| `mining/MiningTunnelFinder.java` | 92 | 分帧扫描最近矿石 | 强制遍历 489k 方块、热路径对象分配、不是真正的"最近"、未加载区块无保护 |
| `mining/MiningTunnelBehavior.java` | **486** | **单体状态机**：搜索+寻路+挖掘+攀爬+拾取+换镐+日志 | 职责全混、状态几乎不可测、`setPos` 传送、多处复制/绕过事件、危险无感知 |

合计约 920 行、7 个类（不含资源）。**没有一行测试代码**（`build.gradle:40,43,46-48` 已配置 GameTest namespace，但 `src/test` 与 gametest 源码目录为空）。

### 1.2 运行时控制流（现状）

```
MiningTunnelBehavior.tick()  ← 每 tick 由女仆大脑调用，永不退出（canStillUse 恒 true，duration=Integer.MAX_VALUE）
├─ timer++
├─ 每 20 tick：若主手不是镐 → equipPickaxe()
├─ 每 10 tick：pickupDrops()（1.5 格 AABB 内所有 ItemEntity 中的 BlockItem）
└─ switch(state)
   ├─ SEARCH ── finder.findNearestOre()  ← 每 tick 最多 128 列 × 52 层 = 6656 次 getBlockState
   │             命中 → 基岩邻近检查（命中即丢弃）→ state=DIG
   ├─ DIG ────── 每 6 tick 执行一次：拆解为 高度调整(tryClimb) / 水平推进(清 3 格高) / 直下挖
   │             贴近目标 → state=MINE ；判定失败 → toSearch()
   └─ MINE ───── 邻接则 destroyBlock()，然后**无条件** toSearch()
```

关键事实：**状态机没有任何"记忆"**——每挖掉一个矿石就 `toSearch()`，重置 `timer/cursor/黑名单外的全部字段`（`MiningTunnelBehavior.java:445-471`），然后从 0 重新扫描。

### 1.3 可量化基线（来自实机日志）

对 `run/logs/debug-2/3/4/5.log.gz`（2026-08-11 至 08-14 四次运行）中的 `[MaidMining]` 事件做了聚合：

| 指标 | 实测值 | 证据 |
|------|-------|------|
| 单次会话挖到矿石数 | **37** | `debug-2.log.gz` 的 `Mined` 事件 |
| 目标锁定次数 / 唯一目标数 | 40 / 39 | `Locked ore` 事件（含 1 次重复锁定 `-223,-52,-216`） |
| 锁定成功率 | 37/45 = **82%**（四次会话合计） | `Locked` 45 次 vs `Mined` 37 次 |
| 平均挖矿间隔 | **3.0 秒/矿** | `Mined` 时间戳差分（avg） |
| 最坏挖矿间隔 | **41.0 秒** | 同上（max）；min 0.1 s |
| 因"基岩邻近"被丢弃的矿石 | **27 个**，y 值全部落在 **-59 ~ -63** | 四次会话 `Skipped bedrock-near ore` 合计 |
| 丢弃率 | 27 / (45 + 27) = **37.5%** | 同上 |
| 实际挖到的矿石 y 范围 | -58 ~ -40 | `Mined` 坐标 |
| 卡死/黑名单事件 | **0 次**（这四次会话中未触发） | 无 `Blocked`/`Blacklisted`/`Destroy fail` 日志 |

> 样本说明：四次会话都较短（每个日志文件 30–90 条事件），数字只作**指示性基线**，不作为统计结论。
> 但"y=-59~-63 的矿被大面积丢弃"这一点在四次会话中一致出现，且与代码逻辑完全吻合（见 MM-110），可视为已确认缺陷。

**结论（基线最重要的一条）**：当前版本最严重的功能损失不是"挖不动"，而是**把最深、最有价值的矿（y=-59~-63 的深层深板岩矿，正是钻石/红石/深层金的主要分布带）直接扔掉了近 4 成**。
而这条规则的由来并不是"这些矿挖不到"，而是**女仆会被基岩卡住**（§1.5-A：`breakBlock` 失败不分类 → 对基岩空转 6 秒 → 恢复动作向上传送 → 循环上爬）。
同理，1×3 的矿道是"上行会卡住"的补偿（§1.5-B）。**这两处都必须打在上行/受阻逻辑上，而不是继续保留补偿。**

### 1.4 已确认缺陷索引（速查，详见 §3）

| 编号 | 一句话 | 级别 |
|------|--------|------|
| MM-501 | 掉落物部分转移时不回收实体 → **物品复制漏洞**；`setStackInSlot` 绕过入库校验 | P0 |
| MM-509 | 无差别读写 36 格背包 → 可能**消耗/覆盖女仆的背包展示位物品**（槽位 5） | P0 |
| MM-901 | `MiningTask` 覆写方法引用客户端类 → 专用服务器 `NoClassDefFoundError`（且该覆写本就多余） | P0 |
| MM-401 | 任何流体都视为可通行 → **女仆会走进岩浆** | P0 |
| MM-302 | 垫脚放置直接 `level.setBlock` → 任何保护 mod 都无法拦截 | P0 |
| MM-502 | 垫脚建材无过滤 → 会吃掉钻石块、矿石块、主人的建材 | P0 |
| MM-101 | 扫描不判断区块是否加载 → 理论上有强制加载/生成区块的风险 | P0【待核实】 |
| MM-110 | 基岩邻近规则过激 → 实测丢弃 37.5% 的发现矿 | P1 |
| MM-102/103/104 | 扫描热路径：每轮 489,268 次方块查询 + 等量对象分配 + 无缓存注册表查询 | P1 |
| MM-204/306 | 无单目标预算、无统一卡住判定 → 最坏间隔 41 秒 | P1 |
| MM-503/507 | 自定义拾取绕过 TLM 的拾物模式（`isPickup()`）与 `MaidPickupEvent` | P1 |
| MM-701/702 | 全部参数是 `static final` → 玩家无法调 | P1 |
| MM-301 | 破坏通道其实**是**正确的（走 `onEntityDestroyBlock`，保护 mod 可拦截）——缺的是"不挖容器/玩家建筑"策略；且 **`mobGriefing=false` 会让女仆彻底挖不动并静默发呆** | P1（已修正） |
| MM-303 | 破坏/掉落不计时运与精准采集（TLM 求掉落时固定传空工具栈），瞬时破坏也绕过了硬度与工具等级 | P1 |
| MM-110/304/305/306 | **基岩跳过与 1×3 矿道都是"上行会卡住"的补偿措施**（见 §1.5），必须一起根治 | P1 |

### 1.5 两个历史妥协的真实根因（开发者口述 + 代码印证）

**这两个"看起来不合理"的规则不是设计选择，而是为了掩盖"上行动作会卡住"所做的补偿。
修复必须打在受阻/上行逻辑上，而不是保留补偿。**

**妥协 A：跳过基岩邻近的矿石（`MiningValidator.isNearBedrock`，`:171-183`）**

- 口述根因【实测/开发者】：女仆**会被基岩卡住**，所以干脆不挖靠近基岩的矿。
- 代码印证：
  1. `breakBlock` 的失败**不区分原因**（`:313-323`）——基岩（永不可破）、流体、越界、被保护、暂时失败，全都只返回 `false`；
  2. 调用侧只能一律 `++stuckCount`（`:187,217,238,250`），而该分支被 `DIG_INTERVAL_TICKS = 6` 节流 ⇒ 对着基岩**空转约 120 tick（6 秒）**才轮到恢复动作；
  3. `dy <= -2` 分支的恢复动作是 `tryClimb()`（`:188`），而目标在正下方、脚下是基岩时，`tryClimb` 走"直上"分支执行**向上传送 1 格**（`:356`）并把 `stuckCount` 清零（`:188`）；
  4. ⇒ 下一轮又回到同一步：空转 6 秒 → 再上爬 1 格 → 无限循环。**这就是"被基岩卡住"的真身。**
- 结论：基岩的正确语义是**墙**（规划时绕开），不是"这个矿不能挖"。MM-110 因此改为两段式：先关规则止血 + 失败分类，再由规划器根治后**删除**规则。

**⚠️ 2026-09-20 第五轮：规则被按实机反馈重新打开（止损优先）**

关掉规则后的实机日志显示，深层（y ≤ -60）出现了**碎裂循环**：

```
18:53:34.622  Wall UNBREAKABLE (-222,-62,-234)   ← 目标 y=-63，脚下是基岩
18:53:36.171  Wall UNBREAKABLE (-222,-61,-231)   ← 同一个墙块，连续卡死 4 个目标
18:53:36.522  Wall UNBREAKABLE (-222,-61,-231)
18:53:36.873  Wall UNBREAKABLE (-222,-61,-231)
18:53:37.223  Wall UNBREAKABLE (-222,-61,-231)
```

2.5 秒内「锁定 → 撞同一面基岩墙 → 放弃」重复 4 次，女仆表现为**站着反复搜索**。
（本轮 9 次放弃中有 **6 次**是基岩。）

⇒ 决定：`SKIP_NEAR_BEDROCK` 重新**默认开启**，并在 GameTest 里加了一条**契约用例**钉住这个决定
（`bedrockSkipRuleIsCurrentlyEnabled`，附原因注释）。取舍写在 `MiningConfig` 的字段注释里：
开启会丢弃一部分**本来可挖**的深层矿（旧实测丢弃率 37.5%）；关闭则保留全部候选但会出现上述碎裂循环。
**规划器（MM-202）落地后改为"按墙绕行"，届时这条开关应被真正删除。**

**妥协 B：把矿道挖成 1×3（`head2` / `destHead2`）**

- 口述根因【实测/开发者】：女仆**向上时会卡住**，所以把净高放大到 3 格来规避。
- 代码印证：
  1. 水平推进额外清 `feet+(dx,2,dz)`（`:234-259`），比玩家身高等多挖一整层；
  2. `tryClimb` 的侧向分支在传送前要求 `stepPos+1` 与 `stepPos+2` **全部可通行**（`:365-372`）；
  3. 但上行的**主路径是"走路"**：`stepPos` 为固体时只发 `setWalkAndLookTargetMemories(maid, dest, …)`（`:389`），依赖原版寻路在 1 格宽竖井里完成一次跳步；**一旦寻路不动，这条路径没有任何兜底**（只有 `stepPos` 为空才会"放置方块 + 传送"，`:381-387`）⇒ 卡住。
- 结论：正确做法是「净高按女仆**实际碰撞箱**计算（通常 2）+ 用 `level.noCollision` 做真正的通行校验 + 跳跃优先、传送兜底」，
  而不是把隧道挖成 3 格高去迁就一个不可靠的跳步。**MM-304 与 MM-305 必须一起改**，只改一个会复发卡住。

**⚠️ 上面的结论不完整，已被实机反馈推翻一半（2026-09-20 第二轮）**

把净高降到 2 之后，实机观察：「**向上挖会卡住，看起来跳的不够高，最后还是得靠传送**」。物理上完全成立：

> 女仆 `bbHeight = 1.5`。1×2 隧道里她站在 y，身体占 `[y, y+1.5]`，天花板在 `y+2`。
> 起跳初速 0.42（vanilla `LivingEntity#getJumpPower`），但**头顶一撞到 y+2 的天花板，上抛速度立刻被清零
> ⇒ 最多只能抬高 0.5 格**，而台阶高 1.0 格。
> ⇒ **2 格高的巷道里，女仆在物理上永远迈不上 1 格台阶**（玩家也一样：2 格高的走廊里根本跳不起来）。

所以原始作者把整条矿道挖成 1×3，**不是"怕卡住多挖一点"的偷懒，而是让上行能跳起来的必要条件**——
本轮把它降成 1×2 时破坏了这个前提。**真正的正确解法是"只在上行处加高"**：

| 部位 | 净高 | 理由 |
|------|------|------|
| 水平推进 / 向下 | **2（1×2）** | 省下原设计 1/3 的无谓挖掘 |
| **上台阶那一格** | **3（局部凹坑）** | 给出起跳高度；玩家在 2 格高巷道里上台阶时正是这么干的 |

实现：`MiningConfig.JUMP_HEADROOM = 1` + `stepUp()` 的第 3.5 步——起跳前若 `feet + height` 不可通行，就先挖掉它。
每级台阶只多挖 1 格，而不是每前进 1 格多挖 1 格。

---

## 2. 优化目标（KPI）

### 2.1 北极星指标

> **在默认配置下，一名女仆连续挖矿 10 分钟：不死亡、不破坏玩家建筑、不复制/丢失任何物品、平均 ≤ 2 秒产出一块矿石，且对服务端 TPS 的影响 ≤ 0.1 ms/tick。**

### 2.2 性能预算（硬性红线）

| 指标 | 当前（推算/实测） | 目标 | 验证方法 |
|------|------------------|------|---------|
| 单女仆方块查询 | ≤ 6,656 /tick【推】 | **≤ 256 /tick**，整轮扫描 ≤ 2 s 墙钟 | MM-805 计数器 + JFR |
| 单女仆 tick 耗时 | 未测 | **≤ 0.05 ms**（p99 ≤ 0.2 ms） | JFR / Spark profiler |
| 未加载区块访问 | 无保护【待核实】 | **0 次**（前置 `hasChunkAt`） | 单测断言 + 计数器 |
| 热路径对象分配 | ~489k `BlockPos`/轮 ≈ 12–16 MB【推】 | **0 分配/tick** | JFR allocation profile |
| 10 女仆同时挖矿 | 未测（线性叠加风险） | 服务端 TPS **≥ 19.5** | `/forge tps` 30 分钟压测 |
| 空扫行为 | 每 5.7 s 空转一轮 489k 查询【推】 | 指数退避至 ≤ 1 次/30 s 并停止无意义扫描 | MM-105/802 |

> 推算依据：扫描列数 = (2×48+1)² = **9,409**；y 层数 = `SEARCH_VERTICAL_UP(3) + SEARCH_VERTICAL_DOWN(48) + 1` = **52**；
> 一轮 = 9,409 × 52 = **489,268** 次 `getBlockState`；`COLUMNS_PER_TICK=128` → 一轮 ≥ 74 tick；
> 覆盖区块 = 97 格跨度 → 单轴 7~8 区块 → **49~64 个区块**。（来源：`MiningConfig.java:14-24`、`MiningTunnelFinder.java:55-78`）

### 2.3 正确性与安全（零容忍项）

| 目标 | 判定 |
|------|------|
| **物品守恒** | 任何路径（挖掘掉落、拾取、垫脚、换镐、收纳）都不产生净增/净减；用 GameTest 断言背包总数守恒（MM-1003） |
| **不破坏玩家资产** | 默认不挖玩家放置的方块/容器、不消耗贵重建材、不覆盖女仆展示位；破坏走 `destroyBlock`（保护 mod 可经 `onEntityDestroyBlock` 拦截，已核实）、放置走 `BlockItem#place`（MM-302/502/509/704） |
| **女仆不死** | 默认配置下因岩浆/坠落/窒息/沙砾致死次数 = 0（MM-401~404） |
| **不崩溃** | 专用服务器 + 客户端双端启动、任务切换、存档重载、区块卸载均不抛异常（MM-901/907） |
| **可中断** | 切换任务/收回女仆/服务器关闭时，不留下半成品状态与悬空物品（MM-604） |

### 2.4 体验指标

| 指标 | 目标 |
|------|------|
| 平均产出效率 | ≤ 2.0 s/矿（基线 3.0 s） |
| 最坏单目标耗时 | ≤ 20 s，且任何"无进展"不超过 10 s（基线 41 s） |
| 发现矿丢弃率 | ≤ 5%（仅真正不可达；基线 37.5%） |
| 锁定成功率 | ≥ 98%（基线 82%） |
| 玩家可理解性 | 女仆当前在做什么、为什么停下，玩家能通过 i18n 消息/指令看到（MM-802/803/804） |
| 配置能力 | 附录 B 中所有参数玩家可改，改完无需重启（MM-701/702） |

### 2.5 工程质量

| 指标 | 目标 |
|------|------|
| 单元测试 | 纯逻辑（扫描/评分/过滤/转移/配置）覆盖率 ≥ 70% |
| GameTest | ≥ 8 个场景（见 §6.2），CI 可跑 |
| 文档一致性 | 代码注释、`README`、`HANDOFF`、本文档、lang 文件的参数与描述一致（MM-1007） |
| 单类规模 | 任何类 ≤ 250 行（当前 `MiningTunnelBehavior` 486 行） |

---

## 3. 分模块优化目标

> 表格中 `文件:行` 均指 v1.0.0 源码。`P` = 优先级，`W` = 工作量。

### M1 感知层：矿石搜索

| 编号 | P | W | 现状证据 | 优化目标 | 验收标准 |
|------|---|---|---------|---------|---------|
| **MM-101** ✅ | **P0** | M | `MiningTunnelFinder.java:69-77` 对每个候选坐标直接 `level.getBlockState`，无加载判断；扫描覆盖 49~64 个区块。**Q-1 已核实（附录 D.3.1）：`Level#getBlockState` 在未加载区块上会同步加载/生成区块**——链路 `getBlockState → getChunk(x,z) → LevelReader.getChunk(x,z,FULL) → getChunk(...,requireChunk=true) → ServerChunkCache`（`TicketType` + `runDistanceManagerUpdates` + `managedBlock(future::isDone)` 阻塞等待），失败还会抛 `IllegalStateException("Chunk not there when requested")`。⇒ 低 `simulation-distance` 服务器上，每轮扫描都可能**强制生成数十个区块** | 扫描绝不触碰未加载区块：用 **`level.isLoaded(pos)`**（已核实为不加载的检查；`hasChunkAt` 已 `@Deprecated`）或按区块段遍历；搜索半径自动收敛到服务端已加载范围 | 计数器"未加载跳过数" > 0 且"未加载访问数" = 0；低 `simulation-distance` 服务器上无区块生成尖峰（Q-9 实测） |
| **MM-102** ✅ | P0 | S | `MiningTunnelFinder.java:70` 每个 (列,y) `new BlockPos(...)` → 一轮 ≈ 489k 次分配 | 热路径零分配：使用 `BlockPos.MutableBlockPos` 复用 + 以 `long` 打包坐标做 key | JFR 分配采样：扫描阶段无 `BlockPos` 分配 |
| **MM-103** | P1 | M | `MiningValidator.java:43-49` 每个非标签方块都做 `ForgeRegistries.BLOCKS.getKey()` + `endsWith("_ore")` | 矿石识别结果按 `Block` 缓存在 `ObjectSet`/`IntSet`，随 `TagsUpdatedEvent` 失效重建 | 100 万次 `isOre` 调用 < 10 ms；数据包重载后缓存正确刷新 |
| **MM-104** | P1 | M | 逐 y 逐方块读取，等价于每区块 16³ 次查询 | 段级剪枝，**已核实的可用 API（附录 D.3.2）**：`LevelChunkSection.hasOnlyAir()`（非空气计数为 0）直接跳过；`maybeHas(Predicate<BlockState>)` 走调色板——**返回 false 即确定无匹配，true 只是"可能"（调色板进入全局模式后恒为 true）**；更省事的是 Forge 新增的 `ChunkAccess.findBlocks(BiPredicate<BlockState,BlockPos>, BiConsumer<BlockPos,BlockState>)`，它内部先用 `maybeHas` 预过滤再做 4096 次扫描 | 同一区域扫描的方块查询次数下降 ≥ 20×；结果集与逐块扫描完全一致（差分测试） |
| **MM-105** ✅ | P1 | L | 每女仆独立扫描 128 列/tick（`MiningTunnelFinder.java:55`），N 个女仆线性叠加 | 全局 `OreScanScheduler`：统一预算、同区块扫描结果共享、多女仆去重 | 10 女仆同时搜索时总查询量 ≤ 单女仆 × 2；TPS ≥ 19.5 |
| **MM-106** | P2 | M | `MiningConfig.java:18-20` 固定"上 3 下 48"，与地形无关；TLM 侧本有 `IMaidTask.searchRadius()/searchDimension()/VERTICAL_SEARCH_RANGE` 约定未被使用 | 搜索体改为可配置形状（球/圆柱/盒），并覆写 `searchRadius`/`searchDimension` 与 TLM 的居家/限制语义对齐（见 MM-606）；支持"跟随主人所在层""按矿种分布层优先" | 配置生效；搜索范围可视化指令能画出范围 |
| **MM-107** | P2 | L | 每轮全量重扫，挖 1 个矿重扫 1 次。**核实约束（附录 D.3.9）：Forge 47.4.22 没有任何"所有方块变更"事件**——`Level.setBlock` 不发事件，`LevelChunkSection#setBlockState` 无钩子，唯一的 chunk 级标记是 `ChunkAccess#setUnsaved`（仅用于存档脏标记），`IForgeBlockState#onBlockStateChange` 只通知被放置方块自己的类。另外 `ChunkEvent.Load` 的 javadoc 明确警告：**它可能在区块晋升到 `ChunkStatus.FULL` 之前触发，贸然操作世界会导致区块加载死锁** | 区块级矿石索引 + 脏标记**增量**更新，但必须建立在"索引只是加速、候选点必须二次校验"的前提上（因为无法感知所有变更）；`ChunkEvent.Load` 里只做登记，实际扫描延后到后续 tick | 重复扫描同一区域第二次的耗时 ≤ 首次的 10%；索引与实际世界不一致时不会漏挖/错挖（差分测试） |
| **MM-108** ✅ | P1 | M | `MiningValidator.java:87-109`：`targetOreName` 只认 `raw_*` 与 `*_ore`。`diamond`/`coal`/`redstone`/`quartz` 返回 null → **静默变成"挖全部"**；`raw_copper_block` → 目标 "copper_block" → **永远匹配不到**。**第六轮实机确认**：77 次锁定全部 `target=all`，过滤完全失效 | **已实现**：多策略解析（命名模式 `raw_*`/`*_ore`/`deepslate_*`/`*_ingot`/`*_gem`/`*_dust`/`*_nugget`/`*_block` → 别名表 `lapis_lazuli→lapis`、`netherite_scrap→ancient_debris`、`quartz→nether_quartz` → 物品名本身）；方块侧同样支持 `forge/c:ores/<mat>` 标签兜底；解析结果与"世界里真实存在的矿石材料集合"核对，**核对失败时打 WARN 并按挖全部处理**（不再静默）；扫描热路径加了 ItemStack 级缓存 | **11 种物品的解析 + 4 条匹配断言已由 GameTest 自动验证**（含旧 bug 用例 `raw_copper_block`、`diamond`） |
| **MM-109** ✅ | P1 | M | `MiningValidator.java:47-48` 兜底只认路径以 `_ore` 结尾 → `ancient_debris` 等漏判；mod 矿石的可见性完全依赖是否恰好进了三大标签 | **已实现（标签驱动 + 缓存）**：识别顺序 = `minecraft:ores`/`forge:ores`/`c:ores` → **任意 `<ns>:ores/<材料>` 子标签** → 显式覆盖表（`ancient_debris`）→ `_ore` 命名兜底；材料名同样可由 `ores/<材料>` 标签反推。结果缓存成 `Set<Block>`/`Map<Block,String>`，热路径只剩一次查表（顺带完成 MM-103 的一半） | **mod 矿石（镍/铝/铅/锡…）只要遵循 `forge:ores/<材料>` 约定就自动被识别与定向，无需为任何 mod 写代码**；`ancient_debris` 经 Forge 的 `forge:ores/netherite_scrap` 约定可被 `netherite_scrap` 定向 —— 均有 GameTest 覆盖 |
| **MM-110** 🟡 | P1 | M | 规则在 `MiningValidator.java:171-183`，调用点 `:109-115`：矿石 3×3×3 内出现基岩即放弃。【测】四次会话共丢弃 27 个 y=-59~-63 的矿（37.5%）。**根因见 §1.5-A：不是"这个矿挖不到"，而是女仆会被基岩卡住**——`breakBlock` 失败不分类 → 对基岩空转 ~120 tick → `dy<=-2` 的恢复动作 `tryClimb` 向上传送并清零 `stuckCount` → 循环上爬。**第五轮实机反馈：关掉规则后会撞同一面基岩墙反复放弃（2.5 秒 4 次），故规则已重新开启** | **两段式**：① 止血——`SKIP_NEAR_BEDROCK` 按实机反馈**重新默认开启**（见 §1.5-A 第五轮），同时保留 `breakBlock` 的**失败分类**（`UNBREAKABLE`/`FLUID`/`PROTECTED`/`TEMPORARY`）与"墙"集合——它对**非基岩**的墙（黑曜石/被保护方块）依然有效；② 根治——由 MM-202 的规划器把"墙"作为不可通行代价绕开 + MM-306 的净 Y 漂移检测生效后，**删除 `isNearBedrock` 这条规则** | 规则开启期间：深层矿区不再出现碎裂循环（当前状态）；规划器落地并删除规则后：基岩旁的矿可挖且丢弃率 ≤ 5% |
| **MM-111** ✅ | P2 | S | `MiningTunnelBehavior.java:51` `failedTargets` 为无界 `HashSet`，切任务/重载即丢 | 有界 LRU + TTL 黑名单，随女仆 NBT 持久化（见 MM-602） | 内存上限固定（如 512 条）；重载存档后不再撞同一面墙 |

### M2 决策层：目标选择与路线规划

| 编号 | P | W | 现状证据 | 优化目标 | 验收标准 |
|------|---|---|---------|---------|---------|
| **MM-201** | P1 | L | `MiningTunnelFinder.java:62-77`：按"水平距离最近的列"排序，列内取**最上面**的第一个命中 → 既不是三维最近，也完全不考虑硬度/危险 | 目标评分函数：`score = 价值权重 − (三维距离·w1 + 预计挖掘量·w2 + 危险·w3 + 历史失败惩罚·w4)`，取最优解 | 单测：给定构造场景，选出的目标与期望一致；实测平均到达方块数下降 ≥ 20% |
| **MM-202** | P1 | L | 完全无可达性判定：会为了一个被封死的矿挖 48 格才发现到不了 | 有界 Dijkstra/A*（预算：节点数/扩展距离上限）先给出可行性；不可达直接进入黑名单 | 不可达目标在 ≤ 200 次节点扩展内被判定；不再出现"长途挖穿后放弃"的日志 |
| **MM-203** | P1 | M | `MiningTunnelBehavior.java:302` 挖完立刻 `toSearch()` → 矿脉每个矿都触发一次全量重扫 | 挖到矿后先在 26 邻域内找同类矿（矿脉延续），脉内挖完再重新搜索 | 一个 4 连钻石矿的扫描次数从 4 次降为 1 次；实战效率提升可测 |
| **MM-204** | P2 | M | 无任何单目标预算；实测最坏间隔 41 s【测】 | 单目标预算：最大耗时 / 最大破坏方块数 / 最大耗材，超限放弃并记账（原因可查） | 无任何单目标耗时 > 20 s；放弃原因写入统计（MM-805） |
| **MM-205** | P2 | M | 无协调：两个女仆会挖同一条隧道，甚至互相埋住 | 目标软预约（claim）+ 超时释放；同矿只由一名女仆认领 | 10 女仆场景下重复隧道率 ≤ 5% |
| **MM-206** | P2 | L | `MiningTunnelBehavior.java:180-200` 正下方目标一律 1×1 直下挖；上行则依赖 1 格宽竖井里的寻路跳步（§1.5-B） | 挖掘模式策略：**阶梯巷道** / 竖井+分支 / 直达，按深度、地形与危险图选择；默认禁止 1×1 直下。阶梯巷道同时是**上下行的天然解法**（玩家就是这么走的）：既不需要在 1 格宽竖井里依赖寻路跳步，也天然避免坠落 | 深目标使用阶梯巷道；不再出现"直下挖穿到洞穴坠落"；上行不再依赖竖井跳步 |
| **MM-207** | P2 | M | 无价值概念：`#minecraft:ores` 内所有矿等价（除副手过滤外） | 可配置矿种价值/优先级表，支持"只挖有价值的""先钻石后煤" | 配置后行为可观察；默认表合理 |

### M3 执行层：挖掘与移动

| 编号 | P | W | 现状证据 | 优化目标 | 验收标准 |
|------|---|---|---------|---------|---------|
| **MM-301** | P1 | M | `:317` `maid.destroyBlock(pos)` 语义已核实（附录 D.2/D.3）：内部经 `canDestroyBlock` → `Block.canEntityDestroy`，而 Forge 的该默认实现转入 **`ForgeHooks.canEntityDestroy` = `getMobGriefingEvent(游戏规则 mobGriefing) && state.canEntityDestroy && onEntityDestroyBlock(LivingDestroyBlockEvent)`** ⇒ 保护 mod 与 `mobGriefing` **都能否决**。**但**：① 不触发 `BlockEvent.BreakEvent`（该事件只在玩家路径，`ForgeHooks.onBlockBreakEvent` 仅被 `ServerPlayerGameMode` 调用），而绝大多数领地/保护 mod 挂的是玩家事件；② 没有"不挖容器/玩家建筑"的概念；③ **`mobGriefing=false` 时全部破坏都会失败**，当前代码把它当"挖不动"累加 `stuckCount` → 把所有矿拉黑 → 女仆原地发呆且**零提示**（退化路径） | 保留 `destroyBlock` 通道并补齐：可配置"不破坏容器/玩家放置的方块/贵重方块"；被否决时换目标而非死循环；**检测到"整体不可破坏"（如 mobGriefing=false）时给出明确 i18n 提示并暂停任务**。若需要覆盖绝大多数保护 mod，可选用 FakePlayer 走玩家破坏路径（见 MM-303 备注，注意副作用） | 保护 mod 否决后女仆换目标；`mobGriefing=false` 时有明确提示而非静默发呆；默认不挖容器与玩家建筑 |
| **MM-302** | **P0** | M | `:440` 直接 `level.setBlock(pos, state, 3)`：**任何事件都不会触发**，也没有 `BlockItem#place` 的方块自身逻辑（朝向/含水/可替换判定） | 放置走 `BlockItem#place` / `BlockPlaceContext` 语义，使其进入 Forge 的可拦截路径 | 放置可被 mod 拦截；放置失败时不消耗物品 |
| **MM-303** ✅ | P1 | L | `:289,317` 瞬时破坏（`destroyBlock`）——`getDestroySpeed` 只用于"是否 <50"这一条硬编码规则。**核实补充（附录 D.3.4）**：`Level#destroyBlock`（以及 TLM 手抄版）求掉落时传的是 **`ItemStack.EMPTY` 作为工具**（`Block.getDrops(..., toolStack)`），⇒ **时运/精准采集永远不生效**；同时"需要正确工具"的门槛（`BlockState.canHarvestBlock`）只在玩家路径检查，TLM 路径完全跳过 ⇒ 本 mod 自己的 `isCorrectToolForDrops` 校验是**唯一**的等级门槛 | 引入挖掘时间模型：按方块硬度与工具挖掘速度计算所需 tick，进度可视化（裂纹/音效/挥臂）；**并用真实工具栈自行计算掉落**（`Block.getDrops(state, level, pos, be, maid, realTool)` → `destroyBlock(pos, false)` 无掉落破坏 → 自行入包），使时运/精准采集/效率生效 | 石头用铁镐的破坏耗时与玩家体感一致（±30%）；带时运的镐产出正确数量；精准采集能拿到原矿方块 |
| **MM-303b** ✅ | P2 | M | **备选方案（一次性解决 MM-301/303/308）**：用 `FakePlayerFactory.getMinecraft(serverLevel)` 走**玩家破坏路径**——`ForgeHooks.onBlockBreakEvent`（几乎全部保护 mod 都能拦截）→ `ItemStack.mineBlock`（自动扣耐久）→ `Block.playerDestroy`（自动用真实工具算掉落）。代价：假玩家会带来统计/进度/`mayInteract` 出生点保护等副作用，且 `FakePlayer` 语义需谨慎 | 作为可配置的"兼容模式"实现，默认关闭；与 MM-301 的方案对比后二选一 | 开启后领地 mod 全部生效；工具耐久、时运、掉落与玩家破坏一致；无统计刷屏 |
| **MM-304** 🟡 | P1 | M | `:234-259` 额外清空 `feet+(dx,2,dz)`；`tryClimb:365-372` 还要求 `stepPos+1/+2` 全通 ⇒ 挖出 **3 格高**隧道。**根因见 §1.5-B（已修正版）：这是为掩盖"上行会卡住"而做的补偿**；第二轮实机进一步确认——**2 格高巷道里跳跃被天花板截断在 0.5 格，1 格台阶物理上迈不上去** | 净高按实体碰撞箱计算（通常 2）：水平/向下 **1×2**；**仅在上台阶那一格多挖 1 格**形成局部凹坑（`JUMP_HEADROOM`）；通行判定用 `level.noCollision` 而非"几格是空气" | 水平段截面 1×2；上台阶处允许 1×3 局部凹坑；每格前进的破坏方块数下降 ≥ 30%；实机：上行不再依赖传送兜底 |
| **MM-305** ✅ | **P1** | M | `:356,384` 用 `maid.setPos()` 传送；**上行主路径是 `:389` 的 `setWalkAndLookTargetMemories`（依赖原版寻路在 1 格宽竖井里跳一步），失败时完全没有兜底 ⇒ 这就是 §1.5-B 的卡住来源**。传送本身也不做碰撞解算、不清 `fallDistance`（附录 D.3.8） | 抽出 `VerticalMover` 原语，语义严格按"玩家怎么上去"：① 目标位置的包围盒必须通过 `level.noCollision(maid, bb)` 校验（失败则先挖开缺的那格）；② 先真实跳跃（`maid.getJumpControl().jump()` + 走位目标）；③ 连续 N tick 无位移才允许 `setPos` 兜底，兜底前再校验一次碰撞、兜底后 `resetFallDistance()`；④ 不允许在未通过校验时传送 | 正常上行不调用 `setPos`；1 格宽竖井上行永不卡死；卡进方块的次数 = 0；不再需要靠加高隧道来兜底 |
| **MM-306** 🟡 | P1 | M | `:182,217,238,250,261` `stuckCount` 跨分支共享、重置点不一致；`climbFlip`(:54,467) 是死字段；**更关键的是它在"向上爬"时被清零（`:188`）⇒ 完全识别不出 §1.5-A 的无限上爬** | 统一 `ProgressTracker`：以「与目标的距离变化 / **净 Y 漂移** / 破坏成功次数 / 耗时」四要素判定"无进展"；**当净 Y 漂移与目标方向持续反向（为了够到下方目标却在不断上升）即判定失败并放弃该目标**；恢复动作要有序列与上限（换路 → 换高度 → 换目标） | 41 s 级卡顿消失；顶墙空转/上爬循环在 ≤ 3 秒内被识别并换目标 |
| **MM-307** | P2 | M | `:313-323` 一切方块都用主手镐挖，土/沙也扣镐耐久 | 工具匹配：按方块选择镐/铲/锹，减少耐久浪费 | 挖 100 格泥土的镐耐久消耗 ≤ 10（当前 100） |
| **MM-308** ✅ | P2 | S | `:290,318` `tool.hurtAndBreak(1, maid, item -> {})`。**核实（附录 D.3.3）**：TLM 的 `destroyBlock` 完全不碰工具耐久，所以手动扣耐久**必需且正确**；而 `hurtAndBreak` 在回调之外自己会 `shrink(1)` + `setDamageValue(0)` ⇒ **镐子本来就正常消耗、不会卡在 maxDamage 状态**（这是常见误解，不要"修"它，回归测试里应断言"破坏 N 个方块后耐久正好减 N、耗尽后消失"）。空回调唯一造成的差别是：没有破坏音效/动画广播（`broadcastBreakEvent`）与玩家统计 | 破损回调改为 `e -> e.broadcastBreakEvent(hand)`（与 TLM 的 `TaskSnow`/`TaskMelon`、vanilla `DiggerItem` 一致），补齐音效/动画/统计；**不要去改消耗逻辑** | 耐久耗尽时镐子消失、有破坏音效、触发换镐；每破坏 1 方块仅扣 1 点耐久 |
| **MM-309** | P2 | S | `:393-395` 以女仆 1.5 格 AABB 轮询掉落物。**已核实**：主掉落由 `destroyBlock` 直接入包，本方法实际只处理**溢出落地的部分**，而 AABB 以女仆为中心、远的挖点收不到 | 以"刚破坏的方块坐标"为中心回收溢出掉落；或干脆在背包将满时进入 UNLOAD（MM-504）从源头避免溢出 | 100% 掉落被收集（无残留 `ItemEntity`） |
| **MM-310** | P3 | M | 隧道永久留在地形里（1×1 竖井 + 长巷道） | 可选"回填/恢复"策略（用碎石回填、任务结束回填） | 开启回填后，任务结束地形变化可配置为最小 |
| **MM-311** | P3 | M | 隧道黑暗 → 刷怪风险；TLM 侧已有可复用的照明能力（`entity/task/TaskTorch`、`brain/task/MaidTorchPlaceTask`） | 可选沿隧道照明，优先复用 TLM 的放火把行为（消耗背包火把） | 开启后隧道光照 ≥ 8 |

### M4 安全层：危险规避

| 编号 | P | W | 现状证据 | 优化目标 | 验收标准 |
|------|---|---|---------|---------|---------|
| **MM-401** ✅ | P0 | M | `:308-311` `isPassable` 把**任何流体**当作可通行 → 女仆会主动走进岩浆 | 危险表：岩浆/火/岩浆块/细雪/仙人掌默认**不可进入**；可配置"允许游泳（水）" | GameTest：女仆不会进入岩浆；走进岩浆导致死亡的用例 = 0 |
| **MM-402** | P0 | M | `:180-199` 向下挖穿后无落点探测；挖入洞穴/峡谷会自由落体。**核实补充（附录 D.3.8）**：`setPos`/`moveTo`/`teleportTo` **都不清除 `fallDistance`**，而 `checkFallDamage` 只在 `Entity.move` 里调用 ⇒ 攀爬/传送期间累计的落差会在下一次落地时**一次性结算**，坠落伤害风险比表面更高 | 下降前探测落点（射线/逐格），落差 > N 格时改为阶梯下降或直接拒绝该目标；兜底传送后 `resetFallDistance()`；可选缓降/水桶方案 | 无坠落伤害致死；落差 > 3 的直降路径被替换；传送后不结算历史落差 |
| **MM-403** | P1 | M | 1.20.1 地下含水层常见，破入即淹；`:310` 把水当可通行 | 水处理策略：封堵（方块）/绕行/放弃目标；窒息检测（氧气）触发上浮或撤退 | 隧道破入含水层不导致女仆死亡；策略可配置 |
| **MM-404** | P1 | M | 沙砾/沙子/混凝土粉末会在头顶落下造成窒息 | 下落方块处理：先支护再挖，或改道 | GameTest：沙砾柱下方挖矿不致死 |
| **MM-405** | P2 | M | 破入刷怪笼/黑暗洞穴无反应 | 危险响应：照明、撤退、通知主人 | 配置开启后不再在怪群中硬挖 |
| **MM-406** | P1 | M | 无逃跑/自愈/低血撤退 | 低血量阈值撤退回家 + 可选自愈/呼叫主人 | 血量 < 阈值时进入 RETREAT 并成功返回 |
| **MM-407** | P2 | S | `MiningTunnelFinder.java:66-68` 只在**搜索**时检查 `hasRestriction()`；DIG 移动阶段无约束 | 限制区域在整条路径上生效（不能挖出家门范围） | 居家女仆不会挖出限制半径；越界日志 = 0 |

### M5 物品层：工具 / 建材 / 拾取 / 收纳

| 编号 | P | W | 现状证据 | 优化目标 | 验收标准 |
|------|---|---|---------|---------|---------|
| **MM-501** ✅ | **P0** | S | `:400-412`：从 `ItemEntity` 复制到背包后**只减少本地 `left`**，从不 `setCount`/`setItem` 回写实体；仅当 `left == 0` 才 `discard()`。**背包空间不足时 → 物品同时存在于背包与地面 = 复制**。此外 `inv.setStackInSlot(...)` 直接写 `getMaidInv()`（36 格背包），**绕过 `EntityMaid.canInsertItem`**（该检查含 `MaidConfig.MAID_BACKPACK_BLACKLIST` 与 `canFitInsideContainerItems`），可把 TLM 禁止入包的物品（如潜影盒）塞进去（附录 D.2） | 原子转移：写入多少就从实体扣除多少，剩余量回写；背包满则完全不动实体。写入统一经 `ItemHandlerHelper.insertItemStacked(maid.getAvailableInv(false), ...)`，不再直接 `setStackInSlot` | **GameTest 物品守恒断言**（MM-1003）：背包只剩 1 格且地面有 64 个方块时，总量仍为 64；黑名单物品（潜影盒）不会被插入 |
| **MM-502** ✅ | **P0** | M | `:419-427` 取**36 格背包**（`getMaidInv()`，也就是玩家存放物品的地方）中第一个 `solid` 的 `BlockItem` 垫脚 → 会消耗钻石块、矿石块、主人存放的建材；且未排除槽位 5（`MaidBackpackHandler.BACKPACK_ITEM_SLOT == 5` 是女仆"背包展示位"，见附录 D.2） | 建材白名单 + 估值排序（优先圆石/泥土/下界岩等廉价方块）+ 硬黑名单（矿石、贵重块、容器、食物、展示位物品、副手过滤物）；只在 `getAvailableBackpackInv()` 范围内取用 | 单测：背包含钻石块与圆石时，消耗圆石；黑名单物品与槽位 5 永不被消耗 |
| **MM-503** | P1 | M | **此条已按核实结果改写**：TLM 的 `destroyBlock` 会把战利品**直接放进女仆背包**（`dropResourcesToMaidInv` → `insertItemStacked(getAvailableInv(false))`，溢出才 `Block.popResource` 落地），所以"粗铁/钻石被忽略"**不成立**——真正的收益早已入包。自定义 `pickupDrops` 只是"溢出物收集器"，但它 **绕过了 `maid.isPickup()`（TLM 的"拾物模式"开关）与 `MaidPickupEvent`**，并用裸 `setStackInSlot` 绕过入库校验 | 删除自实现拾取，或改为在 TLM 拾取链内补充（尊重 `isPickup()` 与 `MaidPickupEvent`），仅处理本 mod 造成的溢出掉落 | 关闭"拾物模式"后女仆不主动捡东西；监听 `MaidPickupEvent` 的 mod 能拦截本 mod 的拾取；无残留 `ItemEntity` |
| **MM-504** | P1 | L | 无收纳逻辑：背包满后继续挖，TLM 的溢出掉落会落地/被岩浆烧掉。可用容量由背包类型决定：`getAvailableInv(false)` 只取 `getMaidBackpackType().getAvailableMaxContainerIndex()` 个槽位，**不是 36**（附录 D.2） | 背包满 → UNLOAD 状态：回家/存入指定容器/交给主人 → 返回继续；容量判定用 `getAvailableInv(false)`/背包类型的可用槽数 | 连续运行 30 分钟无"背包满导致掉落丢失" |
| **MM-505** ✅ | P1 | M | `:473-485` 手写换镐：遍历全部 36 格（含展示位 5）、自己实现 swap。TLM 已有**语义完全正确**的工具方法（附录 D.2）：`TaskEquipUtil.tryEquipFromBackpack(maid, pred)`（手部已匹配则直接返回 true；从 `getAvailableBackpackInv()` 取整叠并与主手**交换**，无复制）与 `TaskEquipUtil.putMainHandBack(maid)`；但都不看等级/附魔/耐久 | `ToolManager`：按（等级 > 附魔 > 剩余耐久）选最优镐，用 `putMainHandBack` + 谓词精确匹配（`findStackSlot` 只返回首个匹配，谓词需含耐久判定）；剩余耐久低于阈值提前更换；无镐时明确报告 | 同时有木镐与钻石镐时用钻石镐；镐将坏时自动换新；换镐路径零复制（物品守恒测试覆盖） |
| **MM-506** | P2 | S | 副手过滤物品与食物未受保护（垫脚选择只看"是否固体 BlockItem"）；TLM 的进食走 `MaidWorkMealTask`，会遍历主手/副手（`HandUtils.NATIVE_HANDS`）找 `IMaidMeal` 可吃的物品（附录 D.2）——挖矿行为若长期占用主手/副手，会影响进食判定 | 副手过滤物、食物、容器类永不作为建材/不被消耗；确认挖矿时主手必须持镐、副手保留过滤物不会阻断进食 | 单测覆盖；女仆在挖矿期间仍能正常进食 |
| **MM-507** | P2 | S | `:393-395` 会拾取**玩家丢弃**的方块类物品，且不看 `maid.isPickup()`（TLM 的拾物模式，`isPickup()`/`setPickup(b)`，附录 D.2） | 只拾取自己造成的掉落（记录挖点）或按配置允许范围；始终尊重 `isPickup()` | 玩家丢出的方块不被捡走（默认）；关闭拾物模式后不拾取 |
| **MM-508** | P3 | S | 矿石经验球被完全忽略 | TLM 已有经验拾取链路（`MaidPickupEvent.ExperienceResult`），本 mod 不应重复实现、也不应阻断它 | 复用 TLM 后经验正常入账；不再单列自实现 |
| **MM-509** ✅ | **P0** | S | **新增（核实后确立）**：`getMaidInv()` 返回的是 **36 格背包 `MaidBackpackHandler`**，其中 `BACKPACK_ITEM_SLOT == 5` 是女仆的**背包展示位**（`onContentsChanged` 会在该槽变动时改写女仆展示的物品）。当前代码在 `pickupDrops`/`placeStepBlock`/`equipPickaxe` 中按 `0..getSlots()` 无差别读写，**可能消耗或覆盖展示位物品**，也可能写入玩家存放的任意物品（附录 D.2） | 所有背包写操作集中到一个封装（`inv/*`）：只用 `getAvailableBackpackInv()`/`getAvailableInv(false)`，显式排除 `BACKPACK_ITEM_SLOT`，插入走 `ItemHandlerHelper.insertItemStacked`（尊重 `canInsertItem`） | 单测：槽位 5 的物品在任何路径下都不被读取为耗材、不被覆盖；展示物品保持不变 |

### M6 会话层：状态机与持久化

| 编号 | P | W | 现状证据 | 优化目标 | 验收标准 |
|------|---|---|---------|---------|---------|
| **MM-601** ✅ | P1 | L | `MiningTunnelBehavior.java` 486 行单体类，六个职责混在一起 | 拆分为 会话状态机 + 感知/决策/执行/物品/安全 组件（见 §4），单类 ≤ 250 行，纯逻辑可单测 | 拆分后核心状态迁移可被单元测试直接驱动（无需启动游戏） |
| **MM-602** | P1 | M | `:46-54` 全部状态在内存；`start()` 清空（`:66-70`）→ 切任务/重载即失忆 | 关键状态持久化：当前目标、黑名单、隧道进度、会话统计；**优先使用 TLM 提供的任务数据存储**（jar 内含 `api/entity/data/TaskDataKey`、`entity/data/TaskDataRegister`、`MaidTaskDataMaps`），避免自造 NBT 格式 | 存档保存/重载后，女仆继续原目标；黑名单保留 |
| **MM-603** | P1 | M | `:56-58,61-63` duration=`Integer.MAX_VALUE`、`canStillUse` 恒 true。**核实结论（附录 D.2）：核心行为并没有被压制**——`createBrainTasks` 的返回值只进入 `Activity.WORK`，TLM 还会追加 `MaidBegTask(6)`/`MaidWorkMealTask(7)`/`MaidStealEdible*(8)`/look-and-random-walk(20)/`MaidUpdateActivityFromSchedule(99)`；进食、跟随、拾取、自愈、换气、灭火、空闲、坐下都在 CORE/IDLE/REST 等独立活动注册，与本行为并行运行。**真正的问题是移动指令竞争**：CORE 的 `MaidFollowOwnerTask(0.5F,2)`、`MaidPanicTask`、`MaidSwimJumpTask`、`MaidClimbTask`、`MaidBreathAirTask` 与本行为都会写 `WALK_TARGET`（`BehaviorUtils.setWalkAndLookTargetMemories` 同时写 `walk_target` 与 `look_target`），而 vanilla `Brain.startEachNonRunningBehavior` **只按优先级 TreeMap 顺序 `tryStart`、没有任何互斥门控**（已核实，附录 D.3.7），`tickEachRunningBehavior` 会逐个体 tick ⇒ **同活动不同优先级的行为可以同时 RUNNING**，本行为每 6 tick 覆写一次移动意图 | 明确与 CORE 行为的协作：当跟随主人/恐慌/换气等安全行为生效时让出移动控制（或统一经 `TARGET_POS` 约定协调），避免"一边被拽向主人、一边往隧道里走"的抖动；挖矿行为保持在工作活动中，**不要**试图独占大脑（`IExtraMaidBrain` 是全局追加、对所有女仆生效，不适用于任务专属行为） | 主人远离/被攻击/水下缺氧时挖矿行为不与安全行为对抗；女仆无来回抖动 |
| **MM-604** ✅ | P2 | S | `:72-76` `stop()` 只清 `WALK_TARGET` + 停止导航；工具是"借"到主手的（`equipPickaxe`），停止时未归还 | 停止时完整清理：用 `TaskEquipUtil.putMainHandBack(maid)` 归还镐子、释放目标预约、持久化进度、上报统计（附录 D.2 有现成 API） | 任务切换 100 次无状态泄漏（内存/物品都守恒） |
| **MM-605** | P2 | S | 无会话统计 | 会话统计：挖矿数、耗时、耗材、损坏工具、放弃原因分布 | `/maidmining stats` 可查 |
| **MM-606** | P2 | S | **新增**：TLM 的 `IMaidTask` 提供 `searchRadius()`/`searchDimension()`/`VERTICAL_SEARCH_RANGE` 约定（默认 `getRestrictRadius()`，且居家模式下把搜索盒夹紧到限制区域），本 mod 完全自造 48 格半径与垂直范围，两边语义脱节（附录 D.2） | 覆写 `searchRadius(maid)`/`searchDimension(maid)`，让搜索范围与 TLM 的居家/限制语义一致（与 MM-106/MM-407 合并实现） | 居家女仆的搜索范围与 `getRestrictRadius()` 一致；非居家使用配置半径 |

### M7 配置层

| 编号 | P | W | 现状证据 | 优化目标 | 验收标准 |
|------|---|---|---------|---------|---------|
| **MM-701** ✅ | P1 | L | `MiningConfig.java` 全部 `public static final` → 玩家完全无法调整 | `ForgeConfigSpec`（SERVER 类型）+ 运行时快照；改配置即时生效（或明确要求重载）【待核实 ForgeConfigSpec 在本版本的 API】 | 配置文件生成、可改、生效；GameTest 断言配置边界值 |
| **MM-702** ✅ | P1 | M | 参数散落在 11 个常量里，语义残缺（如没有危险/物品/安全相关配置） | 配置分组：搜索 / 移动 / 挖掘 / 安全 / 物品 / 性能 / 兼容；每项含注释、默认值、范围 | 配置项 ≥ 35（草案见附录 B） |
| **MM-703** | P2 | M | 无每女仆配置。**核实补充（附录 D.2）：TLM 已提供挂载点** —— `IMaidTask.getTaskConfigGuiProvider(maid)`（默认返回一个空配置界面）与 `getTaskInfoGuiProvider(maid)` | 支持每女仆覆盖（搜索半径/危险策略/建材白名单），挂到 `getTaskConfigGuiProvider` 提供的界面上；服务器级配置作默认值 | 两只女仆可设置不同挖矿范围；界面可从 TLM 女仆 GUI 进入 |
| **MM-704** ✅ | P1 | S | 默认值激进（会拆家、会进岩浆、直下挖） | 默认值保守：不挖玩家方块、不进岩浆、不直下、垫脚只用廉价方块、日志仅 WARN+ | 新装 mod 默认配置下不会破坏玩家存档体验 |

### M8 观测层：日志 / 指令 / 反馈

| 编号 | P | W | 现状证据 | 优化目标 | 验收标准 |
|------|---|---|---------|---------|---------|
| **MM-801** ✅ | P1 | S | 12 处 `LOGGER.info` 中多数在热路径/按矿触发（`:101,111,119,133,194,220,241,253,293,296,349,454`） | 分级：热路径降为 `debug`，且受配置开关控制；关键事件保持 info | 默认配置下每矿日志 ≤ 1 行（或 0 行）；开启 debug 后有完整轨迹 |
| **MM-802** ✅ | P1 | M | `:100` 硬编码英文 `"I need a pickaxe to mine!"`，每 100 tick 刷屏；搜索不到矿时无任何提示 | 全部改 i18n `Component.translatable`；消息去重（状态变化才说一次）；新增"找不到矿""背包满""放弃了某目标（原因）"等提示 | 语言文件切换后提示跟随；连续 10 分钟无重复刷屏 |
| **MM-803** | P2 | M | 无任何指令 | `/maidmining status|stats|debug|blacklist clear|reload` | 指令可用、权限正确、输出 i18n |
| **MM-804** ✅ | P2 | M | 玩家看不到女仆在做什么。**核实补充（附录 D.2）：TLM 有现成出口** `IMaidTask.getMaidActionSummary()`（默认返回 `getUid().getPath()`），以及 `getTaskInfoGuiProvider(maid)` | 覆写 `getMaidActionSummary()` 返回当前状态（搜索中/前往/挖掘/受阻/收纳，走 i18n）；状态可视化可挂 `getTaskInfoGuiProvider` | 玩家能在女仆界面/提示中看到当前行为与受阻原因 |
| **MM-805** | P1 | M | 无计数器，性能问题只能靠猜 | 计数器：方块查询/tick、扫描轮次、候选矿数、各放弃原因计数、分配次数 | `/maidmining stats` 输出与 §2.2 预算对齐；可做回归断言 |
| **MM-806** | P2 | S | 行为内任何异常都会打断女仆 AI（无 try/catch） | 行为外层异常兜底 + 熔断（连续失败则停用该女仆的挖矿任务并提示） | 注入异常测试不导致 AI 死锁 |

### M9 兼容性与边界

| 编号 | P | W | 现状证据 | 优化目标 | 验收标准 |
|------|---|---|---------|---------|---------|
| **MM-901** ✅ | **P0** | S | `MiningTask.java:9-10,35-42,51-64` 在任务类里引用 `net.minecraft.client.Minecraft` 与 `net.minecraft.client.resources.language.I18n`；`isZh()` 只 `catch (Exception)`（捕不到 `NoClassDefFoundError`/`Error`），而 `I18n.get` 在 `getName()` 中还在 try 之外。**核实补充（附录 D.2）：TLM 的 `IMaidTask.getName()` 默认实现本就是 `Component.translatable("task.<ns>.<path>")`**，所以这个覆写既危险又完全多余 | **删除 `getName()`/`getDescription()` 覆写**，直接使用 TLM 默认实现（key 由 UID 自动生成，正是 `task.maidmining.mining` / `.desc`，且已是可翻译组件） | 专用服务器启动 + 任务名/描述渲染无 `NoClassDefFoundError`；任务类不再引用任何 `net.minecraft.client.*` |
| **MM-902** | P1 | S | 描述有两份事实源：`MiningTask.java:32-33` 的硬编码文案 与 `lang/en_us.json`、`lang/zh_cn.json`，且**不一致**（lang 缺少"需要镐子"）。**核实补充：TLM 的 `getDescription()` 默认返回的是 key 列表 `Lists.newArrayList("task.<ns>.<path>.desc")`，而当前覆写返回的是已翻译文本**，与 TLM 约定相反 | 删除覆写，文案只保留在 lang 文件（单一事实源），并把"需要镐子/范围/副手过滤"信息补全到中英文案 | 文案改动只需改 lang；任务界面中英双语完整 |
| **MM-903** ✅ | P1 | S | `MiningTask.java:66-69` `getConditionDescription` 返回空列表，而 `zh_cn.json:4` 已有未被引用的 `task.maidmining.mining.condition.pickaxe`。**核实补充（附录 D.2）：TLM 的启用条件应实现 `getEnableConditionDesc(maid)`（返回 `List<Pair<String,Predicate<EntityMaid>>>`），GUI 按 `<uid>.condition.<name>` 取名——现有那个闲置 key 正好符合该约定**；另有 `MaidTaskEnableEvent` 可动态追加条件 | 实现 `getEnableConditionDesc`（如"需要镐子""需要背包空间"）并配好 lang 键；必要时提供 `isEnable(maid)` 让没镐子的女仆不显示为可用 | GUI 中能看到条件与是否满足；无镐子时任务置灰或有明确提示 |
| **MM-904** | P2 | M | 无领地/保护 mod 兼容路径 | 见 MM-301/302；并补充"保护 mod 拦截后的行为"（换目标而非卡死） | 联调测试通过 |
| **MM-905** | P2 | S | **已核实（附录 D.2）**：TLM 的 `TaskManager.add()` **不检测重复 UID**（`Map.put` + `List.add`，重复会静默覆盖 map 条目并在 GUI 列表里出现两份）；`init()` 结束后 `TASK_MAP`/`TASK_INDEX` 被替换为 Guava **不可变**副本，之后再 `add()` 会抛 `UnsupportedOperationException`。TLM 自带的 22 个任务 UID 全部在 `touhou_little_maid` 命名空间，**与 `maidmining:mining` 无冲突** | 注册只经 `ILittleMaid.addMaidTask(taskManager)`（当前做法正确）；注册前用 `TaskManager.getTaskMap().containsKey(UID)` 自查并在冲突时告警降级 | 冲突时日志明确；不会静默覆盖他人任务 |
| **MM-906** | P2 | S | `mods.toml:27-31` 只声明 `touhou_little_maid >= 1.5.3`，未实测更高版本 | 版本兼容矩阵与声明范围一致（含 TLM 新版本、Forge 47.x 全系） | 实测矩阵记录在 HANDOFF |
| **MM-907** | P2 | M | 区块卸载/女仆未加载时的行为未定义（`MM-101` 的对偶） | 女仆所在区块卸载时暂停并持久化；恢复后从 MM-602 的状态续跑 | 反复卸载/加载不产生异常与状态错乱 |

### M10 工程化：测试 / 构建 / 发布

| 编号 | P | W | 现状证据 | 优化目标 | 验收标准 |
|------|---|---|---------|---------|---------|
| **MM-1001** | P1 | L | `src/test` 不存在 | 纯逻辑单元测试（扫描器、评分、过滤、转移、配置边界、状态机迁移） | `./gradlew test` 通过；覆盖率 ≥ 70%（核心包） |
| **MM-1002** ✅ | P1 | L | `build.gradle:40,43,46-48` 已配置 GameTest namespace，但无任何 gametest | ≥ 8 个 GameTest 场景（§6.2） | `./gradlew runGameTestServer` 全绿 |
| **MM-1003** ✅ | **P0** | M | 复制漏洞（MM-501）说明当前**完全没有物品守恒测试** | 物品守恒回归测试：所有转移路径（拾取/垫脚/换镐/收纳/任务中断）总量守恒断言 | 该测试在 v1.0.0 代码上**必须失败**（先证明测试有效），修复后通过 |
| **MM-1004** | P1 | M | 无性能度量 | 基准：JFR/Spark 采样 + MM-805 计数器；把 §2.2 预算写成可断言脚本 | 每次发版前跑一次并记录到 §2.6 历史表 |
| **MM-1005** | P2 | M | 无 CI | CI：编译 + 单测 + 静态检查；注意依赖离线缓存（`mcmodsrepo`、`~/.gradle`） | PR 必过 |
| **MM-1006** | P2 | S | 仓库名 `maidming` 与 modid `maidmining` 不一致；`mods.toml` 无 issue/update URL；版本策略未定义 | 发布工程化：版本号语义、changelog 模板、仓库改名、元数据补全 | 元数据完整 |
| **MM-1007** | P2 | S | 文档漂移：`HANDOFF.md:96-99` 说"不要覆写 getName/getDescription"，而 `MiningTask` 覆写了；`MiningConfig` 注释与实际用法有出入（如 `climbFlip` 死字段） | 文档与代码互链、发版时同步；本文档作为优化 backlog 唯一来源 | 文档评审通过 |

---

## 4. 目标架构（To-Be）

### 4.1 包结构

```
com.leyue.maidmining
├── MaidMiningMod                主类：配置注册、事件总线、指令注册
├── MaidExtension                TLM 接入（保持精简）
├── task/MiningTask              TLM 任务定义（只用 i18n key，零客户端类）
├── session/
│   ├── MiningSession            每 tick 推进的会话状态机（原 MiningTunnelBehavior 的脑）
│   ├── MiningState              IDLE / SEARCH / PLAN / TRAVEL / MINE / COLLECT / UNLOAD / RETREAT
│   └── MiningBlackboard         单女仆可变状态（目标/预算/统计/黑名单），可 NBT 序列化
├── sense/
│   ├── OreScanScheduler         全局分帧预算与多女仆去重（MM-105）
│   ├── OreScanner               段级剪枝扫描（MM-101/102/104）
│   ├── OreIndex                 区块矿石索引 + 脏标记（MM-107）
│   └── OreRules                 矿石/工具/副手过滤规则 + 缓存（MM-103/108/109）
├── plan/
│   ├── TargetScorer             目标评分（MM-201/207）
│   ├── ReachabilityProbe        有界 Dijkstra/A*（MM-202）
│   └── TunnelPlan               阶梯/竖井/直达 方案（MM-206）
├── act/
│   ├── WorldActor               世界改动的唯一出口：破坏/放置**返回分类结果**（MM-110/301/302）
│   ├── DigExecutor              破坏：时间模型 + 工具匹配 + 耐久 + 真实工具栈掉落（MM-303/307/308）
│   ├── VerticalMover            垂直移动原语：净高=碰撞箱、跳跃优先、传送兜底（含碰撞校验与落差重置）（MM-304/305）
│   ├── Scaffolder               垫脚：白名单 + 碰撞 + 多实体避让（MM-502/MM-205）
│   ├── WallMap                  本会话"不可破坏方块"集合，供规划器当墙绕开（MM-110）
│   └── ProgressTracker          无进展判定：距离 / 净 Y 漂移 / 破坏数 / 耗时（MM-306）
├── safe/
│   ├── HazardMap                危险图（岩浆/水/坠落/沙砾/怪）（MM-401~405）
│   └── SafetyPolicy             策略表与撤退决策（MM-406/407）
├── inv/
│   ├── ToolManager              最优镐选择与更换（MM-505）
│   ├── ScaffoldPolicy           建材估值（MM-502/506）
│   ├── LootCollector            原子拾取（MM-501/503/507）
│   └── UnloadService            收纳与回家（MM-504）
├── diag/
│   ├── MiningStats              计数器（MM-805）
│   ├── MiningDebug              指令与可视化（MM-803/804）
│   └── LogGate                  日志分级开关（MM-801）
└── cfg/MiningConfigSpec         ForgeConfigSpec + 运行时快照（MM-701/702/703/704）
```

### 4.2 状态机（目标）

```
                 ┌──────────────────────────── 无矿/超时/受限 ────────────────┐
                 ▼                                                            │
  IDLE ──启用任务──► SEARCH ──找到候选──► PLAN ──可达──► TRAVEL ──贴近──► MINE │
                      ▲                    │                  │            │  │
                      │                    └─不可达/超预算─────┼────────────┘  │
                      │                                        │               │
                      │                             背包满 ──► UNLOAD ──► SEARCH│
                      │                                        │               │
                      └──────────── 危险/低血 ──► RETREAT ◄────┘               │
                                                 （回家/脱离危险后回 SEARCH）◄──┘
```

规则：
- 每次状态迁移都必须**可解释**（记录原因），便于 MM-805 统计。
- `MINE` 完成后先做**矿脉延续检查**（MM-203），再回 `SEARCH`。
- `TRAVEL` 内部分解为"逐格推进"，但每格推进都经过 `HazardMap` 与 `ProgressTracker`。

### 4.3 关键接口草案

```java
/** 感知：一次有预算的扫描，返回候选（不是最终决策） */
public interface OreScanner {
    List<OreCandidate> scan(MiningContext ctx, int budgetBlocks);
}

public record OreCandidate(BlockPos pos, Block block, int estimatedCost, double value) {}

/** 决策：可替换的策略，便于单测与后续换算法 */
public interface TargetSelector {
    Optional<TargetPlan> select(MiningContext ctx, List<OreCandidate> candidates);
}

/** 执行：所有世界改动的唯一出口（事件、保护、时间模型、耐久都在这里） */
public enum BreakResult { SUCCESS, UNBREAKABLE, FLUID, PROTECTED, TEMPORARY_FAILURE }

public interface WorldActor {
    /** 失败必须分类——§1.5-A 的根因就是它只返回 boolean，导致对基岩空转 6 秒 */
    BreakResult breakBlock(BlockPos pos, BreakReason reason);
    boolean placeBlock(BlockPos pos, ItemStack stack);
}

/** 垂直移动原语：语义严格对齐"玩家怎么上去"，而不是"把女仆瞬移上去" */
public interface VerticalMover {
    /** dest 必须通过 level.noCollision(maid, 目标包围盒)；跳跃优先，传送是兜底且会重置落差 */
    boolean stepUp(BlockPos dest);
    boolean stepDown(BlockPos dest);
    /** 净高由实体碰撞箱推导，禁止硬编码 */
    int requiredHeight(EntityMaid maid);
}

/** 安全：任何移动/挖掘前的统一闸门 */
public interface SafetyPolicy {
    Verdict check(MiningContext ctx, Action action); // ALLOW / DELAY / REROUTE / ABORT
}

/** 统计：所有可观测量的唯一出口 */
public interface MiningStats {
    void increment(Counter counter, long delta);
}
```

### 4.4 设计原则（写进代码评审清单）

1. **唯一出口原则**：世界改动只能通过 `WorldActor`；物品转移只能通过 `inv/*`。禁止在业务代码里直接 `level.setBlock` / `inv.setStackInSlot` / `drop.discard()`。
2. **零分配热路径**：每 tick 路径上禁止 `new BlockPos`、`new ArrayList`、字符串拼接。
3. **可解释性**：任何"放弃/等待/重试"都必须带原因枚举，进入统计。
4. **保守默认**：涉及玩家资产与女仆性命的行为，默认值必须是最保守的。
5. **可测性**：算法与 I/O 分离——`TargetSelector`/`ReachabilityProbe`/`ScaffoldPolicy` 必须能在无世界的情况下单测。
6. **复用要符合语义，不是"能调就调"**：`TaskEquipUtil`（交换工具）、`TaskDataKey`（持久化）、`getEnableConditionDesc`（启用条件）语义完全吻合，直接用；但 TLM 的 `MaidMoveToBlockTask`/`MaidArriveAtBlockTask` 依赖**原版寻路**，在地下隧道场景正是失败的那一环（这也是当初删掉 MOVE 的原因）⇒ 地下移动必须走本 mod 自己的 `VerticalMover`/DIG 原语，不能为了"复用"而把不可靠的寻路请回来。判据：**复用的是"语义相同的 API"，不是"名字相近的功能"**。
7. **不越过 TLM 的边界**：背包写入只用 `getAvailableInv`/`getAvailableBackpackInv` + `insertItemStacked`（尊重 `canInsertItem`），永不触碰槽位 5（展示位）；世界改动只用 `maid.destroyBlock`（尊重 `onEntityDestroyBlock`）与 `BlockItem#place`；不改动玩家在 TLM 配置里的开关语义（如 `isPickup()`、居家模式）。
8. **与 CORE 行为协作而非对抗**：移动意图可能被 `MaidFollowOwnerTask`/`MaidPanicTask`/`MaidBreathAirTask` 覆盖，设计时必须考虑被抢占时的降级行为（MM-603）。
9. **几何量必须从实体推导，禁止硬编码**：隧道净高用 `ceil(maid.getBbHeight())`、通行判定用 `level.noCollision(maid, bb)`。
   §1.5-B 的 1×3 隧道就是"硬编码 3 格"的产物——**任何"多挖一点以防卡住"的写法都要先问：真正卡住的原因是什么**。
10. **受阻要分类、不要笼统重试**：`UNBREAKABLE` 与 `TEMPORARY` 的处理方式完全不同（前者绕开，后者重试）。§1.5-A 的教训。

---

## 5. 里程碑路线图

| 版本 | 主题 | 包含条目 | 出口标准（DoD） |
|------|------|---------|----------------|
| **1.0.1** | **止血热修**（正确性/安全） | MM-501、MM-509、MM-901、MM-401、MM-502、MM-302、MM-101（含核实）、MM-110、MM-801、MM-902、MM-903、MM-308 | 复制漏洞有回归测试并修复；展示位不被消耗；专用服务器可启动；默认不进岩浆、不吃贵重方块、不破坏容器；深层矿丢弃率 ≤ 5%；构建 + GameTest 全绿 |
| **1.1.0** | **移动根治（1×2 隧道 + 不卡住）+ 性能与配置** | MM-304、MM-305、MM-306、MM-110①、MM-102、MM-103、MM-104、MM-105、MM-701、MM-702、MM-704、MM-805、MM-803、MM-301、MM-503、MM-507、MM-606 | 隧道截面 1×2（净高 = 女仆碰撞箱）；1 格宽竖井上行不卡；顶墙空转/上爬循环 ≤ 3 秒被识别；基岩邻近的矿默认不再跳过；§2.2 性能预算达标；配置项 ≥ 35 |
| **1.2.0** | **决策质量与体验** | MM-201、MM-202、MM-110②、MM-203、MM-204、MM-303、MM-303b、MM-307、MM-309、MM-402、MM-403、MM-404、MM-406、MM-505、MM-504、MM-111、MM-602、MM-601、MM-802、MM-804、MM-603 | 平均 ≤ 2.0 s/矿；锁定成功率 ≥ 98%；女仆不死；背包自动收纳；状态机拆分完成且有单测；时运/精准采集生效；**删除 `isNearBedrock`，基岩旁的矿可正常开采** |
| **2.0.0** | **生态与长期演进** | MM-107、MM-206、MM-205、MM-207、MM-310、MM-311、MM-405、MM-407、MM-508、MM-703、MM-906、MM-907、MM-1005 | 多女仆协作可用；挖掘模式可选；CI 化；发布元数据完整 |

---

## 6. 验证与回归体系

### 6.1 每次改动的最小验证

```powershell
# 编译（注意：必须带 -p 指定项目目录，见 HANDOFF.md:118-124）
./gradlew -p "D:\biancheng\minecraft\forge-1.20.1-47.4.22-mdk" compileJava

# 单元测试（MM-1001 落地后）
./gradlew -p "<项目路径>" test

# 打包（贴图改动后若报 Could not create ZIP，先 clean）
./gradlew -p "<项目路径>" jar

# GameTest（MM-1002 落地后）
./gradlew -p "<项目路径>" runGameTestServer
```

验证启动是否加载了正确的 mod（`HANDOFF.md:123`）：

```powershell
Select-String -Path run\logs\latest.log -Pattern "Logging into server with mod list"
```

### 6.2 GameTest 场景清单（MM-1002）

> **状态**：基础设施已建立并可用（`./gradlew runGameTestServer`，约 23 秒，无需客户端）。
> ✅ 已实现：破坏分类语义 7 例（见 §9）。⏳ 待实现：下表里所有**需要女仆实体**的场景。
> 结构模板：仓库根 `gameteststructures/*.snbt`（Forge 从 `<世界目录>/gameteststructures/` 读取，`build.gradle` 自动同步）。

| 场景 | 断言 | 关联 |
|------|------|------|
| 平坦石层中的铁矿 | 女仆挖到，且粗铁**直接进入背包**（TLM `destroyBlock` 自带入包） | MM-503 |
| **1 格宽竖井中连续上行 5 格** | 不使用 `setPos` 也能上去、无卡死（当前版本**必须失败**，证明 §1.5-B） | MM-304/305 |
| **挖 10 格后测量隧道空洞高度** | 净高 == `ceil(maid.getBbHeight())`（当前为 3，**必须失败**） | MM-304 |
| **路径上横着一道基岩墙** | 绕行或换目标；不再出现"顶墙空转 → 连续上爬"循环（当前版本**必须失败**，证明 §1.5-A） | MM-110/306 |
| y=-59 紧贴基岩的钻石矿 | 能被挖到（当前版本**必须失败**，用于证明 MM-110） | MM-110 |
| 背包仅剩 1 格 + 地面 64 个方块 | 总量守恒 64（当前版本**必须失败**，证明 MM-501） | MM-1003 |
| 背包槽位 5 放了独特物品 | 该物品在挖矿/垫脚/拾取后原样保留（当前版本**可能失败**） | MM-509 |
| 背包塞满后挖矿 | 溢出掉落落地，数量守恒（当前版本**可能失败**） | MM-501/504 |
| 背包含钻石块 + 圆石 + 空隙 | 只消耗圆石 | MM-502 |
| 关闭 TLM"拾物模式"（`setPickup(false)`） | 本 mod 不主动拾取 | MM-503/507 |
| 目标格是岩浆 | 不进入、改道或放弃，女仆存活 | MM-401 |
| 否决 `BlockEvent.BreakEvent` 的监听器 | 方块不被破坏 | MM-301 |
| 否决放置事件的监听器 | 方块不被放置且物品不消耗 | MM-302 |
| 沙砾柱下方挖矿 | 女仆不受窒息伤害 | MM-404 |
| 背包满 | 进入 UNLOAD 并成功交付 | MM-504 |
| 存档保存 → 重载 | 目标与黑名单保留 | MM-602 |

### 6.3 手动回归清单（每个里程碑必跑）

1. 单机创造：给女仆镐 + 副手原矿，切挖矿任务 → 观察 10 分钟（记录 KPI 表数值）。
2. 副手过滤矩阵：`raw_iron` / `iron_ore` / `deepslate_iron_ore` / `raw_copper_block` / `diamond` / `coal` / 空手 → 行为是否符合 MM-108 的新语义。
3. 危险地形：含水层、岩浆湖、峡谷、沙砾柱、刷怪笼各一次。
4. 极限状态：无镐、镐将坏、背包满、背包空（无法垫脚）、**背包展示位放着物品**（槽位 5）、被基岩包围、目标被玩家先挖掉。
5. TLM 开关联动：关闭"拾物模式"（`setPickup(false)`）、开启居家模式（限制半径）、切换日程（白天/夜晚 → REST）。
6. 生命周期：切换任务、收回女仆、离开区块、保存退出重进、专用服务器 + 客户端联机。
7. 兼容：领地/保护类 mod（若有）、TLM 更高版本。
8. 日志检查：默认配置下 `latest.log` 中 `[MaidMining]` 行数与内容符合 MM-801 预期。

### 6.4 KPI 历史记录表（每次发版回填）

| 版本 | 日期 | 平均 s/矿 | 最坏 s | 丢弃率 | 锁定成功率 | 单女仆查询/tick | 10 女仆 TPS | 备注 |
|------|------|----------|-------|--------|-----------|----------------|------------|------|
| 1.0.0 | 2026-08-11~14 | 3.0 | 41.0 | 37.5% | 82% | ≈6,656【推】 | 未测 | 基线（§1.3） |
| 1.1.0-dev（第一批修复） | 2026-09-20 | **3.2** | **8.9**（剔除暂停挂机） | **0%** | **95%** | 未测 | 未测 | 40 矿/21.9 分钟，Teleport fallback 4 次，无异常；详见 §9 |
| 1.1.0 | | | | | | | | |
| 1.2.0 | | | | | | | | |

---

## 7. 风险登记册

| 风险 | 影响 | 概率 | 缓解措施 |
|------|------|------|---------|
| "模拟挖掘时间"（MM-303）让女仆显著变慢，玩家觉得变弱 | 体验倒退、差评 | 中 | 保留"速度倍率"配置；默认值与当前体感对齐后再调 |
| 保守默认值（不挖玩家方块等）让玩家觉得"变笨了" | 体验 | 中 | 全部可配置 + 首次使用时给出说明消息 |
| 走 Forge 事件链（MM-301/302）带来额外开销 | 性能 | 低 | 仅在真正破坏/放置时触发，已在预算内 |
| 拆分重构（MM-601）引入回归 | 稳定性 | 高 | 先补测试再重构；按状态机逐段迁移，每段一个提交 |
| 多女仆协作（MM-205）引入死锁（互相等对方释放） | 卡死 | 中 | 预约必须带超时；看门狗检测"无进展"并强制释放 |
| 区块索引（MM-107）与现实不一致（漏掉后放置的矿石） | 漏挖 | 中 | 索引只作加速，最终以候选点二次校验为准；定期全量校准 |
| 玩家用本 mod 大规模自动挖矿导致服务器地形破坏投诉 | 社区 | 中 | 提供回填/范围限制/权限（MM-310、MM-407） |
| TLM 未来版本 API 变更导致行为失效 | 兼容 | 中 | 版本矩阵实测（MM-906）；行为内异常兜底（MM-806） |

---

## 8. 待确认问题（Open Questions）

> 这些问题的答案会影响若干条目的实现方式，**不改变目标本身**。
> **状态（本轮核实后）**：Q-1、Q-2、Q-3、Q-4、Q-5、Q-6、Q-7、Q-11、Q-12、Q-13、Q-14、Q-15 **已关闭**（结论见附录 D.2/D.3，并已回写到对应条目）；
> 仍待关闭的是 **Q-8、Q-9、Q-16、Q-17、Q-18**（另有 Q-10 已因"净高由碰撞箱推导"的设计而降级为验证项），全部属实机验证项（见 §6.3）。

| 编号 | 问题 | 影响条目 | 确认方式 |
|------|------|---------|---------|
| Q-1 | `Level#getBlockState` 在未加载区块上是否会同步加载/生成区块？ | MM-101（P0） | ✅ **已确认：会同步加载/生成**（附录 D.3.1）；MM-101 维持 P0 |
| Q-2 | `EntityMaid#destroyBlock` 是否已扣工具耐久？（与 `hurtAndBreak` 是否重复扣） | MM-308、MM-303 | ✅ **已确认：不扣**（附录 D.2）——手动 `hurtAndBreak` 必需，无重复扣耐久问题 |
| Q-3 | `destroyBlock` 是否走 Forge 破坏事件、是否掉落了战利品？ | MM-301、MM-503 | ✅ **已确认**：走 `Block.canEntityDestroy` + `ForgeEventFactory.onEntityDestroyBlock`（可被否决），掉落**直接进女仆背包**（溢出落地），不触发 `BlockEvent.BreakEvent`（附录 D.2） |
| Q-4 | 任务只返回 1 个行为时，TLM 的进食/自卫/跟随核心行为是否仍运行？ | MM-603 | ✅ **已确认：仍运行**（`createBrainTasks` 只进 `Activity.WORK`，CORE/IDLE/REST 独立注册，附录 D.2）；遗留子问题：与 CORE 行为的 `WALK_TARGET` 竞争需实测 |
| Q-5 | `IMaidTask#getName/getDescription` 的默认实现返回 key 还是已翻译文本？是否在服务端被调用？ | MM-901、MM-902 | ✅ **已确认**：`getName()` 默认 = `Component.translatable("task.<ns>.<path>")`；`getDescription()` 默认 = key 列表。覆写应直接删除（附录 D.2） |
| Q-6 | TLM 是否已有通用掉落物拾取（影响 MM-503 的必要性） | MM-503 | ✅ **已确认：有**（`MaidPickupEntitiesTask` + `MaidPickupEvent` + `isPickup()` 开关），见附录 D.1/D.2 |
| Q-7 | `TaskEquipUtil.tryEquipFromBackpack` 的谓词语义与是否会复制物品 | MM-505 | ✅ **已确认：交换、无复制**；另有 `putMainHandBack`；只搜 `getAvailableBackpackInv()`（不含手部）（附录 D.2） |
| Q-8 | 女仆是否吃食物/是否需要食物（影响长时间挖矿的可持续性） | MM-603、MM-504 | 实机 + TLM 文档 |
| Q-9 | 服务端 `simulation-distance`/`view-distance` 与搜索半径的实际关系 | MM-101、MM-106 | 专用服务器不同配置下实测 |
| Q-10 | 女仆碰撞箱尺寸与隧道高度的精确关系 | MM-304 | ✅ **已实测确认**：`Session start ... bbHeight=1.5 tunnelHeight=2` ⇒ 净高 = `ceil(1.5)` = **2**，矿道为 1×2（附录 §9 实机记录） |
| Q-11 | `ItemStack#hurtAndBreak` 在破损回调为空时是否自动移除物品 | MM-308 | ✅ **已确认：物品仍被消耗**（`shrink(1)`+耐久归零，不会卡在 maxDamage），空回调只缺音效/统计（附录 D.3.3） |
| Q-12 | `LevelChunkSection` 可用的剪枝 API（`hasOnlyAir`/`maybeHas`）签名与语义保证 | MM-104 | ✅ **已确认**，另有 Forge 的 `ChunkAccess.findBlocks` 可直接用（附录 D.3.2） |
| Q-13 | `Level#destroyBlock` 各重载的掉落与事件语义 | MM-301、MM-503 | ✅ **已确认**：空工具算掉落（不影响原版矿石掉出，但**时运/精准采集不生効**）、不触发 BreakEvent、不做保护检查、不扣耐久（附录 D.3.4） |
| Q-14 | `ForgeConfigSpec` / `ChunkEvent.Load` / `TagsUpdatedEvent` 在本版本的类名与注册方式 | MM-701、MM-107、MM-103 | ✅ **已确认可用**；`ChunkEvent.Load` 有"勿在回调里操作世界"的死锁警告（附录 D.3.6） |
| Q-15 | `Entity#setPos` 是否绕过碰撞、能否把实体卡进方块 | MM-305 | ✅ **已确认：绕过碰撞且不清 fallDistance**，卡住后会持续窒息伤害（附录 D.3.8） |
| Q-17 | `mobGriefing=false` 时女仆是否完全无法挖矿并静默发呆 | MM-301、MM-802 | 实测项（概率高，`canEntityDestroy` 会整体返回 false） |
| Q-18 | 时运/精准采集改造后的实际产出（验证 MM-303 的收益） | MM-303 | 实测项 |

---

## 9. 实施记录（Change Log）

> 每完成一批改动就在这里登记：条目编号、做了什么、怎么验证、遗留什么。与 §6.4 的 KPI 表配套使用。
> 状态图例：✅ 已实现并编译验证 ／ 🟡 部分实现 ／ 🔴 尚未开始。

### 2026-10-04 · 第九轮（1.0.2）：附魔适配 + 拆分重构

**主题**：本轮对应用户提出的两个目标——① 完成镐子附魔适配 ② 优化代码。

#### ① 附魔适配（MM-303 / MM-505）

**根因（新核实，字节码证据）**：TLM 的 `EntityMaid.destroyBlock` 在
`destroyBlock(Level, BlockPos, boolean, Entity)` 里把 `ItemStack.EMPTY`
**硬编码**传给 `dropResourcesToMaidInv`：

```
79: getstatic  ItemStack.EMPTY
82: invokevirtual dropResourcesToMaidInv:(...Lnet/minecraft/world/item/ItemStack;)V
```

掉落表的 `match_tool` 分支因此永不命中 ⇒ **时运与精准采集在 1.0.x 与 1.01 上 100% 无效**。
（与 D.3.4 的推断一致，本次拿到了直接的字节码证据。）

**关键 API 发现**：`EntityMaid.dropResourcesToMaidInv(BlockState, Level, BlockPos, BlockEntity,
EntityMaid, ItemStack)` 是 **public 且接受工具栈**，`maid.destroyBlock(level, pos, false, maid)`
的第三个参数是 `drop`——两者组合即可「自己算掉落 + 无掉落破坏」，**完全复用 TLM 接口**，
不需要 `setBlock`，因此 `onEntityDestroyBlock` 等保护链依旧有效。

**已实现**（`act/DigExecutor.java`，世界改动的唯一出口）：
- 默认路径：`Block.getDrops(state, level, pos, null, maid, realTool)` → `maid.destroyBlock(level,pos,false,maid)`
  → 逐个 `insertItemStacked` 入包、溢出 `popResource` → `spawnAfterBreak` 补经验球。
- 保护检查仍在破坏前走 `maid.canDestroyBlock`（尊重 `onEntityDestroyBlock` 与 mobGriefing）。
- `compat.fakePlayerMode`（MM-303b）默认 `MAID`；切 `FAKE_PLAYER` 时用
  `FakePlayerFactory.getMinecraft(level)` + `gameMode.destroyBlock(pos)` 走玩家路径，
  并**清空假玩家背包槽位**（该实例是跨次共享的，不清会搬走别处的物品）。

**取消耐久消耗**（用户需求）：`dig.damageTool` 默认 **false**。
注意 MM-308 的原结论是"扣耐久是必需且正确的"，本轮按需求反转默认值，
但**保留了 `hurtAndBreak` + `broadcastBreakEvent` 的正确写法**（打开开关即可恢复）。

**ToolManager（MM-505）**：按「挖矿相关附魔 → 基础等级 → 剩余耐久」评分选镐。
权重：精准采集 400、时运 300、效率 20、耐久修补 15（各乘等级），基础等级 ×100，耐久 0..99。
换手仍走 `TaskEquipUtil.tryEquipFromBackpack`（交换语义、无复制），失败才手工 swap。

**GameTest（MM-1002/1003）**：新增 6 个用例共 19 个，**已反向验证有效性**——
把 `useRealToolForDrops` 改为 false 后 `silkTouchKeepsTheOreBlock` 与
`fortuneIncreasesDiamondYield` 立即变红，恢复后 19 项全绿。
> ⚠️ 时运用例踩过一个坑：时运走 `apply_bonus → ApplyBonusCount$UniformBonusCount`，
> 内部是 `random.nextInt(bonusMultiplier + 1)`（已核实字节码）⇒ **单次挖掘结果是随机的**，
> 断言"单次 > 1"会随机变红。改为采样 40 次比平均值。
> 另一坑：两次挖同一坐标，第二次拿到的是空气。

#### ② 拆分重构（MM-601）

`MiningTunnelBehavior` 从 **954 行 → 459 行**（其中约 95 行是解释性注释），只保留状态迁移编排：

| 新类 | 职责 | 对应条目 |
|------|------|---------|
| `act/DigExecutor` | 破坏与掉落（唯一世界改动出口） | MM-303/303b/307 |
| `act/TunnelAdvancer` | DIG 状态的逐格推进决策（上行/下降/水平/绕行） | MM-201/206 |
| `act/VerticalMover` | 上行与净空（跳跃优先、传送兜底） | MM-304/305 |
| `act/Scaffolder` | 垫脚建材（白名单 + 碰撞检查） | MM-502 |
| `inv/ToolManager` | 最优镐选择与更换 | MM-505 |
| `inv/MaidInventory` | 背包读写唯一出口（守恒 + 展示位） | MM-501/509 |
| `session/MiningBlackboard` | 会话可变状态（有界集合） | MM-111/601 |
| `session/MiningState` | 状态枚举（仍三态，未扩八态） | MM-601 |
| `cfg/MiningConfigSpec` | ForgeConfigSpec，35 项 7 组 | MM-701/702/704 |
| `cfg/MiningConfig` | 运行时门面 + 标签缓存 | MM-701 |
| `cfg/Defaults` | 配置默认值（与 spec 逐项一致） | MM-701 |

**行为等价**：状态机语义、失败分类、上行原语、基岩规则均未改动。
**未做**（有意留在后续）：`MiningState` 仍是 SEARCH/DIG/MINE 三态，
没有提前扩成 §4.2 的八态——决策质量（可达性探测、矿脉延续、价值评分）还没落地，
提前扩状态只增加迁移风险。

#### ③ 顺手修掉的 P0/P1

- **MM-101（扫描强制加载区块）** —— 改用 `getChunkNow(x,z)`（已核实不加载不生成），
  拿不到就跳过并计数；再加 `LevelChunkSection.hasOnlyAir()` + `maybeHas` 段级剪枝。
- **MM-102（热路径分配）** —— 复用 `MutableBlockPos`，一轮扫描零 `new BlockPos`。
- **MM-105（空扫空转）** —— 冷却按 2^n 翻倍到 `search.emptyBackoffMaxTicks`（默认 600）。
- **MM-502（垫脚吃贵重方块）** —— 建材白/黑名单，默认只允许廉价方块。
- **MM-509（展示位）** —— `MaidInventory.DISPLAY_SLOT = 5`（已核实与
  `MaidBackpackHandler.BACKPACK_ITEM_SLOT` 一致），全路径排除。
- **MM-604（停止时归还工具）** —— `stop()` 里 `TaskEquipUtil.putMainHandBack`。
- **MM-903（启用条件）** —— 实现 `getEnableConditionDesc`，接上一直没被引用的
  `task.maidmining.mining.condition.pickaxe` 键。
- **MM-804（玩家看不到状态）** —— 实现 `getMaidActionSummary`（**注意真实签名是无参返回 `String`**，
  OPTIMIZATION.md 原草案写成了带 maid 参数返回 Component，已修正）。
- **MM-401 部分（岩浆）** —— `isPassable` 不再把任何流体视为可通行，岩浆默认禁止、水可配。

#### ⚠️ 本轮踩到的两个真实坑（已写入代码注释）

1. **`FMLCommonSetupEvent` 里读配置会炸服**：COMMON_SETUP **早于**配置加载完成，
   任何 `MiningConfig` 访问抛 `IllegalStateException: Cannot get config value before config is loaded`。
   实测直接把 GameTest 服务端炸了。⇒ 除纪律外，`MiningConfig.safe()` 还加了兜底：
   配置不可用时返回 `Defaults` 中的默认值而非崩溃。
2. **改 spec 的默认值不会影响已存在的配置文件**：Forge 只在文件不存在时写入默认值。
   做反向验证时改代码默认值无效，必须改 `run/world/serverconfig/maidmining-server.toml`。

#### 本轮未做（留给后续版本）

- 挖掘时间模型（效率附魔因此仍无实际效果）—— MM-303 下半部分，属 1.2.0。
- 经验球拾取（耐久修补）—— MM-508。
- 黑名单持久化 —— MM-602。
- 可达性探测与矿脉延续 —— MM-202 / MM-203。
- `/maidmining` 指令族 —— MM-803。
- 每女仆配置 GUI —— MM-703。

### 2026-09-20 · 第一批：§1.5 的两个历史妥协（1.1.0 起步）

**构建**：`./gradlew -p "<项目>" jar --offline` → **BUILD SUCCESSFUL**（含 `reobfJar`），javac **零警告**
（`build.gradle` 已开启 `-Xlint:deprecation -Xlint:unchecked`）。产物 `build/libs/maidmining-1.0.0.jar` 重建成功。

| 条目 | 状态 | 具体改动 | 验证 |
|------|------|---------|------|
| MM-110① | ✅ | 新增 `BreakResult`（SUCCESS/UNBREAKABLE/FLUID/PROTECTED/TEMPORARY）；`MiningValidator.classifyDig` 取代 `isDiggable`；行为侧 `tryBreak` 返回分类，`UNBREAKABLE/PROTECTED` → `recordWall` + **`abandonTarget` 立即放弃**（不再空转 ~120 tick）；`SKIP_NEAR_BEDROCK` 默认 false | 编译 + 打包通过 |
| MM-304 | ✅ | 净高改为 `Mth.ceil(maid.getBbHeight())`；删除水平推进里的 `feet+(dx,2,dz)`（原 1×3 的来源）→ 矿道恢复 **1×2**；`tryClimb` 里 `stepPos+1/+2` 的过度要求一并去掉 | 编译通过；`Session start ... tunnelHeight=` 日志可直接核对 |
| MM-305 | ✅ | 新增 `stepUp()` 上行原语：净空 → 落脚面 → **`canStandAt()`（`getBlockCollisions` 包围盒校验）** → **跳跃优先** → 传送兜底（兜底前再校验碰撞、兜底后 `resetFallDistance`）；**起塔改为「先校验抬升、再把腾出来的格子补上」**，等价玩家垫柱且去掉了"跳跃中放方块"的时序依赖 | 编译通过；行为待实机 |
| MM-306 | 🟡 | 新增 `tickProgressWatchdog`：与目标的距离连续 `MAX_NO_PROGRESS_TICKS`(400) 未缩短即放弃目标——直接兜住"顶墙空转"和"贴着基岩上爬"两类死循环。**净 Y 漂移的定向判定与恢复动作序列仍待做** | 编译通过 |
| MM-308 | ✅ | `hurtAndBreak(1, maid, item -> {})` → `e -> e.broadcastBreakEvent(MAIN_HAND)`，补齐破坏音效/动画/统计（与 vanilla `DiggerItem`、TLM `TaskSnow` 一致） | 编译通过 |
| MM-1005/1007 | 🟡 | 开启编译期 lint；清理 5 处已弃用 API（`BlockStateBase#isSolid()`、4 处 `ResourceLocation(String,String)`），构建输出零警告 | 构建输出零警告 |
| MM-1002 | 🟡 | 建立可运行的 **GameTest 基础设施**并落地第一批 7 个用例（破坏分类语义 + 契约测试），含**负向对照**证明用例有牙齿 | `runGameTestServer` → `All 7 required tests passed :)` |

**自动化验证证据（本批建立）**

```powershell
# 单元/集成测试：无需客户端，20 余秒
./gradlew -p "D:\biancheng\minecraft\forge-1.20.1-47.4.22-mdk" runGameTestServer --offline
# → 7 tests are now running!   ...   ========= 7 GAME TESTS COMPLETE =====   All 7 required tests passed :)
```

**负向对照（先证明测试能红，再信任它能绿）**：把 `classifyDig` 的基岩分支临时改回 `SUCCESS`（即旧代码的二值语义）后重跑：

```
[Server thread/ERROR] [minecraft/LogTestReporter]: bedrockisunbreakable failed! 基岩必须分类为 UNBREAKABLE，实际=SUCCESS
[Server thread/INFO]  [minecraft/GameTestServer]: 1 required tests failed :(
BUILD FAILED
```

恢复修复后重新全绿。⇒ 这些用例确实锁定的是"分类语义"，而不是恒真的空断言。

**已覆盖的用例**：基岩 = UNBREAKABLE、石头 = SUCCESS、水 = FLUID、黑曜石按硬度阈值 = UNBREAKABLE、
空气 = 已通过（SUCCESS）、基岩邻接识别（近/远两种）、`SKIP_NEAR_BEDROCK` 必须为 `false`（契约测试）。

**结构模板的两个坑（记录以免重踩）**：
1. Forge 的 GameTest **不从数据包读结构**，而是从 `<世界目录>/gameteststructures/<name>.snbt`（SNBT 文本）读取。
   最初放在 `src/main/resources/data/maidmining/structures/empty5.nbt`（二进制 NBT）→
   `Could not find structure file gameteststructures\empty5.snbt, and the structure is not available in the world structures either.`
2. `@GameTest(template = ...)` 的名字**不带命名空间**：`@GameTestHolder` 会自动补 mod 命名空间，
   写成 `"maidmining:empty5"` 会变成 `maidmining:maidmining:empty5` 并抛 `ResourceLocationException`。

仓库内 `gameteststructures/empty5.snbt` 是唯一事实源，`build.gradle` 会在 `runGameTestServer` 前自动同步到 run 目录，
因此测试对任何接手者都可复现。

**实机验证（2026-09-20，单人创造模式，21.9 分钟）**

启动命令：`./gradlew -p "<项目>" runClient --offline`（`forgeclientuserdev`，Forge 47.4.22 / MC 1.20.1）。
启动日志确认：`[maidmining/]: MaidMining task registered uid=maidmining:mining`，**无异常、无崩溃报告**。

| 观察项 | 日志证据 | 结论 |
|--------|---------|------|
| 净高推导 | `Session start maid=0 bbHeight=1.5 tunnelHeight=2` | **Q-10 关闭**：女仆碰撞箱 1.5 → 净高 2，矿道 1×2 |
| 破坏失败分类生效 | `Wall UNBREAKABLE (-232,-60,-228)` 紧跟 `Abandoned (-232,-61,-224) reason=unbreakable ahead (UNBREAKABLE)` | 撞到基岩**立刻放弃并换目标**（0.05 s 内），**没有出现任何上爬循环** |
| 基岩跳过规则已下线 | `Skipped bedrock-near ore` = **0 次**（旧基线 4 次会话共 27 次） | MM-110① 生效 |
| 上行原语 | `Teleport fallback` **4 次** / 约 46 次移动 ≈ 9% | 跳跃优先生效；失败时由有界兜底接管，未卡死 |
| 产出与稳定性 | 锁定 42 / 挖到 40（**95%**，基线 82%）；40 矿共 21.9 分钟，其中含暂停与挂机 | 锁定成功率明显改善 |
| 稳定挖矿间隔 | 平均 **3.2 s**；**真实最长停顿 8.9 s** | 旧基线最坏 41 s → 本次最坏的 47.5 s / 1174.5 s **都跨在 `Saving and pausing game`（15:25:57 / 15:26:45）上，是暂停/挂机痕迹**，不是卡顿 |
| 连续矿脉爆发 | 15:46:16–17 的 0.85 s 内连续 4 次 `Locked→Mined` | 相邻矿无需重扫，说明搜索延迟不是瓶颈 |

**本次暴露出的最大摩擦点（第二轮已修，待复测）**：**需要「上行 1 格」的场合**。会话里两个 8.3 s 停顿（15:46:17→25、15:46:29→38）都是"目标比自己高一格"，
日志显示最终由 `Teleport fallback` 解决。

第二轮实机反馈给出了决定性线索：「**向上挖会卡住，看起来跳的不够高，最后还是得靠传送**」。
这不是寻路问题，而是**物理问题**：女仆 bbHeight=1.5，在 1×2 隧道里天花板在 y+2，
**跳跃被截断在 0.5 格以内，1 格台阶永远迈不上去**（详见 §1.5-B 的修正版）。

⇒ 修复（已实现）：`MiningConfig.JUMP_HEADROOM = 1` + `stepUp()` 第 3.5 步——起跳前若 `feet + height` 不可通行就先挖掉，
形成局部"站位凹坑"。水平/向下仍然是 1×2，只有上台阶那一格是 1×3。
**复测要点**：`Teleport fallback` 次数应从 4 次显著下降；上行应是"挖一个小坑 → 跳上去"，而不是"原地跳几次 → 瞬移"。

### 2026-09-20（第三轮）· 自阻挡 bug：女仆跳到空中给自己砌墙

**实机反馈**：「女仆想往上时，会跳起来，但是跳到空中时，她会在想要上去的目标方块上放置方块，导致把自己挡住。」

**根因（两个缺陷叠加，都在本轮新写的上行原语里）**：

1. **`digging` 对 `|dy| <= 1` 无条件调用 `stepUp`**。
   但女仆站在平整地面上就能挖到高一格的矿石（`isAdjacent` 允许 `|dy| <= 1`）——**根本不需要搭台阶**。
   她却为此在自己前进方向的地面格垫了一块方块，等于**在自己的通道里砌墙**。
2. **`stepUp` 的"垫落脚面"步骤没有区分空中/地面**。
   她跳起来之后该步骤仍会执行；此时她的碰撞箱高于那一格，AABB 相交检查放行，
   于是**方块被放进她正要落进去的格子**，把自己的落点封死。实机看到的就是"跳到空中时在目标格上放方块"。

**修复**：

| 改动 | 位置 | 说明 |
|------|------|------|
| 取消 `|dy| <= 1` 的无条件上行 | `digging` 阶段 2 | 改为"优先水平接近"；上行原语只在 **`dy >= 2`** 与 **水平受阻时的绕行兜底** 两处出场 |
| 垫落脚面只在站在地面时执行 | `stepUp` 第 2 步 | 空中且落脚面未就绪时直接返回，等落地后再从地面垫 |
| 新增自阻挡硬性不变式 | `extractAndPlace` | **绝不**在女仆自身所在的两格高身体柱里放方块（AABB 求交在贴边/空中时可能漏判） |

**验证**：`jar` + `runGameTestServer` → `All 7 required tests passed :)`，BUILD SUCCESSFUL。
**待实机复测**（本 bug 属于"需要女仆实体"的场景，GameTest 覆盖不到）：向上接近时不应再出现"空中砌墙"，`Jump headroom dug` 只应出现在 `dy >= 2` 的真抬升场合。

**教训（已并入设计原则 9/10）**：**"能伸手够到"就不要"搭路过去"**。
`isAdjacent` 已经给了 1 格的高度容差，任何"为安全起见多垫一块"的写法都要先问：
这一步真的是必需的位移吗？

### 2026-09-20（第四轮）· 上行只蹦不走：走位目标在窄隧道里会被寻路器抹掉

**实机反馈**：「女仆想上坡只会原地蹦跶，不会向前。」

**根因（这是 HANDOFF 里"寻路在地下不可用"的具体机制，第一次被定位到确切的代码路径）**：

| 环节 | 事实 |
|------|------|
| `MoveToTargetSink`（vanilla，CORE 行为，每 tick 运行） | 用 **`navigation.createPath()`** 造路来驱动移动；**拿不到路径时会直接 `eraseMemory(WALK_TARGET)` + `navigation.stop()`** ⇒ 我发的走位目标被它抹掉了 |
| `MoveControl`（vanilla 源码 `MoveControl.java:85-105`） | **`MOVE_TO` 是一次性的**：处理完立刻把自己转回 `WAIT`。所以"下发一次"只能驱动 **1 tick** |
| `Mob.java:747` | `this.f_21342_.m_8126_()` ⇒ `MoveControl.tick()` **每 tick 都被驱动**，逐 tick 下发才是正确用法 |
| TLM `MaidMoveControl.tick()` | **会调用 `super.tick()`**，并自带跳跃逻辑（含 `MAID_JUMP_FORBIDDEN_BLOCK` 标签检查）⇒ MoveControl 本身没问题 |
| `MoveControl` 的自动跳跃条件 | `d2 > maxUpStep && 水平距离² < max(1.0, bbWidth)` —— 站在**恰好 1 格外**时该条件**不成立**（需要严格小于），所以不能指望它，必须自己起跳 |

⇒ 结果：走位目标被抹掉 + 跳跃还在发 ⇒ **原地蹦跶不向前**。

**修复（`driveClimb`）**：上行改由**逐 tick 直接驱动 `MoveControl`**，彻底绕开寻路：

```java
maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);   // 别让 MoveToTargetSink 掺和
maid.getNavigation().stop();
maid.getMoveControl().setWantedPosition(dest.x+0.5, dest.y, dest.z+0.5, MOVE_SPEED);  // 每 tick 下发
if (还没抬起来 && maid.onGround()) maid.getJumpControl().jump();                       // 自己起跳
```

- 上行期间 `climbDest != null`，`tick()` 会**暂停 DIG/MINE 状态机**，让这次位移成为原子动作；
- 到位判定：`blockPosition().getY() >= climbDest.getY()`；
- 超时（`CLIMB_DRIVE_TIMEOUT_TICKS = 60`，3 秒）才做一次校验后的传送兜底。

**验证**：`jar` + `runGameTestServer` → `All 7 required tests passed :)`。
**待实机复测**（需要女仆实体，GameTest 覆盖不到）：上坡应表现为"走过去 + 跳上去"，**不再原地蹦**；`Teleport fallback` 应进一步减少。

**沉淀**：**在这个 mod 里，"发走位目标让原版寻路去走"这个念头本身就是错的**——
`setWalkAndLookTargetMemories` 在地下只适用于"同层、已挖通、短距离"的情形；
任何需要**跨高度**或**跨未挖通地形**的位移，都必须由 `MoveControl.setWantedPosition` 逐 tick 直接驱动。

### 2026-09-20（第五轮）· 基岩规则回退 + 给所有静默路径补可观测性

**实机反馈**：「跳跃问题修复了（✅ 第四轮生效），但是女仆老是会突然停住，是不是基岩的问题。」

**日志分析结论：是，但只解释了一半。** 本轮 19 次锁定只挖到 10 个（53%），9 次失败中：

| 现象 | 次数 | 归因 |
|------|------|------|
| `Abandoned reason=unbreakable ahead / below` | 6 | **基岩**（墙块 y=-61/-62，目标 y=-60~-63），其中 4 次在 2.5 秒内撞**同一墙块** |
| `Abandoned reason=no progress for 401 ticks` | 1 | **与基岩无关**：18:53:37.9→18:53:58.4 整整 20 秒无任何动作 |

**① 基岩规则按实机反馈重新开启**（详见 §1.5-A 第五轮）：`SKIP_NEAR_BEDROCK = true`，
并把契约用例改为 `bedrockSkipRuleIsCurrentlyEnabled`（附原因，翻转即变红）。

**② 20 秒静默停顿：先补可观测性，不猜**。排查时发现**所有会卡住的路径都是静默的**，无法定位：

| 补的日志 | 位置 | 用途 |
|---------|------|------|
| `Stuck state=… target=… feet=… dy=… climb=… height=… dist=…` | 看门狗，每 100 tick（5 秒）一条，**只在无进展时**触发 | 直接看出卡在哪个状态、离目标多远、是否在攀爬 |
| `Climb timeout, dest not standable (…)` | `driveClimb` 超时且落点不可站立 | 以前这条分支是静默的，可能造成"每 3 秒静默重试一次"的循环 |
| 净空/起跳净空破坏时的 `TEMPORARY` 计数 | `stepUp` 第 1、3.5 步 | 以前 `tryBreak` 返回 TEMPORARY 会**静默无限重试**；现在计入 `temporaryFailures`，超限即放弃 |

**验证**：`jar` + `runGameTestServer` → `All 7 required tests passed :)`（含更新后的契约用例）。
**待实机复测**：① 深层矿区不再出现"站着反复搜索"；② 若仍出现长时间停顿，`Stuck` 行会直接指出卡点。

**过程教训**：**契约测试在行为反转时立刻变红，这是好事**——它逼我们把"为什么改回来"写进代码与文档，
而不是让下一个人再把开关翻回去。本轮它确实先红了一次。

### 2026-09-20（第六轮）· 发呆真身：水平移动的走位目标同样会被寻路器抹掉

**实机反馈**：「女仆为什么还是会出现发呆的情况，这是必须被解决的问题」＋
「副手放着矿物时女仆应该锁定对应矿物进行挖掘，而不是什么都挖」。

**第五轮补的 `Stuck` 快照直接抓住了发呆**（这是它第一次发挥作用）：

```
4× Stuck state=DIG target=(-189,-54,-186) feet=(-189,-55,-182) dy=1 climb=null height=2 dist=4.1
4× Stuck state=DIG target=(-189,-55,-186) feet=(-189,-55,-182) dy=0 climb=null height=2 dist=4.0
4× Stuck state=DIG target=(-217,-55,-215) feet=(-209,-56,-209) dy=1 climb=null height=2 dist=10.0
```

**读法**：`dist=4.0`、`dy=0`（**同一水平面**）、`climb=null`、连续 4 个快照（20 秒）**feet 一字不变**。
⇒ 代码以为在正常水平掘进（每 6 tick 都在发走位目标），**但女仆一步都没动**。
根因与第四轮的跳跃问题**完全同源**：`setWalkAndLookTargetMemories` → `MoveToTargetSink` 用
`navigation.createPath()` 造路 → **拿不到路径就抹掉 `WALK_TARGET` 并停下**。
挖方块是好的（所以隧道在延伸），但"走路"被静默取消。

**修复 ①：发呆接管（`tickStationaryWatch`）**——
DIG 状态下位置连续 `STALL_TAKEOVER_TICKS`(20) tick 没变，就把最近下发的「下一格」接管为
**直接驱动 `MoveControl`**（复用第四轮验证过的机制）。日志会打 `Stall takeover -> direct drive`。
该机制是**加法**的：正常路径不动，只在病态情形介入。

**修复 ②：副手定向过滤（MM-108，见 §3 条目）**——
本轮日志显示 **77 次锁定全部 `target=all`**，过滤完全没生效（旧实现只认 `raw_*`/`*_ore`）。
已改为多策略解析 + 与真实矿石材料集合核对 + 无法识别时 WARN，并加了 **11 种物品的解析用例与 4 条匹配断言**。

**验证**：`jar` + `runGameTestServer` → `All 13 required tests passed :)`（新增 3 个副手用例）。
**待实机复测**：① 不再出现"站着不动"（若仍有，日志会有 `Stall takeover` 与后续 `Stuck` 快照可对照）；
② 副手放矿石后，`Locked ore … target=xxx` 应显示材料名而不是 `all`。

### 2026-09-20（第七轮）· mod 矿石的标签化支持 + 镐子识别放宽

**实机需求**：「远古残骸的支持我也需要」＋「常见的 mod 矿石如镍、铝之类的我也需要，
**这种 mod 矿石可不可以自动获取标签以进行搜寻**」＋「让女仆的主手镐子可以支持所有带镐标签的工具」。

**先核实了两个事实（避免按猜测写标签名）**：

| 事实 | 依据 |
|------|------|
| 原版 `minecraft:pickaxes` **只列 6 把原版镐**；Forge 1.20.1 **没有**统一的镐标签 | 直接读 `client-extra.jar` 的标签 JSON；Forge `Tags.java` 中无 pickaxe 常量 |
| Forge 给远古残骸的标签是 **`forge:ores/netherite_scrap`**（不是 `ores/ancient_debris`） | Forge 源码 `Tags.Blocks.ORES_NETHERITE_SCRAP = tag("ores/netherite_scrap")` |

**① 矿石识别改为标签驱动（回答"能不能自动获取标签"）：可以，而且这就是现在的工作方式。**
识别顺序：`minecraft:ores` / `forge:ores` / `c:ores` → **任意 `<ns>:ores/<材料>` 子标签** →
显式覆盖表（`ancient_debris`）→ `_ore` 命名兜底。材料名同样从 `ores/<材料>` 标签反推。
⇒ **mod 矿石（镍/铝/铅/锡…）只要遵循 `forge:ores/<材料>` 约定，就自动被搜寻并支持副手定向，无需为任何 mod 写代码。**
⇒ `ancient_debris` 因 Forge 的命名约定，材料名取 `netherite_scrap`，副手放残骸或残骸方块都能定向。
⇒ 顺带把结果缓存为 `Set<Block>` / `Map<Block,String>`，扫描热路径只剩一次查表（完成 MM-103 的一半）。

**② 副手解析改为"多候选逐个核对"**：命名模式 → **物品标签**（`forge:raw_materials/nickel`、
`forge:ingots/aluminum`…，这是 mod 材料的主力来源，能覆盖 `mekanism:ingot_tin` 这类不按套路命名的物品）
→ 别名表 → 物品名本身；每个候选都要与"世界里真实存在的矿石材料"核对，取第一个命中的。
（这个"逐个核对"正是被测试逼出来的：`quartz` 的物品标签给出无效的 `quartz`，会提前短路掉别名 `nether_quartz`。）

**③ 镐子识别放宽**（`isPickaxe`）：`#minecraft:pickaxes` → `forge/c:tools/pickaxes` →
**`ToolActions.PICKAXE_DIG`**（mod 镐主力：任何继承 `DiggerItem` 的工具都会命中）→ **能正确开采石头**兜底。

**验证**：`jar` + `runGameTestServer` → **`All 13 required tests passed :)`**
（新增：远古残骸可定向、材料集合覆盖 10 种含 `netherite_scrap`、镐子识别 12 项断言）。

### 2026-09-20（第八轮）· 发布 1.0.1

**本轮补齐了发布前必须清掉的两个 P0，并完成发版工程化。**

| 条目 | 状态 | 改动 |
|------|------|------|
| MM-901 | ✅ | **删除 `MiningTask` 的 `getName()/getDescription()` 覆写**及其客户端类引用（`Minecraft`/`I18n`）、硬编码中英文案与 `isZh()`；改用 TLM 默认实现（由 UID 生成 `task.maidmining.mining` / `.desc` 的 i18n key，语言文件已是唯一事实源）。专用服务器崩溃风险消除。 |
| MM-502 的一半 | ✅ | `pickupDrops` 的**复制漏洞**修复：部分转移时回写剩余数量（`stack.setCount(left); drop.setItem(stack)`），不再出现"背包与地面同时存在"。**注意这只是 MM-501 的复制部分；MM-509（展示位/安全写入通道）仍未做。** |
| MM-1006 | 🟡 | 版本号 `1.0.0 → 1.0.1`；新增 `CHANGELOG.md`（中英双语）；README 同步 jar 名；发布用大图放 `publish/`。 |

**发布验证**：`clean jar runGameTestServer` 全量重建 → **`All 13 required tests passed :)`**，产物 `maidmining-1.0.1.jar`（66.5 KB），构建零警告。

**⚠️ 公开发布边界（重要）**：本次推送**刻意不包含** `OPTIMIZATION.md`——它记录了**尚未修复**的问题细节（MM-509 展示位被当建材消耗、MM-502 可能吃贵重方块等），公开等于给玩家提供"踩坑指南"。该文件与 `HANDOFF.md`（含凭据）都只保留在本地。等 1.1.0 把这两项修掉后，再考虑把本文档放进仓库。

**同批完成的"顺手正确性"**：
- 塔式上行（目标在正上方）的旧实现只 `setPos` 不补方块 → 会被重力拉回原点（净进度 0），**这正是"向上卡住"的成因之一**；已改为"抬升 + 补格"。
- `findScaffoldSlot` / `extractAndPlace` 拆分：把"有没有建材"的前置判断与"是否会把女仆埋住"的碰撞检查分开，起塔时才能安全地往刚腾出的格子放方块。

**遗留（下一批）**：

1. **需要女仆实体的场景尚未自动化**：§6.2 里的"1 格宽竖井连续上行 5 格""隧道净高实测""基岩墙绕行"都需要给 `EntityMaid`
   配一个可控的 owner（`FakePlayer`）才能跑，属于 MM-1002 的下一批；本批只覆盖了不依赖实体的分类语义。
2. `wallBlocks` 目前只做记录与日志，**尚未被任何规划器消费**——在 MM-202 落地前，遇到墙的策略是"立即放弃目标"，而不是"绕过去"。
3. `isNearBedrock` 仍留在代码里（默认关闭，并有契约测试钉住），等 MM-202 落地后按 MM-110② 删除。
4. 本轮**未触碰** MM-501/MM-509（P0 物品复制与展示位）——按 §0.3 的规则，P0 物品缺陷必须先有物品守恒测试（MM-1003）。
5. **实机未验证**（本会话无法启动客户端）。行为层面需按 §6.3 手动回归确认；`Q-10`（女仆 bbHeight 实测值）可由
   `[MaidMining] Session start ... bbHeight=... tunnelHeight=...` 一行日志直接读出。

---

## 附录 A：缺陷全量索引（v1.0.0 源码）

按文件排列，含行号与一句话结论。**这是修复时的工作清单**。

**`mining/MiningTunnelBehavior.java`（486 行）**

| 行 | 问题 | 条目 |
|----|------|------|
| 51 | `failedTargets` 无界集合，切任务即清空 | MM-111/602 |
| 54,467 | `climbFlip` 赋值后从未使用（死代码） | MM-306 |
| 57,61-63 | duration=`MAX_VALUE`、`canStillUse` 恒 true，行为永不退出 | MM-603 |
| 72-76 | `stop()` 只清 WALK_TARGET，不持久化/不清理 | MM-604 |
| 81-83 | 每 20 tick 尝试换镐，但无最佳工具选择 | MM-505 |
| 100-101 | 硬编码英文提示 + 每 100 tick 刷屏 | MM-802 |
| 109-115 | 基岩邻近即丢弃目标（实测丢弃 37.5%）；**根因是"会被基岩卡住"，见 §1.5-A** | MM-110 |
| 133-137 | 目标失效时也在热路径打 info 日志（含注册表查询） | MM-801 |
| 170-200 | 直下挖掘无落点探测；流体被视为可通行 | MM-401/402 |
| 187-188 | `stuckCount++` 不区分失败原因；`dy<=-2` 的恢复动作 `tryClimb` 成功后**清零计数** ⇒ 识别不出无限上爬（§1.5-A） | MM-110/306 |
| 217-259 | `stuckCount` 跨分支共享、重置不一致；同一段逻辑复制 4 次 | MM-306/601 |
| 234-259 | 额外清 y+2 → 3 格高隧道（**§1.5-B 的补偿产物**） | MM-304 |
| 289-290 | 瞬时破坏 + 空破损回调 | MM-303/308 |
| 302 | 挖完立刻全量重扫（无矿脉延续） | MM-203 |
| 308-311 | `isPassable` 把任何流体当可通行（含岩浆） | MM-401 |
| 313-323 | 破坏通道**是正确的**（`destroyBlock` → `onEntityDestroyBlock`），但 `breakBlock` 把"永不可破/流体/被保护/暂时失败"全压成 `false` ⇒ 调用侧只能盲目重试（§1.5-A 的根因之一） | MM-110/301 |
| 356,384 | `setPos` 传送上台阶/上塔（不做碰撞解算、不清落差，附录 D.3.8） | MM-305 |
| 365-372 | `tryClimb` 上行要求 `stepPos+1/+2` 全通 → 3 格净高的来源之一（§1.5-B） | MM-304/305 |
| 375-390 | 上行主路径是发走位目标（依赖原版寻路跳步）且**无失败兜底** ⇒ 上行卡住的直接原因（§1.5-B） | MM-305 |
| 393-414 | **拾取复制漏洞** + 裸 `setStackInSlot`（绕过 `canInsertItem`）+ 不尊重 `isPickup()` + 1.5 格轮询 | MM-501/503/507/309 |
| 419-427 | 垫脚取第一个固体 BlockItem，无过滤；遍历 36 格（含展示位 5） | MM-502/509 |
| 431-438 | 垫脚碰撞只检查自己，忽略其他实体 | MM-205 |
| 440 | `level.setBlock` 直接放置，无放置事件 | MM-302 |
| 445-471 | 状态机无记忆：每次挖完重置全部状态 | MM-601/602 |
| 473-485 | 换镐逻辑自己实现（且遍历 36 格），未用 `TaskEquipUtil`；只看"是不是镐" | MM-505/509 |

**`mining/MiningTunnelFinder.java`（92 行）**

| 行 | 问题 | 条目 |
|----|------|------|
| 45-46 | 固定冷却 40 tick，无指数退避 | MM-105/802 |
| 52-53 | 扫描下界 clamp 到 `minBuildHeight+1`，与"基岩层是否有矿"无关 | MM-110 |
| 55 | 每 tick 128 列 × 52 层 = 最多 6,656 次查询 | MM-102/104/105 |
| 66-68 | 限制区域只在搜索判定，移动阶段不生效 | MM-407 |
| 69-77 | 逐方块 `getBlockState`，无加载判断、无段级剪枝、每层 `new BlockPos` | MM-101/102/104 |
| 62-77 | "最近的列 + 最上面的矿"≠ 三维最近，且忽略硬度/危险 | MM-201/202 |
| 90 | 排序无二级键，同距离顺序不确定（影响可测性） | MM-1001 |

**`mining/MiningValidator.java`（184 行）**

| 行 | 问题 | 条目 |
|----|------|------|
| 43-49 | 每个方块都做注册表查询 + 字符串判断，无缓存 | MM-103 |
| 47-48 | 兜底只认 `_ore` 结尾（漏 `ancient_debris` 等） | MM-109 |
| 87-109 | 副手解析只认 `raw_*`/`*_ore`；无法识别时静默返回 null（挖全部）；`raw_copper_block` 产生垃圾目标 | MM-108 |
| 112-130 | 过滤靠方块名匹配材料，与掉落物/标签脱节 | MM-108/109 |
| 133-152 | `hasOreNear`/`orePositionsNear` 全项目无人调用（死代码） | MM-1007 |
| 158-168 | 可挖判定用硬编码 `speed < 50`：黑曜石(50) 被排除；不校验工具等级是否足够；不排除容器/贵重方块 | MM-109/303 |
| 171-183 | 3×3×3 基岩邻近即判不可达（过激） | MM-110 |

**`task/MiningTask.java`（78 行）**

| 行 | 问题 | 条目 |
|----|------|------|
| 9-10 | 引用客户端类 `Minecraft`/`I18n` | MM-901 |
| 32-33 | 硬编码描述与 lang 文件重复且不一致（TLM 默认实现本就返回 key，无需此字段） | MM-902 |
| 35-42 | `catch (Exception)` 捕不到 `NoClassDefFoundError`；`isZh()` 整个机制都是多余的 | MM-901 |
| 51-64 | 覆写 `getName/getDescription`，与 `HANDOFF.md:96-99` 的约定冲突，也与 TLM 默认实现重复（应直接删除） | MM-901/902 |
| 66-69 | `getConditionDescription` 为空；TLM 的启用条件应实现 `getEnableConditionDesc`，lang 键 `…condition.pickaxe` 本就符合其命名约定 | MM-903 |
| 74-77 | **未覆写** `searchRadius`/`searchDimension`/`getEnableConditionDesc`/`getMaidActionSummary`（TLM 提供的集成点全部闲置） | MM-106/606/804/903 |

**`mining/MiningConfig.java`（41 行）**：全部为 `static final`（MM-701/702）；缺少安全/物品/性能/兼容类参数（MM-702）；`MAX_STUCK_COUNT` 注释与实际语义（跨分支累加）不符（MM-306）。

**`build.gradle` / `mods.toml` / `gradle.properties`**：已配置 GameTest 但无测试（MM-1002）；`mods.toml` 缺少 issue/update URL 与兼容矩阵（MM-1006/906）；仓库名与 modid 不一致（MM-1006）。

---

## 附录 B：配置项草案（MM-701/702）

> `SERVER` 配置；类型/默认值/范围待实现时定稿。默认值列已体现"保守默认"原则（MM-704）。

| 键 | 默认 | 说明 |
|----|------|------|
| `search.radiusBlocks` | 48 | 搜索半径（格），自动不超过服务端已加载范围 |
| `search.heightUp` / `heightDown` | 3 / 48 | 垂直搜索范围 |
| `search.columnsPerTick` | 128 | 单 tick 扫描预算（MM-105 后为全局预算） |
| `search.emptyBackoffTicks` | 40→1000 | 无矿时的指数退避上限 |
| `search.oreTagWhitelist` / `blacklist` | `#minecraft:ores` / 空 | 矿石识别（MM-109） |
| `search.skipNearBedrock` | **false** | 是否跳过基岩邻近矿石（默认关，MM-110） |
| `target.maxSecondsPerTarget` | 120 | 单目标预算（MM-204） |
| `target.maxBlocksPerTarget` | 512 | 单目标最大破坏方块数 |
| `target.continueVein` | true | 矿脉延续（MM-203） |
| `target.valueWeights` | 见实现 | 矿石价值表（MM-207） |
| `travel.tunnelHeight` | 2 | 隧道净高（MM-304） |
| `travel.speedMultiplier` | 0.9 | 移动速度**倍率**（乘以 `minecraft:generic.movement_speed`，默认属性 0.7；不是方块/tick）。原 `MOVE_SPEED` 命名有歧义，建议改名（MM-1007） |
| `travel.allowTeleportFallback` | true | 是否允许兜底传送（MM-305） |
| `travel.allowDigStraightDown` | **false** | 是否允许 1×1 直下（MM-402/206） |
| `travel.staircaseEvery` | 3 | 阶梯巷道每前进 N 格下降 1 格 |
| `dig.simulateBreakTime` | true | 是否模拟挖掘时间（MM-303） |
| `dig.speedMultiplier` | 1.0 | 挖掘速度倍率 |
| `dig.useBestToolForBlock` | true | 土用铲等（MM-307） |
| `safety.allowLava` | **false** | 绝对禁止进入岩浆（MM-401） |
| `safety.allowWater` | true | 允许涉水/游泳（MM-403） |
| `safety.waterPolicy` | `SEAL` | 破入含水层策略：封堵/绕行/放弃 |
| `safety.minHealthRetreat` | 8.0 | 低血撤退阈值（MM-406） |
| `safety.protectPlayerBlocks` | true | 不破坏玩家放置的方块（MM-301/704） |
| `safety.respectClaims` | true | 尊重领地/保护（MM-301/302/904） |
| `inv.scaffoldWhitelist` | 圆石/泥土/下界岩… | 垫脚建材白名单（MM-502） |
| `inv.scaffoldBlacklist` | 矿石/贵重块/容器/食物 | 硬黑名单（MM-502/506） |
| `inv.pickupPolicy` | `VALUABLE_ONLY` | 拾取策略：全部/仅值钱的/仅自己挖的（MM-503/507） |
| `inv.unloadWhenFull` | true | 背包满时收纳（MM-504） |
| `inv.unloadTarget` | 主人/指定容器 | 收纳目标 |
| `inv.toolMinDurability` | 10 | 提前换镐阈值（MM-505） |
| `perf.logLevel` | WARN | mod 日志级别（MM-801） |
| `perf.statsEnabled` | false | 是否开启统计计数器（MM-805） |
| `compat.respectBlockEvents` | true | 是否走 Forge 事件（MM-301/302） |
| `compat.torchLighting` | false | 隧道照明（MM-311） |
| `compat.backfillTunnel` | false | 回填隧道（MM-310） |

---

## 附录 C：指令与反馈草案（MM-802/803/804/805）

| 指令 | 作用 |
|------|------|
| `/maidmining status` | 列出附近女仆的挖矿状态（状态/目标/已挖数/受阻原因） |
| `/maidmining stats` | 输出计数器：查询/tick、扫描轮次、候选数、放弃原因分布 |
| `/maidmining debug <maid>` | 切换该女仆的详细日志与范围可视化 |
| `/maidmining blacklist clear <maid>` | 清空黑名单（MM-111） |
| `/maidmining reload` | 重载配置（MM-701） |

i18n 键规划（新增，全部中英双语）：`maidmining.msg.no_pickaxe`、`.searching`、`.no_ore_found`、`.target_abandoned`（带原因占位符）、`.inventory_full`、`.unloading`、`.retreat_low_health`、`.hazard_lava`、`.claim_conflict`。

---

## 附录 D：已核实的依赖 API 事实

> 用于消除 §8 的待确认问题。`【jar】` = 直接读取 TLM 1.5.3 jar 的类/资源条目（可复现）；
> `【反编译】` = 读取方法体；`【实测】` = 运行验证。

### D.1 TLM 1.5.3 侧已核实（来源：`touhoulittlemaid-1.5.3_mapped_parchment_2023.09.03-1.20.1.jar` 条目清单【jar】）

| 发现 | 对优化的影响 |
|------|-------------|
| **TLM 没有挖矿任务**：`entity/task/` 下只有 `TaskIdle/TaskAttack/TaskBowAttack/TaskCrossBowAttack/TaskDanmakuAttack/TaskTridentAttack/TaskFishing/TaskGrass/TaskSnow/TaskSugarCane/TaskMelon/TaskCocoa/TaskHoney/TaskMilk/TaskShears/TaskFeedAnimal/TaskFeedOwner/TaskNormalFarm/TaskTorch/TaskBoardGames/TaskExtinguishing` 等 | 本 mod 填补的是真实空白；`maidmining:mining` 无 UID 冲突（回应 MM-905） |
| **TLM 自带拾取链路**：`entity/ai/brain/sensor/MaidPickupEntitiesSensor`、`entity/ai/brain/task/MaidPickupEntitiesTask`、`api/event/MaidPickupEvent`（含 `ItemResultPre`/`ItemResultPost`/`ExperienceResult`/`ArrowResult`/`PowerPointResult`）、GUI 文案 `gui.touhou_little_maid.button.pickup.*`（"拾物模式"开关）、`item_magnet_bauble`（扩展拾取范围） | 直接改写 MM-503/MM-507/MM-508：自定义拾取不仅冗余，还**绕过了 TLM 的事件与拾物模式开关**；应复用而非重写 |
| **TLM 自带照明能力**：`entity/task/TaskTorch`、`entity/ai/brain/task/MaidTorchPlaceTask`、`MaidTorchMoveTask` | MM-311 应复用 TLM 的放火把行为 |
| **TLM 自带生存行为**：`MaidExtinguishingTask`（灭火）、`MaidBreathAirTask`/`MaidBreathAirStopTask`（换气）、`MaidHealSelfTask`（自愈）、`MaidPanicTask`/`MaidRunAwayTask`（逃跑）、`MaidClearHurtTask`、`MaidClimbTask`、`MaidSwimJumpTask`、`MaidMeleeAttack`/`MaidAttackStrafingTask`、`MaidFollowOwnerTask`、`MaidInteractWithDoor`、`MaidMoveToBlockTask`/`MaidArriveAtBlockTask`、`MaidUpdateActivityFromSchedule`、`MaidBedTask` | MM-603/MM-406/MM-403/MM-405 的目标从"自己实现"改为"**不得压制 TLM 已有能力**"；同时也说明当前独占大脑的行为风险更高 |
| **大脑扩展 API**：`api/entity/ai/IExtraMaidBrain`、`entity/ai/brain/ExtraMaidBrainManager`、`entity/ai/brain/MaidBrain`、`network/message/RefreshMaidBrainMessage` | `IExtraMaidBrain` 是**全局**注册（`EXTRA_MAID_BRAINS` 对所有女仆的 WORK/CORE 生效），**不适合**任务专属行为；`MaidBrain.registerWorkGoals` 只把 `createBrainTasks` 追加进 `Activity.WORK`，所以现有接入方式（只返回一个行为）**不会**压制核心行为（详见 D.2.6） |
| **任务数据持久化 API**：`api/entity/data/TaskDataKey`、`entity/data/TaskDataRegister`、`entity/data/MaidTaskDataMaps`、`InitTaskData` | MM-602 应用它存目标/黑名单，而不是自造 NBT |
| **任务接口族**：`api/task/IMaidTask`、`IAttackTask`、`IFarmTask`、`IFeedTask`、`IRangedAttackTask`、`api/task/meal/IMaidMeal`；任务事件 `api/event/MaidTaskEnableEvent` | MM-903 条件描述、MM-602 启用/停用钩子可参考这些接口 |
| **任务配置 GUI/容器**：`client/gui/entity/maid/task/DefaultMaidTaskConfigGui`、`inventory/container/task/DefaultMaidTaskConfigContainer` | MM-703 的每女仆配置可挂在这里（可选） |
| **装备工具**：`util/TaskEquipUtil` | 已核实语义（D.2.8）：`tryEquipFromBackpack` 是**交换、无复制**、只搜可用背包段；`putMainHandBack` 可归还主手物 → MM-505/MM-604 应直接复用，不要再手写换镐 |

### D.2 TLM 1.5.3 方法体已核实事实【字节码】

> 取证方式：沙箱禁止启动 `javap`/`java` 进程，因此改用 PowerShell 自建的 `.class` 解析器 + 反汇编器读取 TLM jar 的常量池与指令流（等价 javap `-p -c`），并用已知正确的方法交叉校验（如 `IMaidTask.isEnable` → `iconst_1; ireturn`）。以下每条都能复现。

**D.2.1 `EntityMaid.destroyBlock`（3 个重载）**

| 事实 | 结论 |
|------|------|
| `destroyBlock(BlockPos)` | 等价于 `destroyBlock(pos, true)`（`dropBlock = true`） |
| `destroyBlock(BlockPos, boolean)` | 先 `canDestroyBlock(pos)`，再委托给 4 参重载；返回值 = 两者皆真。**第二个 boolean 是"是否掉落"，不是"是否可破坏"** |
| `destroyBlock(Level, BlockPos, boolean, Entity)` | 是原版 `Level.destroyBlock` 的手抄版，但把 `Block.dropResources(...)` 换成 `dropResourcesToMaidInv(...)`；发出 `levelEvent(2001)`、`GameEvent.BLOCK_DESTROY`、`BlockState.spawnAfterBreak` |
| **工具耐久** | **完全不扣**。全部重载中没有任何 `ItemStack.hurt`/`hurtAndBreak`/`mineBlock` 调用。TLM 自己的 `TaskSnow`/`TaskMelon` 是在 `destroyBlock` 之后**自己**调 `held.hurtAndBreak(1, maid, …)` → 本 mod 现有的手动扣耐久写法是**正确且必需**的 |
| **掉落去向** | `dropResourcesToMaidInv` → `Block.getDrops(...)` → `ItemHandlerHelper.insertItemStacked(getAvailableInv(false), drop, false)`；**装不下的部分才 `Block.popResource` 落地**。⇒ "女仆挖矿拿不到粗铁"的说法**不成立** |
| **保护检查** | 在 `canDestroyBlock` 内：`Block.canEntityDestroy(state, level, pos, maid)` **且** `ForgeEventFactory.onEntityDestroyBlock(maid, pos, state)`。⇒ 保护类 mod **可以**否决；但**不触发** `BlockEvent.BreakEvent`（无玩家参与，vanilla 亦然） |
| **限制区域** | `canDestroyBlock` **不检查** `isWithinRestriction`（TLM 只在 `MaidMoveToBlockTask` 的选点阶段检查）⇒ 移动阶段的越界防护必须由本 mod 自己做（MM-407 成立） |

**D.2.2 背包与手部（`getMaidInv()` 的真实含义）**

| 事实 | 结论 |
|------|------|
| `getMaidInv()` | 返回 `<init>` 中创建的 `MaidBackpackHandler`（`extends ItemStackHandler`），**36 格背包**，**不是手部** |
| `MaidBackpackHandler.BACKPACK_ITEM_SLOT` | **== 5**（由 `onContentsChanged` 的 `iload_1; iconst_5; if_icmpne` 反推）：该槽变动会 `maid.setBackpackShowItem(...)`，即女仆的**背包展示位** |
| `getAvailableInv(boolean includeHands)` | `RangedWrapper(maidInv, 0, getMaidBackpackType().getAvailableMaxContainerIndex())` + 手部（顺序随 flag 翻转）⇒ **可用容量由背包类型决定，不等于 36** |
| `getAvailableBackpackInv()` | 只含上面的可用背包段（**不含手部**） |
| `MaidHandsInvWrapper.isItemValid` | → `EntityMaid.canInsertItem(stack)`：命中 `MaidConfig.MAID_BACKPACK_BLACKLIST` 的物品名 → false，否则 `item.canFitInsideContainerItems()` ⇒ **裸 `setStackInSlot` 会绕过这条校验** |
| 其它 | `getHideInv()`（1 格隐藏手持）、`getTaskInv()`（9 格）、`getMaidBauble()`（30 格）；**不存在** `getBackpackInv()`/`getHandItems()` |

**D.2.3 限制与开关**

`hasRestriction()` == `isHomeModeEnable()`；`isWithinRestriction(pos)` = `getRestrictCenter().distSqr(pos) < getRestrictRadius()²`；`restrictTo`/`clearRestriction`/`getRestrictCenter`/`getRestrictRadius` 齐备。
**拾物开关方法名是 `isPickup()` / `setPickup(boolean)`**（不是 `isPickupItem`）。

**D.2.4 进食**

`MaidWorkMealTask`（`MaidCheckRateTask` 子类，`setMaxCheckRate(50)`）在 `checkExtraStartConditions` 要求 `task.enableEating(maid)`；`start` 遍历 `HandUtils.NATIVE_HANDS = [MAIN_HAND, OFF_HAND]` 调用 `IMaidMeal.canMaidEat/onMaidEat`。`EntityMaid.isFood` 恒 false；`eat()` 会 post `MaidAfterEatEvent`。⇒ 挖矿长期占用主/副手会影响进食选物（MM-506）。

**D.2.5 `IMaidTask` 全部 22 个方法（关键默认实现）**

| 方法 | 默认实现 / 含义 |
|------|----------------|
| `getName()` | `Component.translatable(String.format("task.%s.%s", ns, path))` ⇒ **已是可翻译组件，覆写纯属多余** |
| `getDescription(maid)` | `Lists.newArrayList(String.format("task.%s.%s.desc", ns, path))` ⇒ **返回 key 列表**，与当前覆写行为相反 |
| `getEnableConditionDesc(maid)` | `Collections.emptyList()`；配 lang 键 `<uid>.condition.<name>` ⇒ **这才是启用条件的正确方法**（MM-903） |
| `getConditionDescription(maid)` | `Collections.emptyList()`（当前覆写的是它，但 GUI 用的是 `getEnableConditionDesc`） |
| `isEnable(maid)` / `isHidden(maid)` | `true` / `false` |
| `enableLookAndRandomWalk` / `enablePanic` / `enableEating` | 均 `true`（可覆写以关闭对应行为） |
| `searchRadius(maid)` | `maid.getRestrictRadius()` |
| `searchDimension(maid)` | `searchRadius` 的 AABB；`hasRestriction()` 时夹紧到限制中心 |
| `VERTICAL_SEARCH_RANGE`（字段） | TLM 的垂直搜索约定常量 |
| `getTaskConfigGuiProvider(maid)` / `getTaskInfoGuiProvider(maid)` | 提供配置/信息界面（MM-703/804 的挂载点） |
| `getMaidActionSummary()` | `getUid().getPath()`（MM-804 的挂载点） |
| `createRideBrainTasks` / `workPointTask` / `canSitInJoy` / `onFunctionCallSwitch` | 空 / false / false / `OK` |
| **不存在** | `getPriority`、`tick`、`onEnable`、`onTaskEnable`、任何 NBT 钩子 |

**D.2.6 大脑装配（回答 Q-4）**

`MaidBrain.registerBrainGoals` 顺序：`registerSchedule` → **`registerCoreGoals`** → `registerPanicGoals` → `registerRide*Goals` → `registerIdleGoals` → **`registerWorkGoals`** → `registerRestGoals` → `setCoreActivities(CORE)` → 默认活动 IDLE。

`registerWorkGoals` 只把 `createBrainTasks(maid)` 的返回**追加**进 `Activity.WORK`，并在其后无条件追加：`MaidUpdateActivityFromSchedule(99)`、`MaidBegTask(6)`、`MaidWorkMealTask(7)`、`MaidStealEdibleMoveBlockTask(8)`、`MaidStealEdibleUseTask(8)`、look-and-random-walk(20)，以及所有 `IExtraMaidBrain.getWorkBehaviors()`。

`Activity.CORE` 有 14 项，含 `MaidSwimJumpTask(0.9F)`、`MaidClimbTask`、`MaidBreathAirTask`/`MaidBreathAirStopTask`、`LookAtTargetSink`、`MaidPanicTask`、`MaidAwaitTask`、`MaidInteractWithDoor`、`MoveToTargetSink`、**`MaidFollowOwnerTask(0.5F, 2)`**、`MaidFollowOwnerVehicleTask`、**`MaidHealSelfTask`**、**`MaidPickupEntitiesTask(pred, 0.7F)`**、`MaidClearSleepTask`。

⇒ **结论：核心行为不会被任务替换**（Q-4 关闭）。任务行为只影响 WORK 活动，且 WORK 内还并行跑着 TLM 追加的那些行为。
⇒ 但 CORE 与 WORK 会**同时**写 `MemoryModuleType.WALK_TARGET`（跟随主人/恐慌/游泳/攀爬/换气 vs 挖矿推进），存在移动指令竞争 → 列为 MM-603 的待实测项。
⇒ `IExtraMaidBrain` 是**全局**注册（对所有女仆、WORK 与 CORE 都生效），**不适合**放任务专属行为，故本 mod 继续用 `createBrainTasks` 是对的。

**D.2.7 `TaskManager`**

`add(task)` = `TASK_MAP.put(uid, task)` + `TASK_INDEX.add(task)`：**不查重、不报错**（重复会覆盖 map 但列表出现两份）。
`init()` 末尾执行 `ImmutableMap.copyOf` / `ImmutableList.copyOf` ⇒ **init 之后再 `add()` 会抛 `UnsupportedOperationException`**，第三方任务必须经 `ILittleMaid.addMaidTask(TaskManager)`（当前做法正确）。
内置 22 个 UID 全部在 `touhou_little_maid` 命名空间（idle/attack/ranged_attack/crossbow_attack/danmaku_attack/trident_attack/farm/sugar_cane/melon/cocoa/honey/grass/snow/feed/shears/milk/torch/feed_animal/fishing/extinguishing/board_games/gun_attack）⇒ **与 `maidmining:mining` 无冲突**（Q-5/MM-905 关闭）。

**D.2.8 `TaskEquipUtil`（回答 Q-7）**

`tryEquipFromBackpack(maid, pred)`：① 主手已满足谓词 → 直接返回 `true`（不动）；② 在 `getAvailableBackpackInv()` 中 `ItemsUtil.findStackSlot` 找**首个**匹配；③ 取整叠 `extractItem(slot, count, false)`；④ 主手非空则把主手写回**同一个**槽位；⑤ `setItemInHand(MAIN_HAND, 取出的叠)`。
⇒ **是交换、不会复制**；不含手部槽位；`findStackSlot` 只返回首个匹配（要"选最优镐"需用带耐久判定的谓词精确匹配）。
`putMainHandBack(maid)`：把主手放进背包首个空槽并清空主手，背包满则返回 false（不动）。

**D.2.9 任务数据持久化（MM-602 的实现路径）**

`TaskDataKey<T>`（`getKey`/`writeSaveData`/`readSaveData`，可选 `writeSyncData`/`readSyncData`）+ `TaskDataRegister.register(...)`（经 `ILittleMaid.registerTaskData(reg)` 注册）+ `MaidTaskDataMaps`。NBT 键名 `"MaidTaskDataMaps"`，逐条 `tag.put(key.toString(), key.writeSaveData(value))`；`EntityMaid.addAdditionalSaveData` → `writeSaveData`，`readAdditionalSaveData` → `readSaveData`，`syncData()`/`onSyncedDataUpdated` → `getUpdateTag`/`readFromServer`（可同步到客户端）。访问入口：`maid.getData(key)` / `getOrCreateData(key, def)` / `setData(key, value)`。另有 `MaidTaskEnableEvent` 可动态追加启用条件。

**D.2.10 `InitEntities.TARGET_POS`**

类型 `MemoryModuleType<PositionTracker>`，注册时传 `Optional.empty()`（**无 Codec ⇒ 不随大脑持久化**）。TLM 自己写入于 `MaidMoveToBlockTask.searchForDestination`（`setMemory(TARGET_POS, new BlockPosTracker(pos))`），读取于 `MaidArriveAtBlockTask`（`VALUE_PRESENT` 门控 → 到位后 `eraseMemory(TARGET_POS)` 与 `WALK_TARGET`）。
⇒ 该记忆是**瞬时**的，挖矿目标不能只存在这里（必须进 D.2.9 的持久化通道）。

**D.2.11 TLM 无挖矿能力（回答 MM-905/906 的一部分）**

对 jar 全部 5124 个条目做 ASCII 扫描：`mining` 仅命中一个第三方许可证文本，`Mining`/`maidmining`/`oredict`/`ore_dict`/`oreautomation`/`Digger`/`Excavate` **全部 0 命中**（`pickaxe` 仅命中动画姿势名 `hold_mainhand:pickaxe`）⇒ TLM 1.5.3 **不含任何挖矿/自动采矿任务或矿石词典集成**，本 mod 填补的是真实空白。

### D.3 原版 1.20.1 / Forge 47.4.22 已核实事实【源码/字节码】

> 取证方式：反编译版原版源码（SRG 名，无 Forge 改动）+ Forge 47.4.22 的 `sources.jar`（含 `patches/**.java.patch` 与 mojmap 命名的 Forge 类）+ 编译产物的常量池扫描。沙箱同样禁止启动 `javap`。

**D.3.1 Q-1：`Level#getBlockState` 在未加载区块上会同步加载/生成区块（P0 结论成立）**

`Level.getBlockState → getChunk(x,z) → LevelReader.getChunk(x,z,ChunkStatus)`（该默认实现硬编码 `requireChunk = true`）→ `Level.getChunk(...,true)` → `ServerChunkCache.getChunk(...,load=true)`：非主线程时 `supplyAsync(...).join()`，主线程上走 `addTicket(TicketType..., chunkPos, ...)` + `runDistanceManagerUpdates()` + `chunkHolder.getOrScheduleFuture(...)`，再 `mainThreadProcessor.managedBlock(future::isDone)` **阻塞等待**；`load=true` 失败会抛 `IllegalStateException("Chunk not there when requested: …")`。只有三种情况返回空气：超出建筑高度（→ void_air）、已加载且该段 `hasOnlyAir`（→ air）、**客户端**缺失区块（→ 空区块）。
**正确的前置检查是 `Level#isLoaded(BlockPos)`**（走 `ServerChunkCache#hasChunk` → `getVisibleChunkIfPresent` + `ChunkLevel.byStatus(FULL)`，**不加载**）；`hasChunkAt` 系列已全部 `@Deprecated`（但同样不加载）。

**D.3.2 Q-12：段级剪枝 API**

`LevelChunkSection.hasOnlyAir()`（内部是"非空气方块计数 == 0"）；`maybeHas(Predicate<BlockState>)` 走调色板：`GlobalPalette` 恒 `true`，`Linear/HashMapPalette` 遍历调色板条目，`SingleValuePalette` 比对唯一值。**语义保证：`false` ⇒ 该段确定没有匹配方块；`true` 只是"可能有"（误报允许，且调色板位宽 ≥8 进入全局调色板后恒为 true）**。Forge 追加了 `ChunkAccess.findBlocks(BiPredicate<BlockState,BlockPos>, BiConsumer<BlockPos,BlockState>)`，内部先用 `maybeHas` 预过滤再走 4096 次循环——这是本 mod 扫描应优先使用的入口。

**D.3.3 Q-11：`hurtAndBreak(1, entity, item -> {})` 的精确语义**

`if (hurt(...)) { onBroken.accept(entity); shrink(1); if (player) awardStat(ITEM_BROKEN); setDamageValue(0); }`。
⇒ 回调为空**不会**导致物品残留：物品仍被消耗（count 1 → 0，`isEmpty()` 为真，槽位里留一个空栈对象），耐久被重置为 0，**永远不会卡在 `damage == maxDamage`**。回调在 `shrink` **之前**执行，因此回调里能读到"已损坏"的状态。
⇒ 结论：镐子消耗逻辑本来就是对的（不要"修"它），空回调唯一的影响是缺 `broadcastBreakEvent` 的音效/动画/统计（vanilla `DiggerItem` 与 TLM `TaskSnow`/`TaskMelon` 都传了该回调）。

**D.3.4 Q-13：`Level#destroyBlock` 三个重载**

| 事实 | 结论 |
|------|------|
| 重载 | `(BlockPos,boolean)` → `(BlockPos,boolean,Entity)` → `(BlockPos,boolean,Entity,int)`（`LevelWriter` 的 default 逐级委托） |
| 掉落 | 内部 `Block.dropResources(state, level, pos, be, entity, **ItemStack.EMPTY**)` ⇒ **战利品按"空手破坏"计算**，工具只影响 `LootContext` 的 TOOL 参数 |
| 工具门槛 | `BlockState.canHarvestBlock`（`requiresCorrectToolForDrops` 的实际关卡）**不在此路径**，只在玩家破坏路径 |
| `BlockEvent.BreakEvent` | **不触发**。`ForgeHooks.onBlockBreakEvent` 全工程只有一个调用点：`ServerPlayerGameMode`（玩家路径）。常量池扫描亦证实 `Level.class` 里没有 `ForgeHooks`/`onBlockBreakEvent` |
| 保护检查 | **不做**。`mayInteract`（出生点保护/世界边界）只在玩家路径检查 |
| 工具耐久 | **不扣**；耐久只在玩家路径由 `ItemStack.mineBlock` 扣 |

**⚠️ 对"空手掉落"的独立复核（与子代理结论不同）**：我从 `client-extra.jar` 直接读出了 `data/minecraft/loot_tables/blocks/iron_ore.json`、`diamond_ore.json`、`stone.json`，**它们的 `raw_iron`/`diamond`/`cobblestone` 分支上没有任何"需要正确工具"的条件**（只有精准采集分支挂在 `match_tool` 上；石头甚至只有 `survives_explosion`）。结合"徒手挖石头不掉落"这一游戏常识可知：**工具门槛在玩家路径（`canHarvestBlock`）而非战利品表**。因此"TLM 用空工具求掉落 ⇒ 铁矿掉 0 个"的推论**不成立**——掉落会正常产出，只是**时运/精准采集不生效**，且因为没有 `canHarvestBlock` 检查，TLM 路径连工具等级要求都跳过了（木镐也能出钻石，这是 TLM 的既有行为，不是本 mod 引入的）。
⇒ 对本文档的影响：MM-503 的"掉落直接入包"成立；**MM-303 新增"时运/精准采集永不生效"这一证据**。

**D.3.5 Q-5：破坏/放置事件与实体破坏钩子**

- `BlockEvent.BreakEvent`（`@Cancelable`，构造需 `Player`；javadoc 明确建议"没有玩家时用 `EntityFakePlayer`"）——由 `ForgeHooks.onBlockBreakEvent` 发布，仅在玩家路径。
- `BlockEvent.EntityPlaceEvent` / `EntityMultiPlaceEvent` ——由 `ForgeHooks.onPlaceItemIntoWorld` → `ForgeEventFactory.onBlockPlace` 发布，**仅在物品使用放置路径**。
- 实体破坏：`ForgeHooks.canEntityDestroy(level,pos,living)` = `ForgeEventFactory.getMobGriefingEvent(level,entity)`（`mobGriefing` 游戏规则，可由 `EntityMobGriefingEvent` 覆盖）`&& state.canEntityDestroy(...) && ForgeEventFactory.onEntityDestroyBlock(...)`（后者发布 `LivingDestroyBlockEvent`）。
  ⇒ **`mobGriefing=false` 会让女仆完全无法破坏任何方块**——当前 mod 会把它当作"挖不动"，累加 `stuckCount` 后把目标拉黑，最终所有矿都进黑名单、女仆静默发呆（见 MM-301 的退化路径）。
- `FakePlayer` / `FakePlayerFactory`（`getMinecraft(ServerLevel)`、`get(ServerLevel, GameProfile)`、`unloadLevel`）在 47.4.22 中确实存在 ⇒ 可选的"玩家路径模拟"方案可行（MM-303b）。
- 另注意：**没有**任何覆盖"任意实体任意 `setBlock`/`destroyBlock`"的通用钩子，也没有 `BlockDrops`/`HarvestDrops` 事件。

**D.3.6 Q-14：`ForgeConfigSpec` 与事件注册（47.4.22 均可用）**

`net.minecraftforge.common.ForgeConfigSpec`（`Builder`：`define`/`defineInRange`/`defineInList`/`defineEnum`/`comment`/`push`/`pop`/`configure`/`build`）；注册 `ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC)`。事件总线 `MinecraftForge.EVENT_BUS` + `@SubscribeEvent`（或 `@Mod.EventBusSubscriber`）。本 mod 需要的三个事件均在总线上：`ChunkEvent.Load(ChunkAccess, boolean newChunk)`（**javadoc 警告：可能在区块晋升到 `FULL` 之前触发，贸然操作世界会导致区块加载死锁**）、`LevelEvent.Load(LevelAccessor)`、`TagsUpdatedEvent(RegistryAccess, boolean fromClientPacket, boolean isIntegratedServerConnection)`（MM-103 的缓存失效就用它）。

**D.3.7 Q-4/行为语义（加强 MM-603 的结论）**

`Behavior`：`tryStart` = `hasRequiredMemories && checkExtraStartConditions` → `status=RUNNING`，时长 `minDuration + random.nextInt(maxDuration + 1 - minDuration)`；`tickOrStop` = `!timedOut && canStillUse` 才 `tick`；`canStillUse` **默认 false**（不覆写就只能活 1 tick）。
`Brain.tick` → `startEachNonRunningBehavior` **只按优先级顺序对 STOPPED 的行为调 `tryStart`，没有任何互斥/打断逻辑**；`tickEachRunningBehavior` 逐个 tick 所有 RUNNING 行为 ⇒ **同一活动内不同优先级（甚至同优先级）的行为可以同时 RUNNING**，优先级只决定迭代顺序。冲突只能靠"记忆条件"自己排除。
`BehaviorUtils.setWalkAndLookTargetMemories(entity, tracker, speed, closeEnough)` 同时写 `walk_target` 与 `look_target`；**`speed` 是 `minecraft:generic.movement_speed` 属性的倍率**（`MoveControl.tick` → `setSpeed(speedModifier * getAttributeValue(MOVEMENT_SPEED))`，属性默认 0.7），**不是方块/tick**。TLM 自身用 0.5F/0.7F/0.9F。另：**TLM 1.5.3 中不存在 `MOVE_SPEED` 这个标识符**（4500+ 类常量池扫描 0 命中）——它不是 TLM API。

**D.3.8 Q-15：传送不解析碰撞，也不清落差**

`setPos(x,y,z)` = `setPosRaw` + 重算包围盒，**无碰撞检测、无区块校验、不清 `fallDistance`** ⇒ 实体可以停在实体方块里；随后每 tick 被 `LivingEntity.baseTick` 的 `isInWall()` 判定受 1 点窒息伤害，并触发方块 `entityInside`。`moveTo` 与 `teleportTo(double,double,double)` 同样不清落差（`checkFallDamage` 只在 `Entity.move` 中调用，`resetFallDistance` 的调用点也只有 move/水/梯子/传送门等）⇒ **传送期间累积的落差会在下一次落地时一次性结算**。
其余：`teleportTo(double,double,double)` 在客户端是空操作；`teleportToWithTicket` 会顺带加载区块；`teleportTo(ServerLevel,…,Set<RelativeMovement>,…)` 的 `Entity` 实现**忽略**该 Set（只有 `ServerPlayer` 会用）；`Entity.moveTowardsClosestSpace`（唯一的"挤出方块"工具）是 protected 且只被 `ItemEntity`/`ExperienceOrb` 使用，**没有任何机制会自动把卡住的女仆推出来**。

**D.3.9 Q-（新）：没有任何"所有方块变更"事件**

`Level.setBlock` 不发事件；`LevelChunkSection#setBlockState` 无钩子且 Forge 未 patch 该类；chunk 侧唯一的标记是 `ChunkAccess#setUnsaved(boolean)`（**只用于存档脏标记**，无版本号、无段级脏标记、无监听器）；Forge 唯一的"变更通知"是 `IForgeBlockState#onBlockStateChange`，**只通知被放置方块自己的类**。相关事件（`NeighborNotifyEvent`、`FluidPlaceBlockEvent`、`CropGrowEvent`、`FarmlandTrampleEvent`、`PortalSpawnEvent`、`BlockToolModificationEvent`）都只在特定路径触发。
⇒ **MM-107 的区块矿石索引必须"保守 + 二次校验"，不能假设索引与世界始终一致**；推荐的廉价重扫路径是 `ChunkEvent.Load` 登记 + `ChunkAccess.findBlocks` + `maybeHas` 预过滤。

### D.4 仍未关闭的问题（需实机）

| 问题 | 状态 |
|------|------|
| Q-8 女仆是否需要食物、挖矿时能否正常进食 | 实测项（§6.3） |
| Q-9 `simulation-distance`/`view-distance` 与搜索半径的实际关系 | 实测项（决定 MM-101 的实际触发频率） |
| Q-10 女仆碰撞箱与隧道高度的精确关系 | 实测项（MM-304） |
| Q-16 CORE 与 WORK 的 `WALK_TARGET` 竞争的实际表现 | 实测项（MM-603） |
| Q-17 `mobGriefing=false` 时女仆是否完全无法挖矿（进而静默发呆） | 实测项（MM-301，概率高） |
| Q-18 `damageItem`/时运等的实际生效情况（确认 MM-303 的改造收益） | 实测项 |

---

## 附录 E：与其它文档的关系

| 文档 | 关系 |
|------|------|
| `HANDOFF.md` | 项目交接（构建/运行/发布/踩坑）。本文档的"验证命令"沿用其第 4 节约定 |
| `README.md` | 面向玩家。功能描述应在每次发版后与本文档 §3 的实际行为对齐（MM-1007） |
| 本文档 | **优化目标的唯一来源**。条目编号稳定，不因实现方式变化而改号 |

**维护约定**：每次发版后更新 §6.4 KPI 历史表与 §3 条目的状态；已完成的条目保留编号并标记 ✅（不要删除，便于回溯"为什么变成现在这样"）。
