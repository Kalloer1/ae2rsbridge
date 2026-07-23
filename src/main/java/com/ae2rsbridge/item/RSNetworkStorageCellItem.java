package com.ae2rsbridge.item;

import com.ae2rsbridge.cell.CellFilter;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * RS 网络存储单元物品（<b>单一磁盘</b>，可切换存入模式）。
 * <p>
 * 交互：
 * <ul>
 *   <li><b>潜行 + 右键</b>任意 RS 网络方块（控制器 / 线缆等）→ 绑定到该 RS 网络。</li>
 *   <li><b>对空气右键</b>（不潜行）→ 切换存入模式：全部物品 ↔ 仅不可堆叠物品。</li>
 * </ul>
 * 之后把单元放进 ME 驱动器，AE2 即以原生存储单元形式<b>读取并写入</b>该 RS 网络内容。
 * <p>
 * <b>模式（存入限制，读取永远全部）</b>：
 * <ul>
 *   <li>默认「全部」模式：读取并写入 RS 中的一切物品/流体。</li>
 *   <li>「仅不可堆叠」模式：<b>照样能读取 RS 里的全部物品</b>，但<b>存入</b>时只接受不可堆叠物品
 *       （工具 / 盔甲 / 附魔书等 {@code maxStackSize<=1}）。即仅比默认模式多一个存入限制。</li>
 * </ul>
 * 模式存于物品 NBT（{@code CUSTOM_DATA}），随物品栈持久化。
 * <p>
 * <b>单向桥接</b>：仅 AE 网络能访问 RS 网络（读 / 写）。RS 网络无法反向访问 AE 网络。
 * 绑定时会同时记录 RS 方块所在的<b>维度</b>，以便单元在驱动器挂载时正确定位 RS 网络。
 */
public class RSNetworkStorageCellItem extends Item {

    private static final Logger LOGGER = LoggerFactory.getLogger(RSNetworkStorageCellItem.class);
    private static final String BOUND_KEY = "boundRsBlock";
    private static final String BOUND_DIM_KEY = "boundRsDim";
    /** 存入模式标记：true = 仅不可堆叠物品可存入；false / 缺省 = 全部物品可存入。读取永远不受此影响。 */
    private static final String MODE_KEY = "nonStackableMode";

    public RSNetworkStorageCellItem(Properties properties) {
        super(properties);
    }

    // ---------------------------------------------------------------------
    // 绑定数据（RS 方块坐标 + 维度）
    // ---------------------------------------------------------------------

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
        CompoundTag tag = cd == null ? new CompoundTag() : cd.getUnsafe().copy();
        tag.putLong(BOUND_KEY, pos.asLong());
        tag.putString(BOUND_DIM_KEY, dim.toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static boolean hasBinding(ItemStack stack) {
        return getBoundRsBlock(stack) != null;
    }

    // ---------------------------------------------------------------------
    // 存入模式（全部 / 仅不可堆叠）—— 读取永远全部
    // ---------------------------------------------------------------------

    /** 当前是否为「仅不可堆叠可存入」模式。false / 缺省 = 全部物品可存入。 */
    public static boolean isNonStackableMode(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = cd == null ? null : cd.getUnsafe();
        return tag != null && tag.getBoolean(MODE_KEY);
    }

    public static void setNonStackableMode(ItemStack stack, boolean value) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = cd == null ? new CompoundTag() : cd.getUnsafe().copy();
        tag.putBoolean(MODE_KEY, value);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        updateModelData(stack, value);
    }

    private static void updateModelData(ItemStack stack, boolean nonStackableMode) {
        if (nonStackableMode) {
            stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(1));
        } else {
            stack.remove(DataComponents.CUSTOM_MODEL_DATA);
        }
    }

    /**
     * 该物品栈当前的读写过滤策略：
     * 「仅不可堆叠」模式 → {@link CellFilter#NON_STACKABLE}（读全部、仅不可堆叠可写）；
     * 否则 → {@link CellFilter#ALL}（读写全部）。
     */
    public static CellFilter getFilter(ItemStack stack) {
        return isNonStackableMode(stack) ? CellFilter.NON_STACKABLE : CellFilter.ALL;
    }

    // ---------------------------------------------------------------------
    // 交互
    // ---------------------------------------------------------------------

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

    /** 对空气右键（不潜行）切换存入模式：全部物品 ↔ 仅不可堆叠物品。 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // 潜行留给绑定 RS 方块（useOn），此处只处理非潜行右键切换模式。
        if (player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(stack);
        }
        if (!level.isClientSide()) {
            boolean now = !isNonStackableMode(stack);
            setNonStackableMode(stack, now);
            player.sendSystemMessage(Component.literal(
                    "[RS Network Cell] 存入模式已切换为："
                            + (now ? "仅不可堆叠物品（读取仍为全部）" : "全部物品"))
                    .withStyle(now ? ChatFormatting.GOLD : ChatFormatting.GREEN));
            LOGGER.info("[rs2ae_cell] 存入模式切换：nonStackableMode={}", now);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
            TooltipFlag flag) {
        BlockPos bound = getBoundRsBlock(stack);
        boolean nonStackable = isNonStackableMode(stack);
        if (nonStackable) {
            tooltip.add(Component.literal("存入模式：仅不可堆叠物品（读取仍为 RS 全部物品）")
                    .withStyle(ChatFormatting.GOLD));
        } else {
            tooltip.add(Component.literal("存入模式：全部物品（读写 RS 全部内容）")
                    .withStyle(ChatFormatting.GREEN));
        }
        tooltip.add(Component.literal("右键(不潜行)切换存入模式")
                .withStyle(ChatFormatting.DARK_GRAY));
        if (bound == null) {
            tooltip.add(Component.literal("未绑定 RS 网络 — 潜行右键 RS 线缆/控制器进行绑定")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.literal("已绑定 RS 网络方块: " + bound.toShortString())
                    .withStyle(ChatFormatting.AQUA));
            tooltip.add(Component.literal("放入 ME 驱动器即可让 AE2 读取并写入 RS（单向桥接：仅 AE 访问 RS）")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        tooltip.add(Component.literal("提示：同一 RS 网络只需放入一个单元；若多个绑同一网络，仅第一个生效")
                .withStyle(ChatFormatting.GRAY));
    }
}
