package com.ae2rsbridge.item;

import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * RS 网络存储单元物品。
 * <p>
 * 玩家<b>潜行 + 右键</b>任意 RS 网络方块（控制器 / 线缆等）将其绑定到该 RS 网络；
 * 之后把单元放进 ME 驱动器，AE2 即以原生存储单元形式读取该 RS 网络内容（只读）。
 */
public class RSNetworkStorageCellItem extends Item {

    private static final Logger LOGGER = LoggerFactory.getLogger(RSNetworkStorageCellItem.class);
    private static final String BOUND_KEY = "boundRsBlock";

    public RSNetworkStorageCellItem(Properties properties) {
        super(properties);
    }

    @Nullable
    public static BlockPos getBoundRsBlock(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = cd == null ? null : cd.getUnsafe();
        if (tag == null || !tag.contains(BOUND_KEY)) {
            return null;
        }
        return BlockPos.of(tag.getLong(BOUND_KEY));
    }

    public static void setBoundRsBlock(ItemStack stack, BlockPos pos) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = cd == null ? new CompoundTag() : cd.getUnsafe();
        tag.putLong(BOUND_KEY, pos.asLong());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static boolean hasBinding(ItemStack stack) {
        return getBoundRsBlock(stack) != null;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide()) {
            return InteractionResult.PASS;
        }
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        BlockPos clicked = context.getClickedPos();
        // 仅当点击的方块是 RS 网络方块（提供 NetworkNodeContainerProvider 能力）时才绑定
        var cap = RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability();
        if (level.getCapability(cap, clicked, context.getClickedFace()) != null) {
            ItemStack held = context.getItemInHand();
            setBoundRsBlock(held, clicked);
            player.sendSystemMessage(Component.literal(
                    "[RS Network Cell] 已绑定到 RS 网络方块 " + clicked.toShortString()));
            LOGGER.info("[rs2ae-cell] cell bound to RS network block at " + clicked);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
            TooltipFlag flag) {
        BlockPos bound = getBoundRsBlock(stack);
        if (bound == null) {
            tooltip.add(Component.literal("未绑定 RS 网络 — 潜行右键 RS 线缆/控制器进行绑定")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.literal("已绑定 RS 网络方块: " + bound.toShortString())
                    .withStyle(ChatFormatting.AQUA));
            tooltip.add(Component.literal("放入 ME 驱动器即可让 AE2 读取 RS（只读）")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
