package com.ae2rsbridge.integration.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.ae2rsbridge.blockentity.StorageBridgeBlockEntity;
import com.ae2rsbridge.bridge.BridgeTransactionGuard;
import com.ae2rsbridge.bridge.KeyConverter;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import net.minecraft.network.chat.Component;

/**
 * RS2 网络 → AE2 网络的存储（挂载到 AE2 网格的 MEStorage）。
 * <p>
 * 旧版 RS1 使用 {@code INetwork.insertItem/extractItem/getItemStorageCache().getList()} 等 API，
 * 在 RS2 中已不存在。新版改为通过 {@code network.getComponent(StorageNetworkComponent.class)}
 * 拿到 RS 网络的根存储（{@link RootStorage}），对其做 insert/extract/getAll。
 * <p>
 * 去重（防翻倍）：RS 网络里已经包含了 AE2 经 {@link AE2NetworkToRSStorage} 镜像过去的物品，
 * 因此 {@link #getAvailableStacks} 读出 RS 库存后，必须扣除“AE2 已上报给 RS 的快照”
 * （{@code bridge.getAE2NetworkToRSStorage().getReportedToRS()}），只把真正属于 RS 的物品
 * 暴露给 AE2 终端。这与旧版用两个 IExternalStorage 的 reportedToRS 做扣减思路一致，
 * 只是现在合并到单一的 provider 快照里。
 */
public class RSNetworkToAEStorage implements MEStorage {

    private final StorageBridgeBlockEntity bridge;

    public RSNetworkToAEStorage(StorageBridgeBlockEntity bridge) {
        this.bridge = bridge;
    }

    private Network getRSNetwork() {
        return bridge.getRSNetwork();
    }

    private static Action toRSAction(Actionable mode) {
        return mode == Actionable.MODULATE ? Action.EXECUTE : Action.SIMULATE;
    }

    @Override
    public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0 || key == null) {
            return 0;
        }

        // 访问模式限制：仅读取/无访问时禁止 AE2 向 RS 写入
        if (!bridge.getAE2Access().isAllowInsertion()) {
            return 0;
        }

        // 仅输送不可堆叠物品：开启时，可堆叠物品（石头、原木等）保留在 AE，不写入 RS
        if (bridge.isNonStackableOnly() && AEItemKey.is(key)
                && ((AEItemKey) key).getMaxStackSize() > 1) {
            return 0;
        }

        Network network = getRSNetwork();
        if (network == null) {
            return 0;
        }
        RootStorage root = network.getComponent(StorageNetworkComponent.class);
        if (root == null) {
            return 0;
        }

        // 守卫激活时拒绝：避免 AE2 把 RS 镜像回来的物品又写回 RS（无限回环）
        if (BridgeTransactionGuard.isActive()) {
            return 0;
        }
        BridgeTransactionGuard.begin();
        try {
            ResourceKey rsKey = KeyConverter.toRSKey(key);
            if (rsKey == null) {
                return 0;
            }
            int size = (int) Math.min(amount, Integer.MAX_VALUE);
            long inserted = root.insert(rsKey, size, toRSAction(mode), Actor.EMPTY);
            return inserted;
        } finally {
            BridgeTransactionGuard.end();
        }
    }

    @Override
    public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0 || key == null) {
            return 0;
        }

        // 访问模式限制：仅写入/无访问时禁止 AE2 从 RS 读取
        if (!bridge.getAE2Access().isAllowExtraction()) {
            return 0;
        }

        Network network = getRSNetwork();
        if (network == null) {
            return 0;
        }
        RootStorage root = network.getComponent(StorageNetworkComponent.class);
        if (root == null) {
            return 0;
        }

        // 守卫激活时拒绝：避免 AE2 把 RS 镜像回来的物品又读回（无限回环）
        if (BridgeTransactionGuard.isActive()) {
            return 0;
        }
        BridgeTransactionGuard.begin();
        try {
            ResourceKey rsKey = KeyConverter.toRSKey(key);
            if (rsKey == null) {
                return 0;
            }
            int size = (int) Math.min(amount, Integer.MAX_VALUE);
            long extracted = root.extract(rsKey, size, toRSAction(mode), Actor.EMPTY);
            return extracted;
        } finally {
            BridgeTransactionGuard.end();
        }
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        // 访问模式限制：无读取权限时不暴露 RS 存储内容给 AE2
        if (!bridge.getAE2Access().isAllowExtraction()) {
            return;
        }

        Network network = getRSNetwork();
        if (network == null) {
            return;
        }
        RootStorage root = network.getComponent(StorageNetworkComponent.class);
        if (root == null) {
            return;
        }

        // 守卫激活时直接返回（不读 RS），避免回环
        if (BridgeTransactionGuard.isActive()) {
            return;
        }
        BridgeTransactionGuard.begin();
        try {
            // 计算 AE2 已上报给 RS 的快照（含 RS 镜像回来的 AE 物品），用于扣除，避免翻倍。
            KeyCounter ae2ReportedToRS = bridge.getAE2NetworkToRSStorage().getReportedToRS();

            for (ResourceAmount ra : root.getAll()) {
                ResourceKey resource = ra.resource();
                long rsAmount = ra.amount();
                if (rsAmount <= 0) {
                    continue;
                }
                if (resource instanceof ItemResource itemResource) {
                    AEItemKey aeKey = KeyConverter.toAEItemKey(itemResource);
                    if (aeKey == null) {
                        continue;
                    }
                    long ae2Amount = ae2ReportedToRS.get(aeKey);
                    long netAmount = rsAmount - ae2Amount;
                    if (netAmount > 0) {
                        out.add(aeKey, netAmount);
                    }
                } else if (resource instanceof FluidResource fluidResource) {
                    AEFluidKey aeKey = KeyConverter.toAEFluidKey(fluidResource);
                    if (aeKey == null) {
                        continue;
                    }
                    long ae2Amount = ae2ReportedToRS.get(aeKey);
                    long netAmount = rsAmount - ae2Amount;
                    if (netAmount > 0) {
                        out.add(aeKey, netAmount);
                    }
                }
            }
        } finally {
            BridgeTransactionGuard.end();
        }
    }

    @Override
    public Component getDescription() {
        return Component.literal("RS Network Storage");
    }
}
