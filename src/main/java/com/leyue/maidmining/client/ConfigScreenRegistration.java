package com.leyue.maidmining.client;

import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * 把本模组的配置界面挂到 NeoForge 的扩展点上。
 * <p>
 * <b>与 1.20.1 Forge 版的差异</b>：Forge 用的是
 * {@code ConfigScreenHandler.ConfigScreenFactory} + {@code ModLoadingContext}；
 * NeoForge 1.21 改为 {@link IConfigScreenFactory} 扩展点，并且挂在
 * {@link ModContainer} 上（构造器注入拿到）。效果一样：Mods 列表里的
 * 「Config」按钮打开我们的界面。
 * <p>
 * 这个类只被 {@code MaidMiningMod} 在 {@code Dist.CLIENT} 分支里调用，
 * 因此专用服务器上不会被加载（里面全是客户端类）。
 */
public final class ConfigScreenRegistration {

    private ConfigScreenRegistration() {
    }

    /** 只能在模组构造期调用（此时 ModContainer 才有效）。 */
    public static void register(ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (container, parent) -> new MiningConfigScreen(parent));
    }
}
