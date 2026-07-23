package com.ae2rsbridge.cell;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.StorageCell;
import appeng.me.helpers.IGridConnectedBlockEntity;
import com.ae2rsbridge.bridge.KeyConverter;
import com.ae2rsbridge.item.RSNetworkStorageCellItem;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.node.NetworkNode;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;
import com.refinedmods.refinedstorage.api.storage.root.RootStorageListener;
import com.refinedmods.refinedstorage.common.api.support.network.NetworkNodeContainerProvider;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;

/**
 * 把 RS 网络以 AE2 原生存储单元（{@link StorageCell}）形式暴露给 AE2 网格。
 * <p>
 * <b>只读</b>：AE 可浏览 / 从 RS 提取物品，但不能写回 RS（{@link #insert} 返回 0）。
 * <b>推模式（高性能）</b>：RS 存储变动时 {@link RootStorageListener} 回调 → 置脏标 +
 * 让驱动器所属 AE2 网格 {@code invalidateCache()}，平时 AE2 直接读缓存，零轮询开销。
 */
public class RSNetworkCellInventory implements StorageCell {

    private static final Logger LOGGER = LoggerFactory.getLogger(RSNetworkCellInventory.class);

    @Nullable private final ISaveProvider host;
    @Nullable private final Level level;
    @Nullable private final BlockPos bound;
    @Nullable private Network network;
    @Nullable private RootStorage root;
    @Nullable private RootStorageListener listener;
    @Nullable private IManagedGridNode gridNode;
    @Nullable private KeyCounter cache;
    private boolean dirty = true;

    public RSNetworkCellInventory(ItemStack is, @Nullable ISaveProvider host) {
        this.host = host;
        this.level = (host instanceof BlockEntity be) ? be.getLevel() : null;
        this.bound = RSNetworkStorageCellItem.getBoundRsBlock(is);
        if (host instanceof IGridConnectedBlockEntity gcb) {
            this.gridNode = gcb.getMainNode();
        }
        resolveNetwork();
    }

    private void resolveNetwork() {
        this.network = null;
        this.root = null;
        if (level == null || bound == null || level.isClientSide()) {
            return;
        }
        BlockEntity be = level.getBlockEntity(bound);
        if (be == null) {
            return;
        }
        var cap = RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability();
        NetworkNodeContainerProvider provider = level.getCapability(cap, bound, Direction.UP);
        if (provider == null) {
            return;
        }
        for (NetworkNodeContainer container : provider.getContainers()) {
            NetworkNode node = container.getNode();
            if (node != null) {
                Network net = node.getNetwork();
                if (net != null) {
                    this.network = net;
                    break;
                }
            }
        }
        if (this.network != null) {
            this.root = this.network.getComponent(StorageNetworkComponent.class);
            registerListener();
        }
    }

    private void registerListener() {
        if (root == null) {
            return;
        }
        RootStorage r = this.root;
        WeakReference<RSNetworkCellInventory> selfRef = new WeakReference<>(this);
        final RootStorageListener[] holder = new RootStorageListener[1];
        holder[0] = result -> {
            RSNetworkCellInventory self = selfRef.get();
            if (self == null) {
                // 单元已被 GC（从驱动器移除），自行清理监听
                r.removeListener(holder[0]);
                return;
            }
            self.dirty = true;
            self.invalidateCache();
        };
        this.listener = holder[0];
        r.addListener(this.listener);
    }

    private void invalidateCache() {
        if (gridNode == null) {
            return;
        }
        gridNode.ifPresent(grid -> grid.getStorageService().invalidateCache());
    }

    private void rebuild() {
        cache = new KeyCounter();
        if (root == null) {
            return;
        }
        for (ResourceAmount ra : root.getAll()) {
            ResourceKey resource = ra.resource();
            long amount = ra.amount();
            if (amount <= 0) {
                continue;
            }
            AEKey aeKey = KeyConverter.toAEKey(resource);
            if (aeKey != null) {
                cache.add(aeKey, amount);
            }
        }
        dirty = false;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        if (dirty || cache == null) {
            rebuild();
        }
        if (cache != null) {
            out.addAll(cache);
        }
    }

    @Override
    public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
        // 只读：AE 不写回 RS 网络
        return 0;
    }

    @Override
    public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0 || key == null || root == null) {
            return 0;
        }
        ResourceKey rsKey = KeyConverter.toRSKey(key);
        if (rsKey == null) {
            return 0;
        }
        int size = (int) Math.min(amount, Integer.MAX_VALUE);
        long extracted = root.extract(rsKey, size,
                mode == Actionable.MODULATE ? Action.EXECUTE : Action.SIMULATE, Actor.EMPTY);
        if (mode == Actionable.MODULATE && extracted > 0) {
            dirty = true;
        }
        return extracted;
    }

    @Override
    public Component getDescription() {
        return Component.literal("RS Network Cell");
    }

    @Override
    public CellState getStatus() {
        if (bound == null || network == null) {
            return CellState.EMPTY;
        }
        if (cache == null || cache.isEmpty()) {
            return CellState.EMPTY;
        }
        return CellState.NOT_EMPTY;
    }

    @Override
    public double getIdleDrain() {
        return 1.0;
    }

    @Override
    public void persist() {
        // 数据保存在 RS 网络 + 绑定坐标在物品 NBT，单元本身不写 NBT。
        // best-effort 移除监听，避免残留泄漏。
        if (root != null && listener != null) {
            root.removeListener(listener);
        }
    }

    /** 主动解除与 RS 网络的绑定（单元从驱动器移除时调用，防止监听泄漏）。 */
    public void detach() {
        if (root != null && listener != null) {
            root.removeListener(listener);
        }
        this.listener = null;
        this.network = null;
        this.root = null;
    }
}
