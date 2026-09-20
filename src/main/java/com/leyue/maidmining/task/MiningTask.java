package com.leyue.maidmining.task;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.Lists;
import com.leyue.maidmining.MaidMiningMod;
import com.leyue.maidmining.mining.MiningTunnelBehavior;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * 女仆“挖矿”任务定义。
 * <p>
 * 职责：提供任务标识、图标，并把挖矿行为装配进女仆大脑。
 * <p>
 * <b>刻意不覆写 {@code getName()} / {@code getDescription()}</b>：
 * TLM 的默认实现会由 UID 自动生成 i18n key（{@code task.maidmining.mining} 与
 * {@code task.maidmining.mining.desc}）并返回可翻译组件 / key 列表，由 GUI 端翻译，
 * 中英文案只需维护语言文件这一份事实源。
 * <p>
 * 旧版（v1.0.0）覆写了这两个方法，并在里面直接调用 {@code net.minecraft.client.*}
 * （{@code Minecraft} / {@code I18n}）：这在<b>专用服务器</b>上是客户端类，
 * 一旦服务端走到这条路径就会 {@code NoClassDefFoundError}，
 * 而旧代码只 {@code catch (Exception)}，捕不到 {@code Error}。
 * 详见 OPTIMIZATION.md 的 MM-901 / MM-902。
 */
public class MiningTask implements IMaidTask {
    public static final ResourceLocation UID = ResourceLocation.tryBuild(MaidMiningMod.MOD_ID, "mining");

    @Override
    public ResourceLocation getUid() { return UID; }

    @Override
    public ItemStack getIcon() { return new ItemStack(Items.IRON_PICKAXE); }

    @Override
    public List<Pair<String, Predicate<EntityMaid>>> getConditionDescription(EntityMaid maid) {
        return Collections.emptyList();
    }

    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) { return null; }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        return Lists.newArrayList(Pair.of(1, new MiningTunnelBehavior()));
    }
}
