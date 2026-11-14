# 移植笔记：1.20.1 Forge → 1.21.1 NeoForge

这份文档记录从 1.20.1 Forge 分支（`main`）移植到本分支时改了什么、为什么改。
玩法逻辑**一行没动**，改动全部来自平台 API 差异，按文件归类如下。

## 1. 版本与工具链对照

| 项目 | 1.20.1 Forge（`main`） | 本分支（1.21.1 NeoForge） |
|------|------------------------|---------------------------|
| Minecraft | 1.20.1 | 1.21.1 |
| 加载器 | Forge 47.4.22 | NeoForge 21.1.219 |
| Java | 17 | 21 |
| Gradle | 8.8 | 8.8 |
| 构建插件 | ForgeGradle 6 + Parchment | ModDevGradle 2.0.148（官方映射） |
| 映射 | Parchment `2023.09.03-1.20.1` | Mojang 官方（NeoForge 开发环境默认） |
| TLM | 1.5.3-forge+mc1.20.1 | 1.5.3-neoforge+mc1.21.1 |
| 模组元数据 | `META-INF/mods.toml` | `META-INF/neoforge.mods.toml` |
| 模组版本 | 1.1.0 | 1.1.0 |

## 2. 平台迁移（机械替换）

| 1.20.1 Forge | 1.21.1 NeoForge |
|---|---|
| `net.minecraftforge.common.ForgeConfigSpec` | `net.neoforged.neoforge.common.ModConfigSpec` |
| `ForgeRegistries.BLOCKS/ITEMS` | `BuiltInRegistries.BLOCK/ITEM` |
| `net.minecraftforge.items.*` | `net.neoforged.neoforge.items.*` |
| `net.minecraftforge.common.MinecraftForge.EVENT_BUS` | `NeoForge.EVENT_BUS` |
| `net.minecraftforge.fml.*` | `net.neoforged.fml.*` |
| `net.minecraftforge.gametest.*` | `net.neoforged.neoforge.gametest.*` |
| `net.minecraftforge.common.ToolActions` | `ItemAbilities` |

## 3. 需要改语义的地方（重点看）

1. **`MaidMiningMod` 构造方式**
   Forge 用 `FMLJavaModLoadingContext.get()` + `ModLoadingContext.get().registerConfig(...)`；
   NeoForge 1.21 改为**构造器注入** `(IEventBus, ModContainer)`，配置走
   `modContainer.registerConfig(...)`。

2. **`DistExecutor` 已被移除**
   改用 `FMLEnvironment.dist == Dist.CLIENT` 判断侧别（客户端的配置界面注册仍只在客户端类里）。

3. **配置界面扩展点**
   `ConfigScreenHandler.ConfigScreenFactory` → `IConfigScreenFactory`，
   挂在 `ModContainer#registerExtensionPoint` 上。

4. **`Registry` 统一**
   `IForgeRegistry` 没了，`ConfigIdList` 改为操作原版 `Registry`：
   `getKeys()`→`keySet()`、`getValue(id)`→`get(id)`（缺失时回退默认值，所以要先 `containsKey`）、
   `tags().getTag(tag)`→`getTag(tag)` 返回 `Optional<HolderSet.Named>`，遍历拿到的是 `Holder`。

5. **附魔全面改为数据组件 + Holder**
   - `Enchantments.BLOCK_FORTUNE/BLOCK_EFFICIENCY` → `FORTUNE/EFFICIENCY`；
   - `Enchantments.X` 现在是 `ResourceKey<Enchantment>`，读写都要
     `registryAccess().holderOrThrow(key)` 转 `Holder`；
   - `ItemStack#getEnchantmentLevel` 移除 → `getTagEnchantments().getLevel(holder)`；
   - `ItemStack#getTag()` 移除 → `get(DataComponents.CUSTOM_DATA)`。

6. **`TieredItem` / `Tier#getLevel()` 双双移除**
   `ToolManager` 打分改为看 `DiggerItem#getTier()`，并按原版五档实例
   （木/石/铁/钻石/下界合金）映射等级；模组工具退回"按挖掘速度近似"。

7. **`hurtAndBreak` 与 `broadcastBreakEvent`**
   1.21 只剩 `hurtAndBreak(int, LivingEntity, EquipmentSlot)`，
   `LivingEntity#broadcastBreakEvent` 已被删除（装备同步由内部处理）。

8. **`BlockState#getTags()` / `ItemStack#getItemHolder().tags()`**
   标签遍历仍可用，只是入口从 `ForgeRegistries` 换到原版 `Holder`。

9. **GUI（1.20.2+ 的原版改动）**
   - `renderBackground(graphics)` → `renderBackground(graphics, mouseX, mouseY, partialTick)`；
   - `AbstractSelectionList` 构造器去掉 `bottom` 参数，
     改为 `(mc, width, listHeight, y, itemHeight)`；
   - 注册表标签枚举 `registry.tags().getTagNames()` → `registry.getTagNames()`。

10. **`IMaidTask#getMaidActionSummary()` 的语义修正**
    1.20.1 版返回 i18n key，但核对 TLM 1.5.3 源码后确认该字符串会被
    **原样拼进大模型提示词**（`SwitchWorkTaskTool`），因此改为英文硬编码描述，
    与 TLM 自带任务一致。这是本移植里唯一一处处行为性修改。

11. **GameTest**
    `@GameTestHolder` / `@PrefixGameTestTemplate` 在 NeoForge 包里；
    `Mob#finalizeSpawn` 去掉 `CompoundTag` 参数。
    `gameteststructures/*.snbt` 这套约定在 NeoForge 上依然有效。

## 4. 目录结构

```
maidmining-neoforge-1.21.1/
├── build.gradle              # ModDevGradle 2 配置（runs: client/server/gameTestServer/data）
├── gradle.properties         # 版本号与构建开关（neo_version / tlm_version 等）
├── libs/                     # TLM 编译依赖（jar 不入库，见 libs/README.md）
├── gameteststructures/       # GameTest 结构模板（empty5.snbt）
├── src/main/java/com/leyue/maidmining/
│   ├── MaidMiningMod.java    # 主类：配置注册、标签/配置缓存失效、挂配置界面
│   ├── MaidExtension.java    # @LittleMaidExtension，向 TLM 注册挖矿任务
│   ├── task/MiningTask.java  # IMaidTask：UID/图标/启用条件
│   ├── cfg/                  # ModConfigSpec、运行时门面、字符串→注册表解析
│   ├── mining/               # 状态机（SEARCH/DIG/MINE）、目标搜索、校验
│   ├── session/              # 会话状态（黑板）
│   ├── act/                  # 破坏、隧道推进、垂直移动、垫脚
│   ├── inv/                  # 工具选择、背包读写唯一出口
│   ├── client/               # 配置界面（纯客户端）
│   └── gametest/             # GameTest
└── src/main/resources/
    ├── META-INF/neoforge.mods.toml
    ├── assets/maidmining/lang/{zh_cn,en_us}.json
    └── pack.mcmeta           # pack_format 34，兼容 48（数据包）
```

## 5. 验证状态

`./gradlew runGameTestServer` 在开发机上全绿。

覆盖：破坏分类（基岩/流体/硬度）、副手定向（材料通道 + 方块通道）、
工具白名单/黑名单/正确工具规则、目标三档模式、`#标签` 与通配解析、
区块段下标换算、时运增产、精准采集保留方块、默认不耗耐久、物品守恒、展示位保护。

**尚未验证**：客户端界面（配置页/名单编辑页）只在编译层面通过，
需要在有图形环境时 `runClient` 手工点一遍。
