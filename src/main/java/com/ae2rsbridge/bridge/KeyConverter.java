package com.ae2rsbridge.bridge;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/**
 * AE2 Key 与 RS ItemStack/FluidStack 之间的转换工具类。
 * <p>
 * 提供双向转换方法以及类型判断辅助方法，供桥接存储层使用。
 */
public final class KeyConverter {

    private KeyConverter() {
        // 工具类，禁止实例化
    }

    // ===== 物品转换 =====

    /**
     * 将 AE2 的 AEItemKey 转换为指定数量的 ItemStack。
     *
     * @param key    AE2 物品键
     * @param amount 数量
     * @return 对应的 ItemStack
     */
    public static ItemStack toItemStack(AEItemKey key, int amount) {
        if (key == null) {
            return ItemStack.EMPTY;
        }
        return key.toStack(amount);
    }

    /**
     * 将 RS 的 ItemStack 转换为 AE2 的 AEItemKey。
     *
     * @param stack RS 物品堆叠
     * @return 对应的 AEItemKey，如果 stack 为空则返回 null
     */
    public static AEItemKey toAEItemKey(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return AEItemKey.of(stack);
    }

    // ===== 流体转换 =====

    /**
     * 将 AE2 的 AEFluidKey 转换为指定数量的 FluidStack。
     *
     * @param key    AE2 流体键
     * @param amount 数量（毫桶）
     * @return 对应的 FluidStack
     */
    public static FluidStack toFluidStack(AEFluidKey key, int amount) {
        if (key == null) {
            return FluidStack.EMPTY;
        }
        return key.toStack(amount);
    }

    /**
     * 将 RS 的 FluidStack 转换为 AE2 的 AEFluidKey。
     *
     * @param stack RS 流体堆叠
     * @return 对应的 AEFluidKey，如果 stack 为空则返回 null
     */
    public static AEFluidKey toAEFluidKey(FluidStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return AEFluidKey.of(stack);
    }

    // ===== 类型判断 =====

    /**
     * 检查给定的 AEKey 是否为物品类型。
     *
     * @param key 要检查的 AEKey
     * @return 如果是物品类型则返回 true
     */
    public static boolean isItem(AEKey key) {
        return key != null && AEKeyType.items().contains(key);
    }

    /**
     * 检查给定的 AEKey 是否为流体类型。
     *
     * @param key 要检查的 AEKey
     * @return 如果是流体类型则返回 true
     */
    public static boolean isFluid(AEKey key) {
        return key != null && AEKeyType.fluids().contains(key);
    }
}
