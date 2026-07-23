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
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 把 RS 网络以 AE2 原生存储单元（{@link StorageCell}）形式暴露给 AE2 网格。
 * <p>
 * <b>读写</b>：AE 可浏览 / 从 RS 提取物品，也能把物品写入 RS 网络（双向桥接）。
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
     * 虚拟玩家 "AE"：每当 AE 经此单元从 RS 提取或写入物品时，用这个 Actor 标注来源，
     * 使 RS 的「谁在何时存入/取出」记录里显示为玩家 <b>AE</b>，而非匿名的 Actor.EMPTY。
     */
    private static final Actor AE_ACTOR = new Actor() {
        @Override
        public String getName() {
            return "AE";
        }
    };

    /**
     * 查询 RS 能力时尝试的方向序列（含 null）。RS2 的 NetworkNodeContainerProvider 在注册时
     * 忽略 side（lambda 为 {@code (be, side) -> be.getContainerProvider()}），因此任意方向都应命中；
     * 这里仍做多方向兜底，杜绝任何方向敏感的实现差异导致 capability 查不到。
     */
    private static final Direction[] DIRECTIONS = {
            null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    /**
     * 待解析队列：当 RS 网络暂时不可达（区块未加载 / 控制器未连网 / 维度未就绪 / 世界加载时
     * RS 图尚未重建）时，单元放入此队列，由 {@link #tickPending()} 在服务端每约 1 秒尝试重新
     * 解析一次。一旦 RS 可达即自动接入并移出队列，玩家无需重新插拔单元。
     * <p>
     * 这是修复「绑定后塞入驱动器但 AE 读不到 RS」的关键：AE2 只在挂载时调一次
     * {@code getAvailableStacks}，之后仅依赖本单元的 {@code RootStorageListener} 触发
     * {@code invalidateCache()} 才重新查询。若挂载瞬间 RS 未就绪，监听从未注册，单元会永久为空；
     * 此机制保证 RS 上线后自愈。
     * <p>
     * 用 WeakHashMap 以单元实例为键 —— 单元被驱动器卸载并 GC 后条目自动消失，不会内存泄漏。
     */
    private static final Map<RSNetworkCellInventory, Boolean> PENDING = new WeakHashMap<>();
    private static final int RETRY_INTERVAL_TICKS = 20; // ~1s @ 20TPS
    private static int tickCounter = 0;

    /** 由 {@link com.ae2rsbridge.AE2RSBridge} 在服务端刻事件（{@code ServerTickEvent.Post}）中调用。 */
    public static void tickPending() {
        if (++tickCounter % RETRY_INTERVAL_TICKS != 0) {
            return;
        }
        if (PENDING.isEmpty()) {
            return;
        }
        // 复制键集，避免 resolveNetwork 内部改动 PENDING 导致 ConcurrentModificationException
        for (RSNetworkCellInventory cell : new ArrayList<>(PENDING.keySet())) {
            try {
                cell.resolveNetwork();
            } catch (Throwable t) {
                LOGGER.warn("[rs2ae_cell] tickPending 中 resolveNetwork 异常（已忽略）", t);
            }
        }
    }

    private void registerPending() {
        if (!PENDING.containsKey(this)) {
            PENDING.put(this, Boolean.TRUE);
            LOGGER.info("[rs2ae_cell] 单元已加入待解析队列（RS 未就绪），服务端将每秒重试直到 RS 可达");
        }
    }

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
        LOGGER.info("[rs2ae_cell] cell constructed; bound={}, levelPresent={}, host={}",
                bound, level != null, host != null ? host.getClass().getSimpleName() : "null");
        resolveNetwork();
        if (root == null) {
            // RS 尚未可达（区块未加载 / 控制器未连网）：加入待解析队列，由服务端刻轮询重试，
            // 一旦 RS 上线即自动接入，无需玩家重新插拔单元。
            registerPending();
        }
    }

    private void resolveNetwork() {
        try {
            if (level == null) {
                LOGGER.warn("[rs2ae_cell] resolveNetwork: level==null (host 不是 BlockEntity？host={})，无法解析 RS 网络",
                        host != null ? host.getClass().getSimpleName() : "null");
                return;
            }
            if (bound == null) {
                LOGGER.warn("[rs2ae_cell] resolveNetwork: 未绑定 RS 网络方块，无法解析");
                return;
            }
            if (level.isClientSide()) {
                return;
            }
            BlockEntity be = level.getBlockEntity(bound);
            if (be == null) {
                LOGGER.warn("[rs2ae_cell] resolveNetwork: 绑定坐标 {} 处无 BlockEntity（方块被移除/维度不符？）",
                        bound);
                return;
            }
            var cap = RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability();
            NetworkNodeContainerProvider provider = null;
            Direction usedDir = null;
            for (Direction d : DIRECTIONS) {
                try {
                    NetworkNodeContainerProvider p = level.getCapability(cap, bound, d);
                    if (p != null) {
                        provider = p;
                        usedDir = d;
                        break;
                    }
                } catch (Throwable ignore) {
                    // 某个方向查询异常则跳过，试下一个
                }
            }
            if (provider == null) {
                LOGGER.warn("[rs2ae_cell] resolveNetwork: 在 {} 处查不到 NetworkNodeContainerProvider 能力"
                        + "（RS 方块缺失 / 区块未加载 / 维度不符？），将定时重试", bound);
                registerPending();
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
                // RS 当前未接入网络（绑定方块尚未联网）：保持重试，直到 RS 上线。
                boolean had = this.network != null;
                if (had) {
                    detach();
                    invalidateCache(); // 之前连着、现在断了 → 清掉 AE 侧残留旧视图
                }
                registerPending();
                LOGGER.warn("[rs2ae_cell] resolveNetwork: 在 {} 处找到 RS 能力，但其网络节点尚未加入 RS 网络"
                        + "（控制器未连接？），将定时重试", bound);
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
            int size = (this.root != null) ? this.root.getAll().size() : -1;
            LOGGER.info("[rs2ae_cell] resolveNetwork: 成功解析 RS 网络 @{} (dir={}, root={}, 资源种类数={})",
                    bound, usedDir, root != null, size);
            PENDING.remove(this);
            registerListener();
        } catch (Throwable t) {
            LOGGER.error("[rs2ae_cell] resolveNetwork 失败；单元暂时不生效", t);
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
                LOGGER.warn("[rs2ae_cell] listener 回调异常（已忽略）", t);
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
            LOGGER.warn("[rs2ae_cell] invalidateCache 失败（已忽略）", t);
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
                    LOGGER.warn("[rs2ae_cell] rebuild 时跳过某条 RS 资源", e);
                }
            }
            dirty = false;
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[rs2ae_cell] rebuild 完成：暴露给 AE 的资源种类数={}", cache.size());
            }
        } catch (Throwable t) {
            LOGGER.error("[rs2ae_cell] rebuild 失败；单元降级为空直到 RS 恢复", t);
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
            LOGGER.error("[rs2ae_cell] getAvailableStacks 失败；降级为空", t);
        }
    }

    @Override
    public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
        // 双向桥接：AE 经此单元把物品写入 RS 网络。
        if (amount <= 0 || key == null || root == null || !filter.test(key)) {
            return 0;
        }
        ResourceKey rsKey = KeyConverter.toRSKey(key);
        if (rsKey == null) {
            return 0;
        }
        int size = (int) Math.min(amount, Integer.MAX_VALUE);
        try {
            long inserted = root.insert(rsKey, size,
                    mode == Actionable.MODULATE ? Action.EXECUTE : Action.SIMULATE, AE_ACTOR);
            if (mode == Actionable.MODULATE && inserted > 0) {
                dirty = true;
            }
            return inserted;
        } catch (Throwable t) {
            LOGGER.error("[rs2ae_cell] insert 失败；返回 0", t);
            return 0;
        }
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
            LOGGER.error("[rs2ae_cell] extract 失败；返回 0", t);
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
            LOGGER.warn("[rs2ae_cell] persist 移除监听失败（已忽略）", t);
        }
    }

    /** 主动解除与 RS 网络的绑定（单元从驱动器移除时调用，防止监听泄漏）。 */
    public void detach() {
        try {
            if (root != null && listener != null) {
                root.removeListener(listener);
            }
        } catch (Throwable t) {
            LOGGER.warn("[rs2ae_cell] detach 移除监听失败（已忽略）", t);
        }
        this.listener = null;
        this.network = null;
        this.root = null;
    }
}
