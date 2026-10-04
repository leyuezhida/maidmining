package com.leyue.maidmining.act;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.leyue.maidmining.MaidMiningMod;
import com.leyue.maidmining.cfg.MiningConfig;
import com.leyue.maidmining.inv.MaidInventory;
import com.leyue.maidmining.mining.BreakResult;
import com.leyue.maidmining.mining.MiningValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * 破坏方块的<b>唯一出口</b>（MM-303 / MM-303b）。
 * <p>
 * <b>为什么必须自己算掉落</b>：TLM 的 {@code EntityMaid.destroyBlock} 在字节码里<b>硬编码</b>
 * 把 {@code ItemStack.EMPTY} 传给 {@code dropResourcesToMaidInv}（已用 javap 核实）：
 * <pre>
 * 79: getstatic  ItemStack.EMPTY
 * 82: invokevirtual dropResourcesToMaidInv:(...Lnet/minecraft/world/item/ItemStack;)V
 * </pre>
 * 掉落表里的 {@code match_tool} 分支因此永远匹配不到 ⇒
 * <b>时运（Fortune）不增加产量、精准采集（Silk Touch）拿不到方块本身</b>，附魔等于没装。
 * <p>
 * <b>默认路径的做法</b>：
 * <ol>
 *   <li>自己调 {@link Block#getDrops} 传入<b>真实工具栈</b>拿到掉落；</li>
 *   <li>用 {@code maid.destroyBlock(level, pos, false, maid)} 做「无掉落破坏」
 *       （第三个参数 {@code drop=false}）—— 仍然走 TLM 的保护检查，
 *       {@code onEntityDestroyBlock} 与 mobGriefing 依旧有效；</li>
 *   <li>把掉落逐个塞进 {@code getAvailableInv(false)}，溢出部分落地。</li>
 * </ol>
 * 这样时运与精准采集按玩家规则生效，同时物品守恒（装多少扣多少，不复制）。
 * <p>
 * <b>兼容路径</b>（{@code compat.fakePlayerMode = FAKE_PLAYER}，默认关闭）：
 * 用 {@link FakePlayerFactory} 走玩家破坏路径，让绝大多数领地 mod
 * （挂 {@code BlockEvent.BreakEvent}）能真正拦截。
 * <p>
 * <b>耐久</b>：1.02 起默认<b>不消耗</b>（{@code dig.damageTool=false}）。
 * TLM 的 destroyBlock 本来就不扣耐久，这是本 mod 自己的加码，关掉它符合
 * 「女仆应能长期连续挖矿」的设定；需要时可在配置里打开。
 */
public final class DigExecutor {

    private DigExecutor() {
    }

    /**
     * 破坏一个方块，并按结果分类返回。
     * <p>
     * 顺序很重要：<b>先</b>做保护与可挖性检查，<b>后</b>改世界，
     * 任何一步失败都不会留下"方块没了但东西没给"或反之的中间态。
     */
    public static BreakResult dig(ServerLevel level, EntityMaid maid, BlockPos pos) {
        ItemStack tool = maid.getMainHandItem();

        // 0) 诊断：破坏失败时把完整上下文打出来，否则实机只能靠猜
        //    （实机曾出现"挖不到矿"的日志，分不清是保护、硬度还是掉落）
        boolean verbose = MiningConfig.logDebugEnabled();

        // 1) 世界侧判定：空气 / 基岩 / 流体 / 超硬
        BreakResult pre = MiningValidator.classifyDig(level, pos, tool);
        if (pre != BreakResult.SUCCESS) {
            logDigFailure(verbose, maid, pos, level, tool, "classify=" + pre);
            return pre;
        }

        // 2) 保护判定：onEntityDestroyBlock + mobGriefing（MM-301）
        //    两种模式都先问 maid，否则切到 FakePlayer 就等于绕过所有 TLM 侧保护规则。
        if (!maid.canDestroyBlock(pos)) {
            logDigFailure(verbose, maid, pos, level, tool, "canDestroyBlock=false (protection or mobGriefing)");
            return BreakResult.PROTECTED;
        }

        // 3) 真正破坏
        return MiningConfig.useFakePlayer()
                ? digAsPlayer(level, maid, pos, tool)
                : digAsMaid(level, maid, pos, tool);
    }

    /**
     * 破坏失败的诊断日志。
     * <p>
     * <b>为什么要打工具栈的完整信息</b>：附魔类问题（时运/精准采集）无法从
     * "挖到 1 个铁粒"这种外部现象判断是附魔没读到、还是掉落表被改、还是根本没挖到。
     * 实机踩过的坑：日志里只有 "Mined"，看不出用的哪把镐、带没带附魔。
     */
    private static void logDigFailure(boolean verbose, EntityMaid maid, BlockPos pos,
                                      ServerLevel level, ItemStack tool, String reason) {
        if (!verbose) {
            return;
        }
        MaidMiningMod.LOGGER.info(
                "[MaidMining][DIAG-dig] {} ({},{},{}) reason={} block={} tool={} toolNbt={} silk={} fortune={}",
                "dig-failed",
                pos.getX(), pos.getY(), pos.getZ(),
                reason,
                ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock()),
                ForgeRegistries.ITEMS.getKey(tool.getItem()),
                tool.getTag(),
                tool.getEnchantmentLevel(Enchantments.SILK_TOUCH),
                tool.getEnchantmentLevel(Enchantments.BLOCK_FORTUNE));
    }

    /**
     * 破坏成功时的诊断：把实际产出的物品打出来。
     * <p>
     * 这是验证"精准采集 / 时运是否生效"的<b>唯一可靠办法</b>——
     * 玩家从背包里翻物品时可能把不同来源的产物混在一起，
     * 而这里能确切地说明"这一次破坏、这一把镐、产出了什么"。
     */
    private static void logDigSuccess(EntityMaid maid, BlockPos pos, ItemStack tool,
                                      List<ItemStack> drops, boolean silkPresent) {
        if (!MiningConfig.logDebugEnabled()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(ForgeRegistries.ITEMS.getKey(drop.getItem()))
                    .append(" x").append(drop.getCount());
        }
        MaidMiningMod.LOGGER.info(
                "[MaidMining][DIAG-drops] pos=({},{},{}) tool={} silk={} drops=[{}]",
                pos.getX(), pos.getY(), pos.getZ(),
                ForgeRegistries.ITEMS.getKey(tool.getItem()),
                silkPresent,
                sb);
    }

    /**
     * 默认路径：女仆破坏 + <b>自己算掉落</b>，让附魔生效。
     */
    private static BreakResult digAsMaid(ServerLevel level, EntityMaid maid, BlockPos pos, ItemStack tool) {
        if (!MiningConfig.useRealToolForDrops()) {
            // 兼容模式：完全走 TLM 老路径（附魔不生效，但行为与 1.0.x 一致）
            boolean ok = maid.destroyBlock(pos);
            afterDig(maid, tool);
            return ok ? BreakResult.SUCCESS : BreakResult.TEMPORARY;
        }

        BlockState state = level.getBlockState(pos);
        // 关键：传入真实工具栈 —— 时运 / 精准采集 / 工具等级都在这一步生效
        List<ItemStack> drops = Block.getDrops(state, level, pos, null, maid, tool);

        // 无掉落破坏：避免与下面的手动交付重复给物品
        if (!maid.destroyBlock(level, pos, false, maid)) {
            logDigFailure(MiningConfig.logDebugEnabled(), maid, pos, level, tool,
                    "destroyBlock returned false (TLM path refused)");
            return BreakResult.TEMPORARY;
        }

        logDigSuccess(maid, pos, tool, drops,
                tool.getEnchantmentLevel(Enchantments.SILK_TOUCH) >= 1);
        deliver(level, maid, pos, drops, state, tool);
        afterDig(maid, tool);
        return BreakResult.SUCCESS;
    }

    /**
     * 兼容路径：用 FakePlayer 走玩家破坏代码路径（MM-303b，默认关闭）。
     * <p>
     * 好处：{@code BlockEvent.BreakEvent} <b>只在玩家路径发布</b>
     * （{@code ForgeHooks.onBlockBreakEvent} 全工程只有 {@code ServerPlayerGameMode} 一个调用点），
     * 所以只有这个模式下领地保护才真正拦得住。
     * <p>
     * 代价：假玩家会触发统计/进度等副作用，因此默认关闭，由服务器管理员按需开启。
     */
    private static BreakResult digAsPlayer(ServerLevel level, EntityMaid maid, BlockPos pos, ItemStack tool) {
        FakePlayer fakePlayer = FakePlayerFactory.getMinecraft(level);
        if (fakePlayer == null) {
            return BreakResult.TEMPORARY;
        }
        // 让假玩家手持同一把镐：时运/精准采集/工具等级判定都按这把镐来
        ItemStack previous = fakePlayer.getMainHandItem();
        fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, tool.copy());
        try {
            // gameMode.destroyBlock 内部会发 BlockEvent.BreakEvent 并走 canHarvestBlock
            if (!fakePlayer.gameMode.destroyBlock(pos)) {
                return BreakResult.TEMPORARY;
            }
            takeFromFakePlayer(level, maid, pos, fakePlayer);
        } finally {
            fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, previous);
        }
        afterDig(maid, tool);
        return BreakResult.SUCCESS;
    }

    /**
     * 把假玩家背包里的东西转移给女仆，溢出落地，并清空源槽。
     * <p>
     * 假玩家是<b>跨次调用共享的实例</b>（{@code FakePlayerFactory} 内部按存档缓存），
     * 所以必须清回原状，否则别处的物品会被我们顺手搬走 —— 那就是物品丢失。
     */
    private static void takeFromFakePlayer(ServerLevel level, EntityMaid maid, BlockPos pos,
                                           FakePlayer fakePlayer) {
        // 玩家主背包：0-35 为 items，其余是护甲与副手槽（挖矿产物不会落在那里）
        for (int slot = 0; slot < fakePlayer.getInventory().items.size(); slot++) {
            ItemStack stack = fakePlayer.getInventory().items.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            // 物品守恒：整叠进背包，装不下的落地，然后把源槽清空
            ItemStack remainder = MaidInventory.insert(maid, stack.copy());
            if (!remainder.isEmpty()) {
                Block.popResource(level, pos, remainder);
            }
            fakePlayer.getInventory().items.set(slot, ItemStack.EMPTY);
        }
    }

    /**
     * 把算好的掉落交给女仆：能进背包的进背包，装不下的落地。
     * <p>
     * <b>物品守恒</b>：{@code insertItemStacked} 返回 remainder，我们只把 remainder 落地，
     * 不额外复制 —— 这是 MM-501 复制漏洞的同类修复。
     */
    private static void deliver(ServerLevel level, EntityMaid maid, BlockPos pos,
                                List<ItemStack> drops, BlockState state, ItemStack tool) {
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) {
                continue;
            }
            ItemStack remainder = MaidInventory.insert(maid, drop);
            if (!remainder.isEmpty()) {
                Block.popResource(level, pos, remainder);
            }
        }
        // 经验球照常掉落（与玩家破坏一致）；传真实工具栈，精准采集的 XP 规则才正确
        if (!state.isAir()) {
            state.spawnAfterBreak(level, pos, tool, true);
        }
    }

    /** 破坏成功后的收尾：挥臂 + 可选耐久。 */
    private static void afterDig(EntityMaid maid, ItemStack tool) {
        if (MiningConfig.damageTool() && !tool.isEmpty()) {
            // 回调广播破坏事件，客户端才有音效/动画（与 vanilla DiggerItem、TLM TaskSnow 同款）
            tool.hurtAndBreak(1, maid, e -> e.broadcastBreakEvent(InteractionHand.MAIN_HAND));
            if (tool.isEmpty()) {
                MaidMiningMod.LOGGER.warn("[MaidMining] Pickaxe broke, maid={}", maid.getId());
            }
        }
        maid.swing(InteractionHand.MAIN_HAND);
    }
}
