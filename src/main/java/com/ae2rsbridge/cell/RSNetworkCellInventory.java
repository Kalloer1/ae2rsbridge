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
import com.ae2rsbridge.cell.CellFilter;
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
 * <p>
 * <b>健壮性（关键）</b>：本类被 AE2 驱动器在重建网格存储缓存时调用。任何 RS 侧异常都
 * 必须被这里吞掉并降级为空，<b>绝不能向上抛给 AE2</b>——否则会让整个网格的 StorageService
 * 崩溃，导致 AE 连它自己的物品都读不了/存不了。所有 RS 交互都包在 try/catch 中。
 */
public class RSNetworkCellInventory implements StorageCell {

    private static final Logger LOGGER = LoggerFactory.getLogger(RSNetworkCellInventory.class);

    /**
     * 虚拟玩家 "AE"：本模组以只读方式把 RS 网络暴露给 AE2。每当 AE 经此单元从 RS 提取物品时，
     * 用这个 Actor 标注来源，使 RS 的「谁在何时存入/取出」记录里显示为玩家 <b>AE</b>，
     * 而非匿名的 Actor.EMPTY。写入（AE→RS）目前未开启，见后续机制提案。
     */
    private static final Actor AE_ACTOR = new Actor() {
        @Override
        public String getName() {
            return "AE";
        }
    };

    @Nullable private final ISaveProvider host;
    @Nullable private final Level level;
    @Nullable private final BlockPos bound;
    @Nullable private Network network;
    @Nullable private RootStorage root;
    @Nullable private RootStorageListener listener;
    @Nullable private IManagedGridNode gridNode;
    @Nullable private KeyCounter cache;
    private final CellFilter filter;
    private boolean dirty = true;

    public RSNetworkCellInventory(ItemStack is, @Nullable ISaveProvider host) {
        this.host = host;
        this.level = (host instanceof BlockEntity be) ? be.getLevel() : null;
        this.bound = RSNetworkStorageCellItem.getBoundRsBlock(is);
        this.filter = (is.getItem() instanceof RSNetworkStorageCellItem item) ? item.getFilter() : CellFilter.ALL;
        if (host instanceof IGridConnectedBlockEntity gcb) {
            this.gridNode = gcb.getMainNode();
        }
        resolveNetwork();
    }

    private void resolveNetwork() {
        try {
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
            Network net = null;
            for (NetworkNodeContainer container : provider.getContainers()) {
                NetworkNode node = container.getNode();
                if (node != null) {
                    Network n = node.getNetwork();
                    if (n != null) {
                        net = n;
                        break;
                    }
                }
            }
            if (net == null) {
                // RS 当前未接入网络：若之前有绑定，先清理旧监听，避免泄漏；root 保持 null 等下次重试。
                if (this.network != null) {
                    detach();
                }
                return;
            }
            if (net == this.network) {
                // 同一网络，监听已注册，无需重复。
                return;
            }
            // 网络变了（或首次解析）：清理旧绑定后重新登记监听。
            if (this.network != null) {
                detach();
            }
            this.network = net;
            this.root = this.network.getComponent(StorageNetworkComponent.class);
            registerListener();
        } catch (Throwable t) {
            LOGGER.error("[rs2ae_cell] resolveNetwork failed; cell stays inactive", t);
            this.network = null;
            this.root = null;
        }
    }

    private void registerListener() {
        if (root == null) {
            return;
        }
        final RootStorage r = this.root;
        WeakReference<RSNetworkCellInventory> selfRef = new WeakReference<>(this);
        final RootStorageListener[] holder = new RootStorageListener[1];
        holder[0] = result -> {
            try {
                RSNetworkCellInventory self = selfRef.get();
                if (self == null) {
                    // 单元已被 GC（从驱动器移除），自行清理监听
                    r.removeListener(holder[0]);
                    return;
                }
                self.dirty = true;
                self.invalidateCache();
            } catch (Throwable t) {
                // 监听回调里的异常不要向外冒泡（RS 线程），仅记录。
                LOGGER.warn("[rs2ae_cell] listener callback error (ignored)", t);
            }
        };
        this.listener = holder[0];
        r.addListener(this.listener);
    }

