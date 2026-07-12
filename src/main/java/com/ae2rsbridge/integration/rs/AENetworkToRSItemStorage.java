package com.ae2rsbridge.integration.rs;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.ae2rsbridge.blockentity.StorageBridgeBlockEntity;
import com.ae2rsbridge.bridge.BridgeTransactionGuard;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.AccessType;
import com.refinedmods.refinedstorage.api.storage.cache.InvalidateCause;
import com.refinedmods.refinedstorage.api.storage.externalstorage.IExternalStorage;
import com.refinedmods.refinedstorage.api.util.Action;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class AENetworkToRSItemStorage implements IExternalStorage<ItemStack> {

    private final StorageBridgeBlockEntity bridge;
    private final IActionSource actionSource;
    private long cachedStored = -1;
    private boolean needsCacheInvalidation = false;
    private final KeyCounter reportedToRS = new KeyCounter();

    public AENetworkToRSItemStorage(StorageBridgeBlockEntity bridge) {
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
    public ItemStack insert(ItemStack stack, int size, Action action) {
        if (BridgeTransactionGuard.isActive()) {
            return ItemHandlerHelper.copyStackWithSize(stack, size);
        }
        if (stack.isEmpty() || size <= 0) {
            return ItemHandlerHelper.copyStackWithSize(stack, size);
        }

        MEStorage ae2Storage = getAE2Storage();
        if (ae2Storage == null) {
            return ItemHandlerHelper.copyStackWithSize(stack, size);
        }

        AEItemKey key = AEItemKey.of(stack);
        if (key == null) {
            return ItemHandlerHelper.copyStackWithSize(stack, size);
        }

        // 仅输送不可堆叠物品：开启时拦截可堆叠物品（石头、原木等），使其保留在 AE 网络
        if (bridge.isNonStackableOnly() && key.getMaxStackSize() > 1) {
            return ItemStack.EMPTY;
        }

        boolean began = BridgeTransactionGuard.begin();
        try {
            Actionable mode = toAE2Action(action);
            long inserted = ae2Storage.insert(key, size, mode, actionSource);
            if (inserted > 0 && action == Action.PERFORM) {
                needsCacheInvalidation = true;
            }
            int remainder = size - (int) Math.min(inserted, size);
            if (remainder <= 0) {
                return ItemStack.EMPTY;
            }
            return ItemHandlerHelper.copyStackWithSize(stack, remainder);
        } finally {
            if (began) {
                BridgeTransactionGuard.end();
            }
        }
    }

    @Override
    public ItemStack extract(ItemStack stack, int size, int flags, Action action) {
        if (BridgeTransactionGuard.isActive()) {
            return ItemStack.EMPTY;
        }
        if (stack == null || stack.isEmpty() || size <= 0) {
            return ItemStack.EMPTY;
        }

        MEStorage ae2Storage = getAE2Storage();
        if (ae2Storage == null) {
            return ItemStack.EMPTY;
        }

        AEItemKey key = AEItemKey.of(stack);
        if (key == null) {
            return ItemStack.EMPTY;
        }

        boolean began = BridgeTransactionGuard.begin();
        try {
            Actionable mode = toAE2Action(action);
            long extracted = ae2Storage.extract(key, size, mode, actionSource);
            if (extracted > 0 && action == Action.PERFORM) {
                needsCacheInvalidation = true;
            }
            if (extracted <= 0) {
                return ItemStack.EMPTY;
            }
            return ItemHandlerHelper.copyStackWithSize(stack, (int) Math.min(extracted, Integer.MAX_VALUE));
        } finally {
            if (began) {
                BridgeTransactionGuard.end();
            }
        }
    }

    @Override
    public Collection<ItemStack> getStacks() {
        if (BridgeTransactionGuard.isActive()) {
            return new ArrayList<>();
        }
        boolean began = BridgeTransactionGuard.begin();
        try {
            List<ItemStack> stacks = new ArrayList<>();
            MEStorage ae2Storage = getAE2Storage();
            if (ae2Storage == null) {
                return stacks;
            }

            KeyCounter counter = ae2Storage.getAvailableStacks();
            reportedToRS.clear();
            for (Object2LongMap.Entry<AEKey> entry : counter) {
                AEKey key = entry.getKey();
                if (AEItemKey.is(key)) {
                    AEItemKey itemKey = (AEItemKey) key;
                    // 仅输送不可堆叠物品：开启时 RS 侧直接看不到可堆叠物品
                    if (bridge.isNonStackableOnly() && itemKey.getMaxStackSize() > 1) {
                        continue;
                    }
                    long amount = entry.getLongValue();
                    if (amount > 0) {
                        int count = (int) Math.min(amount, Integer.MAX_VALUE);
                        stacks.add(itemKey.toStack(count));
                        reportedToRS.add(itemKey, amount);
                    }
                }
            }
            return stacks;
        } finally {
            if (began) {
                BridgeTransactionGuard.end();
            }
        }
    }

    public KeyCounter getReportedToRS() {
        return reportedToRS;
    }

    @Override
    public int getStored() {
        if (BridgeTransactionGuard.isActive()) {
            return 0;
        }
        boolean began = BridgeTransactionGuard.begin();
        try {
            MEStorage ae2Storage = getAE2Storage();
            if (ae2Storage == null) {
                return 0;
            }

            KeyCounter counter = ae2Storage.getAvailableStacks();
            long total = 0;
            for (Object2LongMap.Entry<AEKey> entry : counter) {
                AEKey k = entry.getKey();
                if (AEItemKey.is(k)) {
                    AEItemKey ik = (AEItemKey) k;
                    // 仅输送不可堆叠物品：开启时统计也只计不可堆叠物品
                    if (bridge.isNonStackableOnly() && ik.getMaxStackSize() > 1) {
                        continue;
                    }
                    total += entry.getLongValue();
                }
            }
            return (int) Math.min(total, Integer.MAX_VALUE);
        } finally {
            if (began) {
                BridgeTransactionGuard.end();
            }
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
    public int getCacheDelta(int storedPreInsertion, int size, ItemStack remainder) {
        if (remainder == null || remainder.isEmpty()) {
            return size;
        }
        return size - remainder.getCount();
    }

    @Override
    public long getCapacity() {
        return -1;
    }

    @Override
    public void update(INetwork network) {
        if (network == null || network.getItemStorageCache() == null) {
            return;
        }

        if (needsCacheInvalidation) {
            network.getItemStorageCache().invalidate(InvalidateCause.DISK_INVENTORY_CHANGED);
            needsCacheInvalidation = false;
            return;
        }

        long currentStored = getStored();
        if (currentStored != cachedStored) {
            cachedStored = currentStored;
            network.getItemStorageCache().invalidate(InvalidateCause.DISK_INVENTORY_CHANGED);
        }
    }
}
