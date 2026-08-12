package com.leyue.maidmining.task;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.Lists;
import com.leyue.maidmining.MaidMiningMod;
import com.leyue.maidmining.mining.MiningTunnelBehavior;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
 * 职责：提供任务标识、图标、显示名称，并把挖矿行为装配进女仆大脑。
 */
public class MiningTask implements IMaidTask {
    public static final ResourceLocation UID = new ResourceLocation(MaidMiningMod.MOD_ID, "mining");

    private static final String NAME_EN = "Mining";
    private static final String NAME_ZH = "\u6316\u77ff";
    private static final String DESC_EN = "Mine ores within a 3\u00d73 chunk radius. A pickaxe is required; put raw ore in offhand to target specific types.";
    private static final String DESC_ZH = "\u81ea\u52a8\u641c\u7d22\u5468\u56f4 3\u00d73 \u533a\u5757\u5185\u7684\u77ff\u77f3\uff0c\u9700\u8981\u9550\u5b50\uff0c\u526f\u624b\u653e\u539f\u77ff\u53ef\u6307\u5b9a\u53ea\u6316\u5bf9\u5e94\u7c7b\u578b\u3002";

    private static boolean isZh() {
        try {
            String lang = Minecraft.getInstance().getLanguageManager().getSelected();
            return "zh_cn".equals(lang) || "zh_tw".equals(lang);
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public ResourceLocation getUid() { return UID; }

    @Override
    public ItemStack getIcon() { return new ItemStack(Items.IRON_PICKAXE); }

    @Override
    public MutableComponent getName() {
        String key = "task.maidmining.mining";
        String fromLang = I18n.get(key);
        if (!key.equals(fromLang)) return Component.literal(fromLang);
        return isZh() ? Component.literal(NAME_ZH) : Component.literal(NAME_EN);
    }

    @Override
    public List<String> getDescription(EntityMaid maid) {
        String key = "task.maidmining.mining.desc";
        String fromLang = I18n.get(key);
        if (!key.equals(fromLang)) return List.of(fromLang);
        return List.of(isZh() ? DESC_ZH : DESC_EN);
    }

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
