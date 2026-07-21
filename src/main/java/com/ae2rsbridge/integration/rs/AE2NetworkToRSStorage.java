package com.ae2rsbridge.integration.rs;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.ae2rsbridge.blockentity.StorageBridgeBlockEntity;
import com.ae2rsbridge.bridge.BridgeTransactionGuard;
import com.ae2rsbridge.bridge.KeyConverter;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.external.ExternalStorageProvider;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * AE2 网络 → RS2 网络的外部存储提供者。
 * <p>
 * 实现 RS2 的 {@link ExternalStorageProvider}：RS2 的 {@code ExternalStorage} 会周期性调用
 * {@link #iterator()} 拉取 AE2 库存快照做增量 diff，并把变化推送到 RS 网络（含 RS 终端刷新）。
 * 因此本类<b>无需</b>旧版 RS1 中手写的缓存差异推送 / {@code reAttachListeners} / 客户端全量重同步等 hack。
 * <p>
 * 由于 RS2 的 {@code ExternalStorageProvider} 用单一 {@link ResourceKey} 迭代同时涵盖物品与流体，
 * 这里把 AE2 的物品与流体合并到同一个迭代器中（对应 {@link ItemResource} 与 {@link FluidResource}）。
 */
public class AE2NetworkToRSStorage implements ExternalStorageProvider {

    private final StorageBridgeBlockEntity bridge;
    private final IActionSource actionSource;
    /**
     * 当前已上报给 RS 的 AE2 库存快照（AEItemKey/AEFluidKey → 数量）。
     * {@code RSNetworkToAEStorage.getAvailableStacks} 用它扣除，避免双向镜像导致 AE2 终端物品翻倍。
     */
    private final KeyCounter reportedToRS = new KeyCounter();

    public AE2NetworkToRSStorage(StorageBridgeBlockEntity bridge) {
        this.bridge = bridge;
        this.actionSource = IActionSource.ofMachine(bridge);
    }

    private MEStorage getAE2Storage() {
        return bridge.getAE2Storage();
    }

    private static Actionable toAE2Action(Action action) {
        return action == Action.EXECUTE ? Actionable.MODULATE : Actionable.SIMULATE;
    }

    /**
     * 拉取 AE2 当前库存快照并转换为 RS2 资源列表。
     * <p>
     * 注意：无论守卫是否激活都必须返回正确数据——RS2 的 {@code ExternalStorage.detectChanges()}
     * 会调用本方法重建缓存；若此处因守卫激活返回空，AE 物品会被从 RS 缓存清空且无法自愈。
     * 守卫激活时读取 AE2 仍能正确排除 RS 镜像回来的物品
     * （{@code RSNetworkToAEStorage} 在守卫下返回空），故安全。
     */
    @Override
    public Iterator<ResourceAmount> iterator() {
        List<ResourceAmount> result = new ArrayList<>();
        MEStorage ae2Storage = getAE2Storage();
        if (ae2Storage == null) {
            return result.iterator();
        }

        KeyCounter counter = ae2Storage.getAvailableStacks();
        reportedToRS.clear();
        for (Object2LongMap.Entry<AEKey> entry : counter) {
            AEKey key = entry.getKey();
            long amount = entry.getLongValue();
            if (amount <= 0) {
                continue;
            }
            if (AEItemKey.is(key)) {
                ItemResource resource = KeyConverter.toRSItemResource((AEItemKey) key);
                if (resource != null) {
                    result.add(new ResourceAmount(resource, amount));
                    reportedToRS.add(key, amount);
                }
            } else if (AEFluidKey.is(key)) {
                FluidResource resource = KeyConverter.toRSFluidResource((AEFluidKey) key);
                if (resource != null) {
                    result.add(new ResourceAmount(resource, amount));
                    reportedToRS.add(key, amount);
                }
            }
        }
        return result.iterator();
    }

    /**
     * RS→AE 写入：把资源写进 AE2 网络。
     * <p>
     * 守卫激活时拒绝（防止 RS 把从 AE 镜像回来的物品又写回 AE，造成无限回环）。
     * 开启“仅输送不可堆叠物品”时，可堆叠物品（石头、原木等）保留在 AE，不写入 RS。
     */
    @Override
    public long insert(ResourceKey resource, long amount, Action action, Actor actor) {
        if (BridgeTransactionGuard.isActive()) {
            return 0;
        }
        if (resource == null || amount <= 0) {
            return 0;
        }
        AEKey key = KeyConverter.toAEKey(resource);
        if (key == null) {
            return 0;
        }
        if (bridge.isNonStackableOnly() && key instanceof AEItemKey itemKey && itemKey.getMaxStackSize() > 1) {
            return 0;
        }

        BridgeTransactionGuard.begin();
        try {
            MEStorage ae2Storage = getAE2Storage();
            if (ae2Storage == null) {
                return 0;
            }
            return ae2Storage.insert(key, amount, toAE2Action(action), actionSource);
        } finally {
            BridgeTransactionGuard.end();
        }
    }

    /**
     * RS→AE 读取：从 AE2 取出资源交给 RS。
     * <p>
     * 守卫激活时拒绝（防止 AE 把从 RS 镜像回来的物品又读回，造成无限回环）。
     * 提取路径不做 non_stackable_only 过滤（只控制写入路由）。
     */
    @Override
    public long extract(ResourceKey resource, long amount, Action action, Actor actor) {
        if (BridgeTransactionGuard.isActive()) {
            return 0;
        }
        if (resource == null || amount <= 0) {
            return 0;
        }
        AEKey key = KeyConverter.toAEKey(resource);
        if (key == null) {
            return 0;
        }

        BridgeTransactionGuard.begin();
        try {
            MEStorage ae2Storage = getAE2Storage();
            if (ae2Storage == null) {
                return 0;
            }
            return ae2Storage.extract(key, amount, toAE2Action(action), actionSource);
        } finally {
            BridgeTransactionGuard.end();
        }
    }

    /** 当前已上报给 RS 的 AE2 库存快照（供 {@code RSNetworkToAEStorage} 去重扣减）。 */
    public KeyCounter getReportedToRS() {
        return reportedToRS;
    }
}
