package com.leyue.maidmining;

import com.leyue.maidmining.cfg.MiningConfig;
import com.leyue.maidmining.cfg.MiningConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
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
        // MM-701：参数从 static final 常量改为 SERVER 配置，玩家可在游戏内调整
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, MiningConfigSpec.SPEC);
        modBus.addListener(this::commonSetup);
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
}
