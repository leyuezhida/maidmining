package com.leyue.maidmining;

import com.leyue.maidmining.cfg.MiningConfig;
import com.leyue.maidmining.cfg.MiningConfigSpec;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maid Mining 主类（Minecraft 1.21.1 / NeoForge 21.1）。
 * <p>
 * 与 1.20.1 Forge 版的差异（移植说明）：
 * <ul>
 *   <li>构造参数由 {@code FMLJavaModLoadingContext.get()} 改为 NeoForge 的<b>构造器注入</b>
 *       （{@link IEventBus} + {@link ModContainer}），这是 NeoForge 1.21 起的标准写法；</li>
 *   <li>配置注册从 {@code ModLoadingContext.get().registerConfig(...)} 改为
 *       {@code modContainer.registerConfig(...)}；</li>
 *   <li>{@code DistExecutor} 已从 NeoForge 移除，改用 {@link FMLEnvironment#dist} 做侧别判断
 *       —— 非客户端分支里不会触碰任何客户端类，专用服务器上安全。</li>
 * </ul>
 */
@Mod(MaidMiningMod.MOD_ID)
public class MaidMiningMod {
    public static final String MOD_ID = "maidmining";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public MaidMiningMod(IEventBus modBus, ModContainer modContainer) {
        // 1.1.0 起改为 COMMON：落在 config/maidmining-common.toml，
        // 而不是每个存档一份的 world/serverconfig/。这样玩家能在 Mods 界面里直接改，
        // 也方便把配置抄给别的存档/服务器。
        modContainer.registerConfig(ModConfig.Type.COMMON, MiningConfigSpec.SPEC);
        modBus.addListener(this::commonSetup);
        // 配置改动后要让"字符串 → 集合"的缓存失效（MM-1101），否则改白名单得重启
        modBus.addListener((ModConfigEvent.Loading event) -> onConfigChanged(event.getConfig()));
        modBus.addListener((ModConfigEvent.Reloading event) -> onConfigChanged(event.getConfig()));
        // 客户端才注册配置界面；这个判断保证专用服务器上不会加载客户端类
        if (FMLEnvironment.dist == Dist.CLIENT) {
            com.leyue.maidmining.client.ConfigScreenRegistration.register(modContainer);
        }
        // 标签重载后要清掉矿石/建材缓存（MM-103 / MM-702）
        NeoForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // 注意：这里<b>不能</b>读配置 —— COMMON_SETUP 早于配置加载完成，
        // 任何 MiningConfig 访问都会抛 "Cannot get config value before config is loaded"。
        // 实机验证过这个时序，因此只做不需要配置的登记。
        MaidMiningMod.LOGGER.info("[MaidMining] Common setup done, waiting for server config");
    }

    /**
     * 标签更新后让缓存失效。
     * <p>
     * {@code tags/blocks/*.json} 与 {@code tags/items/*.json} 改动（数据包重载或
     * {@code /reload})之后，矿石识别缓存与建材白/黑名单缓存都必须重建，
     * 否则新装的模组矿石要重启游戏才认得（MM-103 / MM-702）。
     */
    @SubscribeEvent
    public void onTagsUpdated(final TagsUpdatedEvent event) {
        MiningConfig.invalidateCaches();
        com.leyue.maidmining.mining.MiningValidator.invalidateCaches();
    }

    /**
     * 配置加载/重载后清缓存（1.1.0，MM-1101）。
     * <p>
     * 工具白名单、目标白/黑名单在配置里是字符串，运行时被解析成集合缓存起来。
     * 不清的话，服务器管理员改完 {@code maidmining-common.toml} 得重启才生效。
     * <p>
     * 只处理自己的 spec：其他模组的配置事件与这里无关。
     */
    private void onConfigChanged(final ModConfig config) {
        if (config.getSpec() != MiningConfigSpec.SPEC) {
            return;
        }
        MiningConfig.invalidateCaches();
        com.leyue.maidmining.mining.MiningValidator.invalidateCaches();
        MaidMiningMod.LOGGER.info("[MaidMining] Config reloaded, tool/target caches cleared");
    }
}
