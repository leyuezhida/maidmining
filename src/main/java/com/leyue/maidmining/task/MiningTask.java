package com.leyue.maidmining.task;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.Lists;
import com.leyue.maidmining.MaidMiningMod;
import com.leyue.maidmining.inv.MaidInventory;
import com.leyue.maidmining.mining.MiningTunnelBehavior;
import com.leyue.maidmining.mining.MiningValidator;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
    public SoundEvent getAmbientSound(EntityMaid maid) { return null; }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        return Lists.newArrayList(Pair.of(1, new MiningTunnelBehavior()));
    }

    /**
     * 启用条件（MM-903）。
     * <p>
     * TLM 会为每个条件生成文案键 {@code task.<ns>.<path>.condition.<name>}，
     * 这里正好把 1.0.x 就写下但一直没被引用的 {@code ...condition.pickaxe} 键接上。
     */
    @Override
    public List<Pair<String, Predicate<EntityMaid>>> getEnableConditionDesc(EntityMaid maid) {
        return Lists.newArrayList(
                Pair.of("pickaxe", m -> MiningValidator.isPickaxe(m.getMainHandItem())
                        || hasPickaxeInBackpack(m)));
    }

    private static boolean hasPickaxeInBackpack(EntityMaid maid) {
        var inv = maid.getMaidInv();
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            if (slot == MaidInventory.DISPLAY_SLOT) {
                continue;
            }
            if (MiningValidator.isPickaxe(inv.getStackInSlot(slot))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 让玩家能在 TLM 女仆界面里看到女仆当前在干什么（MM-804）。
     * <p>
     * TLM 默认返回 {@code getUid().getPath()}（即 "mining"），玩家看不懂；
     * 这里返回可翻译的文案，中英双语只维护在语言文件这一份事实源。
     */
    @Override
    public String getMaidActionSummary() {
        return getUid().getNamespace() + ".maid_action_summary";
    }
}
