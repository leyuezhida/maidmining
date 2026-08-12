package com.leyue.maidmining.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * 目标方块的校验规则：
 * 1. 必须是矿石；
 * 2. 必须能被当前手持镐子（挖掘等级）正确破坏。
 * <p>
 * 注意：某些环境下 {@code #minecraft:ores} 标签可能无法命中，
 * 因此矿石识别采用「标签优先 + 方块注册名兜底」的双保险策略。
 */
public final class MiningValidator {
    /** 矿石方块标签，覆盖原版 + Forge + Fabric 三方矿石。 */
    public static final TagKey<Block> ORES_TAG = TagKey.create(Registries.BLOCK, new ResourceLocation("minecraft", "ores"));
    public static final TagKey<Block> FORGE_ORES_TAG = TagKey.create(Registries.BLOCK, new ResourceLocation("forge", "ores"));
    public static final TagKey<Block> C_ORES_TAG = TagKey.create(Registries.BLOCK, new ResourceLocation("c", "ores"));

    /** 目标站立点周围需要检测的偏移（覆盖脚下、头顶、四周墙壁与地板矿石）。 */
    private static final BlockPos[] NEARBY_OFFSETS = {
            new BlockPos(0, 0, 0), new BlockPos(0, 1, 0), new BlockPos(0, -1, 0),
            new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0), new BlockPos(0, 0, 1), new BlockPos(0, 0, -1),
            new BlockPos(1, 1, 0), new BlockPos(-1, 1, 0), new BlockPos(0, 1, 1), new BlockPos(0, 1, -1),
    };

    private MiningValidator() {
    }

    /** 是否为矿石方块（minecraft/forge/fabric 标签 + 注册名 _ore 结尾兜底）。 */
    public static boolean isOre(BlockState state) {
        if (state.is(ORES_TAG) || state.is(FORGE_ORES_TAG) || state.is(C_ORES_TAG)) {
            return true;
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return key != null && key.getPath().endsWith("_ore");
    }

    /** 手持物品是否为镐子（#minecraft:pickaxes 标签 + ToolActions 兜底）。 */
    public static boolean isPickaxe(ItemStack tool) {
        return !tool.isEmpty() && (tool.is(ItemTags.PICKAXES)
                || tool.canPerformAction(ToolActions.PICKAXE_DIG));
    }

    /**
     * 当前工具能否正确破坏该方块。
     * 内部会校验：方块可用镐子挖掘，且镐子的挖掘等级 ≥ 方块要求等级
     * （即石镐挖不了钻石矿、木镐挖不了铁矿这类规则）。
     */
    public static boolean canBreak(BlockState state, ItemStack tool) {
        if (state.isAir()) {
            return false;
        }
        if (!isPickaxe(tool)) {
            return false;
        }
        return tool.isCorrectToolForDrops(state);
    }

    /** 指定位置是否为「可挖的矿石」（不限定类型）。 */
    public static boolean isOreBreakable(ServerLevel level, BlockPos pos, ItemStack tool) {
        return isOreBreakable(level, pos, tool, ItemStack.EMPTY);
    }

    /**
     * 指定位置是否为「可挖的矿石」。
     * 若副手指定了原矿石，则只匹配对应类型的矿石（如副手放粗铁/铁矿石 → 只挖铁矿）。
     */
    public static boolean isOreBreakable(ServerLevel level, BlockPos pos, ItemStack tool, ItemStack offhand) {
        BlockState state = level.getBlockState(pos);
        return isOre(state) && isTargetedOre(state, offhand) && canBreak(state, tool);
    }

    /** 根据副手物品返回要定向挖掘的矿石类型名（如 "iron"、"diamond"），未指定返回 null。 */
    public static String targetOreName(ItemStack offhand) {
        if (offhand.isEmpty()) {
            return null;
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(offhand.getItem());
        if (key == null) {
            return null;
        }
        String path = key.getPath();
        // 粗铁/粗金/粗铜 → raw_iron → iron
        if (path.startsWith("raw_")) {
            return path.substring(4);
        }
        // 铁矿石/深层铁矿石 → 去掉 _ore 与 deepslate_ 前缀 → iron
        if (path.endsWith("_ore")) {
            String base = path.substring(0, path.length() - 4);
            if (base.startsWith("deepslate_")) {
                return base.substring("deepslate_".length());
            }
            return base;
        }
        return null;
    }

    /** 该方块是否属于副手指定的矿石类型（副手为空则匹配所有矿石）。 */
    public static boolean isTargetedOre(BlockState state, ItemStack offhand) {
        String target = targetOreName(offhand);
        if (target == null) {
            return true;
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (key == null) {
            return false;
        }
        String path = key.getPath();
        if (path.endsWith("_ore")) {
            String base = path.substring(0, path.length() - 4);
            if (base.startsWith("deepslate_")) {
                base = base.substring("deepslate_".length());
            }
            return base.equals(target);
        }
        return false;
    }

    /** 目标站立点周围是否存在可挖的矿石。 */
    public static boolean hasOreNear(ServerLevel level, BlockPos standPos, ItemStack tool) {
        for (BlockPos offset : NEARBY_OFFSETS) {
            if (isOreBreakable(level, standPos.offset(offset), tool)) {
                return true;
            }
        }
        return false;
    }

    /** 返回目标站立点周围全部可挖矿石的位置。 */
    public static List<BlockPos> orePositionsNear(ServerLevel level, BlockPos standPos, ItemStack tool) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos offset : NEARBY_OFFSETS) {
            BlockPos pos = standPos.offset(offset);
            if (isOreBreakable(level, pos, tool)) {
                result.add(pos);
            }
        }
        return result;
    }

    /**
     * 该方块是否可以被女仆挖开（用于隧道挖掘）。
     * 规则：非空气、非基岩、非液体，且硬度在可挖范围内（< 50，即基岩以下的普通方块）。
     */
    public static boolean isDiggable(ServerLevel level, BlockPos pos, ItemStack tool) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getBlock() == net.minecraft.world.level.block.Blocks.BEDROCK) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        float speed = state.getDestroySpeed(level, pos);
        return speed >= 0.0F && speed < 50.0F;
    }

    /** 矿石周围 3x3x3 是否有基岩。基岩不可破坏，靠近基岩的矿无法挖到，应直接跳过。 */
    public static boolean isNearBedrock(ServerLevel level, BlockPos orePos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (level.getBlockState(orePos.offset(dx, dy, dz))
                            .getBlock() == net.minecraft.world.level.block.Blocks.BEDROCK) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