    private void invalidateCache() {
        if (gridNode == null) {
            return;
        }
        try {
            gridNode.ifPresent(grid -> grid.getStorageService().invalidateCache());
        } catch (Throwable t) {
            LOGGER.warn("[rs2ae_cell] invalidateCache failed (ignored)", t);
        }
    }

    private void rebuild() {
        try {
            if (root == null) {
                // RS 网络尚未解析（单元放入时 RS 未上线，或绑定方块暂未联网）：重试解析。
                resolveNetwork();
            }
            cache = new KeyCounter();
            if (root == null) {
                // 仍无网络：保持 dirty 以便 AE2 下次查询时继续重试，直到 RS 上线。
                dirty = true;
                return;
            }
            for (ResourceAmount ra : root.getAll()) {
                try {
                    ResourceKey resource = ra.resource();
                    long amount = ra.amount();
                    if (amount <= 0) {
                        continue;
                    }
                    AEKey aeKey = KeyConverter.toAEKey(resource);
                    if (aeKey != null && filter.test(aeKey)) {
                        cache.add(aeKey, amount);
                    }
                } catch (RuntimeException e) {
                    // 单条资源转换失败（罕见 RS 资源类型）跳过，绝不影响整体。
                    LOGGER.warn("[rs2ae_cell] skipped a resource during rebuild", e);
                }
            }
            dirty = false;
        } catch (Throwable t) {
            LOGGER.error("[rs2ae_cell] rebuild failed; cell reports empty until RS recovers", t);
            cache = new KeyCounter();
            dirty = true; // 保持 dirty，稍后重试
        }
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        if (out == null) {
            return;
        }
        try {
            if (dirty || cache == null) {
                rebuild();
            }
            if (cache != null) {
                out.addAll(cache);
            }
        } catch (Throwable t) {
            // 绝不允许异常冒泡到 AE2 的 StorageService 缓存重建 —— 那会让整个网格崩溃。
            LOGGER.error("[rs2ae_cell] getAvailableStacks failed; degrading to empty", t);
        }
    }

    @Override
    public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
        // 只读：AE 不写回 RS 网络
        return 0;
    }

    @Override
    public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0 || key == null || root == null || !filter.test(key)) {
            return 0;
        }
        ResourceKey rsKey = KeyConverter.toRSKey(key);
        if (rsKey == null) {
            return 0;
        }
        int size = (int) Math.min(amount, Integer.MAX_VALUE);
        try {
            long extracted = root.extract(rsKey, size,
                    mode == Actionable.MODULATE ? Action.EXECUTE : Action.SIMULATE, AE_ACTOR);
            if (mode == Actionable.MODULATE && extracted > 0) {
                dirty = true;
            }
            return extracted;
        } catch (Throwable t) {
            LOGGER.error("[rs2ae_cell] extract failed; returning 0", t);
            return 0;
        }
    }

    @Override
    public Component getDescription() {
        return Component.literal("RS Network Cell (" + filter.name().toLowerCase() + ")");
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
        try {
            if (root != null && listener != null) {
                root.removeListener(listener);
            }
        } catch (Throwable t) {
            LOGGER.warn("[rs2ae_cell] persist listener removal failed (ignored)", t);
        }
    }

    /** 主动解除与 RS 网络的绑定（单元从驱动器移除时调用，防止监听泄漏）。 */
    public void detach() {
        try {
            if (root != null && listener != null) {
                root.removeListener(listener);
            }
        } catch (Throwable t) {
            LOGGER.warn("[rs2ae_cell] detach listener removal failed (ignored)", t);
        }
        this.listener = null;
        this.network = null;
        this.root = null;
    }
}
