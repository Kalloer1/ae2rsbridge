package com.ae2rsbridge.bridge;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * 在 AE2 的 {@link AEKey}（{@link AEItemKey}/{@link AEFluidKey}）与 RS2 的
 * {@link ResourceKey}（{@link ItemResource}/{@link FluidResource}）之间双向转换的调度入口。
 */

/**
 * AE2 的 AEKey 与 RS2 的 ResourceKey（ItemResource / FluidResource）之间的转换工具类。
 * <p>
 * RS2 用 {@link ItemResource} / {@link FluidResource} 表示资源（而非 1.12.4 的 ItemStack/FluidStack），
 * 因此其构造与转换方式与原版不同。
 */
public final class KeyConverter {

    private KeyConverter() {
    }

    // ===== 物品转换 =====

    /** 将 AE2 物品键转为 RS2 物品资源。 */
    public static ItemResource toRSItemResource(AEItemKey key) {
        if (key == null) {
            return null;
        }
        ItemStack stack = key.toStack(1);
        return ItemResource.ofItemStack(stack);
    }

    /** 将 RS2 物品资源转为 AE2 物品键。 */
    public static AEItemKey toAEItemKey(ItemResource resource) {
        if (resource == null) {
            return null;
        }
        return AEItemKey.of(resource.toItemStack(1));
    }

    // ===== 流体转换 =====

    /** 将 AE2 流体键转为 RS2 流体资源。 */
    public static FluidResource toRSFluidResource(AEFluidKey key) {
        if (key == null) {
            return null;
        }
        FluidStack stack = key.toStack(1);
        return new FluidResource(stack.getFluid(), stack.getComponentsPatch());
    }

    /** 将 RS2 流体资源转为 AE2 流体键。 */
    public static AEFluidKey toAEFluidKey(FluidResource resource) {
        if (resource == null) {
            return null;
        }
        Fluid fluid = resource.fluid();
        // 注：RS2 的 FluidResource 组件为 DataComponentPatch，与 1.21.1 的 FluidStack(PatchedDataComponentMap)
        // 类型不匹配；此处用两参构造器丢弃组件（绝大多数流体无组件，足以桥接）。
        FluidStack stack = new FluidStack(fluid, 1);
        return AEFluidKey.of(stack);
    }

    // ===== 类型判断 =====

    public static boolean isItem(ResourceKey resource) {
        return resource instanceof ItemResource;
    }

    public static boolean isFluid(ResourceKey resource) {
        return resource instanceof FluidResource;
    }

    public static boolean isItem(AEKey key) {
        return key instanceof AEItemKey;
    }

    public static boolean isFluid(AEKey key) {
        return key instanceof AEFluidKey;
    }

    // ===== 双向调度（按运行时类型自动分派） =====

    /** 将 RS2 资源键转为对应的 AE2 键（物品/流体），未知类型返回 null。 */
    public static AEKey toAEKey(ResourceKey resource) {
        if (resource instanceof ItemResource ir) {
            return toAEItemKey(ir);
        }
        if (resource instanceof FluidResource fr) {
            return toAEFluidKey(fr);
        }
        return null;
    }

    /** 将 AE2 键转为对应的 RS2 资源键（物品/流体），未知类型返回 null。 */
    public static ResourceKey toRSKey(AEKey key) {
        if (key instanceof AEItemKey ik) {
            return toRSItemResource(ik);
        }
        if (key instanceof AEFluidKey fk) {
            return toRSFluidResource(fk);
        }
        return null;
    }
}
