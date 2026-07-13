package com.ae2rsbridge.integration.rs;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.ae2rsbridge.blockentity.StorageBridgeBlockEntity;
import com.ae2rsbridge.bridge.BridgeTransactionGuard;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.AccessType;
import com.refinedmods.refinedstorage.api.storage.externalstorage.IExternalStorage;
import com.refinedmods.refinedstorage.api.util.Action;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class AENetworkToRSFluidStorage implements IExternalStorage<FluidStack> {

    private final StorageBridgeBlockEntity bridge;
    private final IActionSource actionSource;
    private boolean needsCacheInvalidation = false;
    private long cachedStored = -1;
    private final KeyCounter reportedToRS = new KeyCounter();
    // 同 AENetworkToRSItemStorage：getStacks 填充缓存后标记需要客户端全量重同步。
    private boolean clientResyncNeeded = true;

    public AENetworkToRSFluidStorage(StorageBridgeBlockEntity bridge) {
        this.bridge = bridge;
        this.actionSource = IActionSource.ofMachine(bridge);
    }

    private MEStorage getAE2Storage() {
        IGrid grid = bridge.getMainNode().getGrid();
        if (grid == null) {
            return null;
        }
        return grid.getStorageService().getInventory();
    }

    private static Actionable toAE2Action(Action action) {
        return action == Action.PERFORM ? Actionable.MODULATE : Actionable.SIMULATE;
    }

    @Override
    public FluidStack insert(FluidStack stack, int size, Action action) {
        if (BridgeTransactionGuard.isActive()) {
            FluidStack copy = stack.copy();
            copy.setAmount(size);
            return copy;
        }
        if (stack.isEmpty() || size <= 0) {
            FluidStack copy = stack.copy();
            copy.setAmount(size);
            return copy;
        }

        MEStorage ae2Storage = getAE2Storage();
        if (ae2Storage == null) {
            FluidStack copy = stack.copy();
            copy.setAmount(size);
            return copy;
        }

        AEFluidKey key = AEFluidKey.of(stack);
        if (key == null) {
            FluidStack copy = stack.copy();
            copy.setAmount(size);
            return copy;
        }

        BridgeTransactionGuard.begin();
        try {
            Actionable mode = toAE2Action(action);
            long inserted = ae2Storage.insert(key, size, mode, actionSource);
            if (inserted > 0 && action == Action.PERFORM) {
                needsCacheInvalidation = true;
            }
            int remainder = size - (int) Math.min(inserted, size);
            if (remainder <= 0) {
                return FluidStack.EMPTY;
            }
            FluidStack remainderStack = stack.copy();
            remainderStack.setAmount(remainder);
            return remainderStack;
        } finally {
            BridgeTransactionGuard.end();
        }
    }

    @Override
    public FluidStack extract(FluidStack stack, int size, int flags, Action action) {
        if (BridgeTransactionGuard.isActive()) {
            return FluidStack.EMPTY;
        }
        if (stack == null || stack.isEmpty() || size <= 0) {
            return FluidStack.EMPTY;
        }

        MEStorage ae2Storage = getAE2Storage();
        if (ae2Storage == null) {
            return FluidStack.EMPTY;
        }

        AEFluidKey key = AEFluidKey.of(stack);
        if (key == null) {
            return FluidStack.EMPTY;
        }

        BridgeTransactionGuard.begin();
        try {
            Actionable mode = toAE2Action(action);
            long extracted = ae2Storage.extract(key, size, mode, actionSource);
            if (extracted > 0 && action == Action.PERFORM) {
                needsCacheInvalidation = true;
            }
            if (extracted <= 0) {
                return FluidStack.EMPTY;
            }
            int amount = (int) Math.min(extracted, Integer.MAX_VALUE);
            FluidStack extractedStack = stack.copy();
            extractedStack.setAmount(amount);
            return extractedStack;
        } finally {
            BridgeTransactionGuard.end();
        }
    }

    @Override
    public Collection<FluidStack> getStacks() {
        // 同 AENetworkToRSItemStorage.getStacks：守卫激活时也必须返回正确数据，
        // 否则 invalidate() 清空 RS 缓存后 AE 流体无法恢复。
        BridgeTransactionGuard.begin();
        try {
            List<FluidStack> stacks = new ArrayList<>();
            MEStorage ae2Storage = getAE2Storage();
            if (ae2Storage == null) {
                return stacks;
            }

            KeyCounter counter = ae2Storage.getAvailableStacks();
            reportedToRS.clear();
            clientResyncNeeded = true;
            for (Object2LongMap.Entry<AEKey> entry : counter) {
                AEKey key = entry.getKey();
                if (AEFluidKey.is(key)) {
                    AEFluidKey fluidKey = (AEFluidKey) key;
                    long amount = entry.getLongValue();
                    if (amount > 0) {
                        int count = (int) Math.min(amount, Integer.MAX_VALUE);
                        stacks.add(fluidKey.toStack(count));
                        reportedToRS.add(fluidKey, amount);
                    }
                }
            }
            return stacks;
        } finally {
            BridgeTransactionGuard.end();
        }
    }

    public KeyCounter getReportedToRS() {
        return reportedToRS;
    }

    @Override
    public int getStored() {
        BridgeTransactionGuard.begin();
        try {
            MEStorage ae2Storage = getAE2Storage();
            if (ae2Storage == null) {
                return 0;
            }

            KeyCounter counter = ae2Storage.getAvailableStacks();
            long total = 0;
            for (Object2LongMap.Entry<AEKey> entry : counter) {
                if (AEFluidKey.is(entry.getKey())) {
                    total += entry.getLongValue();
                }
            }
            return (int) Math.min(total, Integer.MAX_VALUE);
        } finally {
            BridgeTransactionGuard.end();
        }
    }

    @Override
    public int getPriority() {
        return bridge.getRSPriority();
    }

    @Override
    public AccessType getAccessType() {
        return AccessType.INSERT_EXTRACT;
    }

    @Override
    public int getCacheDelta(int storedPreInsertion, int size, FluidStack remainder) {
        if (remainder == null || remainder.isEmpty()) {
            return size;
        }
        return size - remainder.getAmount();
    }

    @Override
    public long getCapacity() {
        return -1;
    }

    /** 计算当前应上报给 RS 的 AE2 流体快照（重入守卫下排除 RS 镜像回来的流体）。 */
    private KeyCounter computeSnapshot() {
        KeyCounter snapshot = new KeyCounter();
        MEStorage ae2Storage = getAE2Storage();
        if (ae2Storage == null) {
            return snapshot;
        }
        KeyCounter counter = ae2Storage.getAvailableStacks();
        for (Object2LongMap.Entry<AEKey> entry : counter) {
            AEKey key = entry.getKey();
            if (AEFluidKey.is(key)) {
                long amount = entry.getLongValue();
                if (amount > 0) {
                    snapshot.add(key, amount);
                }
            }
        }
        return snapshot;
    }

    /**
     * 增量 diff 推送（见 AENetworkToRSItemStorage.update 的说明）：RS 终端监听器的
     * onInvalidated 是 NO-OP，必须用 cache.add/remove+flush 推送 delta 才能刷新客户端。
     */
    @Override
    public void update(INetwork network) {
        if (network == null || network.getFluidStorageCache() == null) {
            return;
        }
        if (BridgeTransactionGuard.isActive()) {
            return;
        }

        BridgeTransactionGuard.begin();
        try {
            var cache = network.getFluidStorageCache();
            KeyCounter current = computeSnapshot();
            boolean changed = false;

            for (Object2LongMap.Entry<AEKey> entry : current) {
                AEKey key = entry.getKey();
                long delta = entry.getLongValue() - reportedToRS.get(key);
                if (delta > 0) {
                    int d = (int) Math.min(delta, Integer.MAX_VALUE);
                    cache.add(((AEFluidKey) key).toStack(d), d, false, true);
                    changed = true;
                } else if (delta < 0) {
                    int d = (int) Math.min(-delta, Integer.MAX_VALUE);
                    cache.remove(((AEFluidKey) key).toStack(d), d, true);
                    changed = true;
                }
            }
            for (Object2LongMap.Entry<AEKey> entry : reportedToRS) {
                AEKey key = entry.getKey();
                long oldAmt = entry.getLongValue();
                if (oldAmt > 0 && current.get(key) == 0 && AEFluidKey.is(key)) {
                    int d = (int) Math.min(oldAmt, Integer.MAX_VALUE);
                    cache.remove(((AEFluidKey) key).toStack(d), d, true);
                    changed = true;
                }
            }

            reportedToRS.clear();
            for (Object2LongMap.Entry<AEKey> entry : current) {
                reportedToRS.add(entry.getKey(), entry.getLongValue());
            }
            cachedStored = 0;
            for (Object2LongMap.Entry<AEKey> entry : current) {
                cachedStored += entry.getLongValue();
            }

            needsCacheInvalidation = false;

            // 同 AENetworkToRSItemStorage：客户端首屏/重连全量重同步。
            if (clientResyncNeeded) {
                network.getFluidStorageCache().reAttachListeners();
                clientResyncNeeded = false;
            }

            if (changed) {
                cache.flush();
            }
        } finally {
            BridgeTransactionGuard.end();
        }
    }
}