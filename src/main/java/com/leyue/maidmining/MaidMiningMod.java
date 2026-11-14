package com.leyue.maidmining;

import com.leyue.maidmining.cfg.MiningConfig;
import com.leyue.maidmining.cfg.MiningConfigSpec;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.ModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main mod class for Maid Mining.
 * A Touhou Little Maid extension that adds autonomous ore mining task.
 */
@Mod(MaidMiningMod.MOD_ID)
public class MaidMiningMod {
    public static final String MOD_ID = "maidmining";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @SuppressWarnings("removal") // ModLoadingContext.get() 在新版标记删除，但 1.20.1 上仍是标准注册方式
    public MaidMiningMod() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        // 1.1.0 起改为 COMMON：落在 config/maidmining-common.toml，
        // 而不是每个存档一份的 world/serverconfig/。这样玩家能在 Mods 界面里直接改，
        // 也方便把配置抄给别的存档/服务器。
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, MiningConfigSpec.SPEC);
        modBus.addListener(this::commonSetup);
        // 配置改动后要让"字符串 → 集合"的缓存失效（MM-1101），否则改白名单得重启
        modBus.addListener((ModConfigEvent.Loading event) -> onConfigChanged(event.getConfig()));
        modBus.addListener((ModConfigEvent.Reloading event) -> onConfigChanged(event.getConfig()));
        // 客户端才注册配置界面；DistExecutor 保证专用服务器上不会加载客户端类
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> com.leyue.maidmining.client.ConfigScreenRegistration::register);
        // 标签重载后要清掉矿石/建材缓存（MM-103 / MM-702）
        MinecraftForge.EVENT_BUS.register(this);
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
     * 不清的话，服务器管理员改完 {@code maidmining-server.toml} 得重启才生效——
     * 而 Forge 的配置文件监视器本来就会触发 {@code Reloading}，没理由浪费它。
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
