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
    // getStacks() 在 RS 缓存 invalidate 时填充服务端缓存，但 RS 终端监听器的
    // onInvalidated 是 NO-OP（不通知已打开的客户端）。因此填充后标记需要一次
    // 客户端全量重同步，由 update() 调用 reAttachListeners() 把完整缓存推过去。
    private boolean clientResyncNeeded = true;

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

        // 仅输送不可堆叠物品：此 insert 为 RS→AE 方向（把物品写入 AE 网络）。
        // 开启时，可堆叠物品（石头、原木等）应留在 AE → 放行；不可堆叠物品
        // （工具、盔甲等）应留在 RS → 拦截，让 RS 将其存入自身存储。
        if (bridge.isNonStackableOnly() && key.getMaxStackSize() <= 1) {
            return ItemStack.EMPTY;
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
                return ItemStack.EMPTY;
            }
            return ItemHandlerHelper.copyStackWithSize(stack, remainder);
        } finally {
            BridgeTransactionGuard.end();
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

        BridgeTransactionGuard.begin();
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
            BridgeTransactionGuard.end();
        }
    }

    @Override
    public Collection<ItemStack> getStacks() {
        // 注意：无论守卫是否激活都必须返回正确数据。invalidate() 会先 list.clear() 再用
        // getStacks() 重建缓存，若此处因守卫激活返回空，AE 物品会被从 RS 缓存清空且无法自愈。
        // 守卫激活时读取 AE2 仍能正确排除 RS 镜像（RSNetworkToAEStorage 在守卫下返回空），故安全。
        BridgeTransactionGuard.begin();
        try {
            List<ItemStack> stacks = new ArrayList<>();
            MEStorage ae2Storage = getAE2Storage();
            if (ae2Storage == null) {
                return stacks;
            }

            KeyCounter counter = ae2Storage.getAvailableStacks();
            reportedToRS.clear();
            clientResyncNeeded = true;
            for (Object2LongMap.Entry<AEKey> entry : counter) {
                AEKey key = entry.getKey();
                if (AEItemKey.is(key)) {
                    AEItemKey itemKey = (AEItemKey) key;
                    // 注意：读取视图不得按 non_stackable_only 过滤。该开关只控制
                    // 插入路由（可堆叠留 AE、不可堆叠去 RS），不应隐藏 AE 内容——
                    // 否则开启后 RS 终端将看不到 ME 驱动器里的可堆叠物品。
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
                AEKey k = entry.getKey();
                if (AEItemKey.is(k)) {
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

    /**
     * 计算当前应上报给 RS 的 AE2 物品快照（仅 AE2 原生物品；重入守卫下
     * RSNetworkToAEStorage 会返回空，从而排除 RS 镜像回来的物品）。
     */
    private KeyCounter computeSnapshot() {
        KeyCounter snapshot = new KeyCounter();
        MEStorage ae2Storage = getAE2Storage();
        if (ae2Storage == null) {
            return snapshot;
        }
        KeyCounter counter = ae2Storage.getAvailableStacks();
        for (Object2LongMap.Entry<AEKey> entry : counter) {
            AEKey key = entry.getKey();
            if (AEItemKey.is(key)) {
                AEItemKey itemKey = (AEItemKey) key;
                long amount = entry.getLongValue();
                if (amount > 0) {
                    snapshot.add(itemKey, amount);
                }
            }
        }
        return snapshot;
    }

    /**
     * 关键修复（终端取物卡住不刷新）：RS 终端 grid 的存储缓存监听器
     * {@code onInvalidated()} 是 NO-OP——调用 {@code cache.invalidate()} 虽会重建服务端
     * 列表，但<b>不会推送任何更新到打开终端的客户端</b>。客户端只响应
     * {@code onChanged}/{@code onChangedBulk}（增量 delta）。同时因为本类是
     * {@code IExternalStorage}，{@code Network.extractItem}/{@code insertItem} 把
     * 变化推送责任完全交给本方法（自身不再 remove/add）。因此必须在此做增量 diff，
     * 通过 {@code cache.add}/{@code cache.remove}+{@code flush} 推送 delta。
     */
    @Override
    public void update(INetwork network) {
        if (network == null || network.getItemStorageCache() == null) {
            return;
        }
        // 重入守卫活跃时跳过：避免在 RS 缓存重建 / 桥事务途中读取半成品状态
        if (BridgeTransactionGuard.isActive()) {
            return;
        }

        BridgeTransactionGuard.begin();
        try {
            var cache = network.getItemStorageCache();
            KeyCounter current = computeSnapshot();
            boolean changed = false;

            // 1) 新增或增多的物品
            for (Object2LongMap.Entry<AEKey> entry : current) {
                AEKey key = entry.getKey();
                long delta = entry.getLongValue() - reportedToRS.get(key);
                if (delta > 0) {
                    cache.add(((AEItemKey) key).toStack(1), (int) Math.min(delta, Integer.MAX_VALUE), false, true);
                    changed = true;
                } else if (delta < 0) {
                    cache.remove(((AEItemKey) key).toStack(1), (int) Math.min(-delta, Integer.MAX_VALUE), true);
                    changed = true;
                }
            }
            // 2) 完全消失的物品（当前快照里已不存在）
            for (Object2LongMap.Entry<AEKey> entry : reportedToRS) {
                AEKey key = entry.getKey();
                long oldAmt = entry.getLongValue();
                if (oldAmt > 0 && current.get(key) == 0 && AEItemKey.is(key)) {
                    cache.remove(((AEItemKey) key).toStack(1), (int) Math.min(oldAmt, Integer.MAX_VALUE), true);
                    changed = true;
                }
            }

            // 更新上报快照（同时供 RSNetworkToAEStorage 防翻倍去重使用）
            reportedToRS.clear();
            for (Object2LongMap.Entry<AEKey> entry : current) {
                reportedToRS.add(entry.getKey(), entry.getLongValue());
            }
            cachedStored = 0;
            for (Object2LongMap.Entry<AEKey> entry : current) {
                cachedStored += entry.getLongValue();
            }

            needsCacheInvalidation = false;

            // 客户端首屏/重连同步：getStacks() 填充缓存后，若玩家早已打开 RS 终端，
            // onInvalidated(NO-OP) 不会推送，且 update 的增量 delta 可能因
            // reportedToRS 已被同步而全为 0。强制一次全量重同步保证客户端能看到 AE 物品。
            if (clientResyncNeeded) {
                network.getItemStorageCache().reAttachListeners();
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
