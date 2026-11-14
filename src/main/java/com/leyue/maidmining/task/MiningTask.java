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
     * 启用条件（MM-903 / MM-1102）。
     * <p>
     * TLM 会为每个条件生成文案键 {@code task.<ns>.<path>.condition.<name>}。
     * 1.1.0 起工具集合由配置决定，不再只限镐子，所以条件名从 {@code pickaxe}
     * 改成 {@code tool}，文案也相应泛化为"需要工具"。
     */
    @Override
    public List<Pair<String, Predicate<EntityMaid>>> getEnableConditionDesc(EntityMaid maid) {
        return Lists.newArrayList(
                Pair.of("tool", m -> MiningValidator.isTool(m.getMainHandItem())
                        || hasToolInBackpack(m)));
    }

    private static boolean hasToolInBackpack(EntityMaid maid) {
        var inv = maid.getMaidInv();
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            if (slot == MaidInventory.DISPLAY_SLOT) {
                continue;
            }
            if (MiningValidator.isTool(inv.getStackInSlot(slot))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 给 AI 助手看的任务摘要（MM-804）。
     * <p>
     * <b>1.21.1 移植修正</b>：1.20.1 版这里返回的是 i18n key
     * （{@code maidmining.maid_action_summary}），但核对 TLM 1.5.3 源码后确认
     * 这个字符串会被<b>原样拼进大模型的提示词</b>
     * （{@code SwitchWorkTaskTool#getTaskIdParameterDesc}：{@code "- %s: %s"}），
     * 返回 key 等于把 "maidmining.maid_action_summary" 直接喂给模型。
     * <p>
     * {@code IMaidTask} 的文档明确要求这里返回"适合大模型理解的英文硬编码描述"，
     * 因此改为英文句子，与 TLM 自带任务（{@code "Melee attack entities"} 等）保持一致。
     */
    @Override
    public String getMaidActionSummary() {
        return "Mine ores automatically: search nearby ores, dig a tunnel to reach them, "
                + "and collect the drops. Requires a pickaxe or other configured tool in hand or backpack.";
    }
}
