package com.ae2rsbridge.item;

import com.ae2rsbridge.cell.CellFilter;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
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
 * 之后把单元放进 ME 驱动器，AE2 即以原生存储单元形式<b>读取并写入</b>该 RS 网络内容。
 * <p>
 * <b>单向桥接</b>：仅 AE 网络能访问 RS 网络（读 / 写）。RS 网络无法反向访问 AE 网络。
 * 绑定时会同时记录 RS 方块所在的<b>维度</b>，以便单元在驱动器挂载时正确定位 RS 网络。
 */
public class RSNetworkStorageCellItem extends Item {

    private static final Logger LOGGER = LoggerFactory.getLogger(RSNetworkStorageCellItem.class);
    private static final String BOUND_KEY = "boundRsBlock";
    private static final String BOUND_DIM_KEY = "boundRsDim";

    private final CellFilter filter;

    public RSNetworkStorageCellItem(Properties properties, CellFilter filter) {
        super(properties);
        this.filter = filter;
    }

    /** 该单元向 AE2 暴露 RS 资源的过滤策略。 */
    public CellFilter getFilter() {
        return filter;
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

    /** 绑定 RS 方块所在的维度（以 ResourceLocation 字符串形式存储，解析时还原）。 */
    @Nullable
    public static ResourceLocation getBoundDimension(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = cd == null ? null : cd.getUnsafe();
        if (tag == null || !tag.contains(BOUND_DIM_KEY)) {
            return null;
        }
        return ResourceLocation.parse(tag.getString(BOUND_DIM_KEY));
    }

    public static void setBoundRsBlock(ItemStack stack, BlockPos pos, ResourceLocation dim) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = cd == null ? new CompoundTag() : cd.getUnsafe();
        tag.putLong(BOUND_KEY, pos.asLong());
        tag.putString(BOUND_DIM_KEY, dim.toString());
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
            // 记录方块坐标 + 所在维度，供单元在驱动器挂载时（跨维度）正确定位 RS 网络。
            setBoundRsBlock(held, clicked, level.dimension().location());
            player.sendSystemMessage(Component.literal(
                    "[RS Network Cell] 已绑定到 RS 网络方块 " + clicked.toShortString()
                            + " @ " + level.dimension().location()));
            LOGGER.info("[rs2ae_cell] cell bound to RS network block at {} (dim={})",
                    clicked, level.dimension().location());
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
            TooltipFlag flag) {
        BlockPos bound = getBoundRsBlock(stack);
        if (filter == CellFilter.NON_STACKABLE) {
            tooltip.add(Component.literal("[不可堆叠专用] 仅向 AE2 暴露 RS 中的不可堆叠物品")
                    .withStyle(ChatFormatting.GOLD));
        } else {
            tooltip.add(Component.literal("[全类型] 向 AE2 暴露 RS 中的全部物品与流体")
                    .withStyle(ChatFormatting.GREEN));
        }
        if (bound == null) {
            tooltip.add(Component.literal("未绑定 RS 网络 — 潜行右键 RS 线缆/控制器进行绑定")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.literal("已绑定 RS 网络方块: " + bound.toShortString())
                    .withStyle(ChatFormatting.AQUA));
            tooltip.add(Component.literal("放入 ME 驱动器即可让 AE2 读取并写入 RS（单向桥接：仅 AE 访问 RS）")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
