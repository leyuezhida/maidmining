**本次更新：系统性修复「女仆在地下挖矿会卡住」，并新增模组矿石支持。**

### 修复

- **不再被基岩卡住** —— 破坏结果现在会分类（永不可破 / 流体 / 被保护 / 暂时失败）。旧版把四种情况混成一个 `false`，导致对着基岩空转约 6 秒，再叠加错误的恢复动作（向上传送并清零计数），形成「贴着基岩无限上爬」的死循环。现在遇到不可破坏的方块会立即放弃该目标。
- **矿道不再过高** —— 隧道净高改为按女仆碰撞箱推导（**1×2**，与玩家身高一致）。旧版固定挖 3 格高，比需要多挖约 1/3。上台阶处会在头顶局部多挖 1 格作为起跳空间。
- **修复上坡「原地蹦跶不向前」** —— 上行不再依赖原版寻路（它在 1 格宽隧道里给不出路径），改为逐 tick 直接驱动移动；跳跃优先，传送仅作兜底。
- **修复上坡时「空中放方块把自己挡住」**。
- **修复「突然停住」** —— 新增发呆检测：位置连续 20 tick 不变即接管移动。
- **修复物品复制** —— 收集溢出掉落物时，「只装下一部分」的情况会正确回写剩余数量（旧版不回写，物品会同时存在于背包与地面）。
- **修复镐子破损无音效/动画**。
- **专用服务器安全** —— 任务定义不再引用客户端类（旧版在专用服务器上可能 `NoClassDefFoundError`）。

### 新增

- **模组矿石自动支持** —— 矿石识别改为标签驱动（`#forge:ores`、`#c:ores` 及任意 `<命名空间>:ores/<材料>` 子标签），镍、铝、铅、锡等遵循 Forge 约定的模组矿石**自动可被搜寻、自动支持副手定向**，无需为任何模组适配。
- **远古残骸支持** —— 副手放 `netherite_scrap` 或远古残骸方块即可定向。
- **副手定向重做** —— 支持命名模式（`raw_iron`/`iron_ore`/`iron_ingot`/`iron_dust`…）、**物品标签**（`forge:raw_materials/nickel` 等）、别名（`lapis_lazuli→lapis`、`quartz→nether_quartz`）。识别不了时会给出 WARN，不再静默变成「挖全部」。
- **镐子识别放宽** —— 带镐标签或行为上就是镐的工具都能用（含模组镐）。
- **无进展看门狗 + 卡住快照日志**。

### 调整

- 「基岩邻近的矿跳过」按实测重新默认开启（避免在基岩层出现「连续锁定→撞墙→放弃」的碎裂循环）。
- 构建开启编译期 lint，清理 5 处已弃用 API。

### 已知问题（后续版本处理）

- 使用背包里的方块垫脚时，目前取「第一个可站立的方块」，可能消耗贵重方块。
- 背包展示位（第 5 格）尚未从垫脚/存储范围排除。
- 目标黑名单不随存档持久化。
- 遇到不可破坏的方块只会放弃目标，尚无绕墙寻路。
- 破坏仍是瞬时完成，时运/精准采集暂不生效。

---

**Summary (English)**: fixes the maid getting stuck underground — no more grinding against bedrock, 1×2 tunnels instead of 3-high, step-ups now move forward instead of hopping in place, no more mid-air self-blocking, no more random standing still, and an item duplication bug in drop collection is fixed. Adds tag-driven support for ores from other mods (nickel, aluminium, lead, tin, …) plus ancient debris, and rewrites offhand ore targeting (naming patterns + item tags + aliases).
