package com.leyue.maidmining.client;

import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;

/**
 * 把本模组的配置界面挂到 Forge 的扩展点上。
 * <p>
 * <b>为什么需要这个类</b>：Forge 1.20.1 <b>不提供</b>通用配置界面，
 * Mods 界面里的「Config」按钮只在模组注册了
 * {@link ConfigScreenHandler.ConfigScreenFactory} 时才出现。
 * 这个类只被 {@code MaidMiningMod} 通过 {@code DistExecutor} 在客户端调用，
 * 因此专用服务器上不会被加载（里面全是客户端类）。
 */
public final class ConfigScreenRegistration {

    private ConfigScreenRegistration() {
    }

    /** 只能在模组构造期调用（ModLoadingContext 此时才有效）。 */
    @SuppressWarnings("removal") // ModLoadingContext.get() 在新版本被标记删除，1.20.1 上仍是标准写法
    public static void register() {
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parent) -> new MiningConfigScreen(parent)));
    }
}
