package com.ae2rsbridge.cell;

import appeng.api.config.Actionable;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.StorageCell;
import appeng.api.storage.IStorageProvider;
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
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;
import com.refinedmods.refinedstorage.api.storage.root.RootStorageListener;
import com.refinedmods.refinedstorage.common.api.support.network.NetworkNodeContainerProvider;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 把 RS 网络以 AE2 原生存储单元（{@link StorageCell}）形式暴露给 AE2 网格 —— <b>单向桥接</b>。
 * <p>
 * <b>方向</b>：只有 <b>AE 网络</b>能访问 RS 网络（读取其物品 / 流体，并向其中存入物品）。
 * RS 网络<b>无法</b>反向访问 AE 网络（既不能读也不能改 AE 的物品与存储）。
 * <p>
 * <b>读取</b>：AE2 终端浏览 RS 内容；<b>写入</b>：AE2 经此单元把物品写入 RS（写入者在 RS 的存取记录中显示为 "AE"）。
 * <p>
 * <b>实时刷新</b>：注册 RS 的 {@link RootStorageListener}，RS 内容变动即调用
 * {@link IStorageProvider#requestUpdate(IManagedGridNode)} 让 AE2 重新扫描本单元，终端立即更新；
 * 单元挂载 / AE2 操作亦触发刷新。
 * <p>
 * <b>维度无关</b>：AE2 把驱动器包成 {@code ISaveProvider} lambda 传进来，它<b>不含</b>可用的 Level，
 * 因此本类通过绑定坐标 + 维度，在解析时用 {@code MinecraftServer.getLevel(dim)} 取得 Level，
 * 不依赖 AE2 的 host。刷新用的网格节点则从 host lambda 反射出驱动器后取得。
 * <p>
 * <b>健壮性（关键）</b>：本类被 AE2 驱动器在重建网格存储缓存时调用。任何 RS 侧异常都必须被吞掉并降级为空，
 * <b>绝不能向上抛给 AE2</b>——否则会让整个网格的 StorageService 崩溃，导致 AE 连它自己的物品都读不了 / 存不了。
 * 所有 RS 交互都包在 try/catch 中。
 */
public class RSNetworkCellInventory implements StorageCell {

    private static final Logger LOGGER = LoggerFactory.getLogger(RSNetworkCellInventory.class);

    /**
     * 虚拟玩家 "AE"：每当 AE 经此单元从 RS 提取或写入物品时，用这个 Actor 标注来源，
     * 使 RS 的「谁在何时存入 / 取出」记录里显示为玩家 <b>AE</b>，而非匿名的 Actor.EMPTY。
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
     * 待解析队列：当 RS 网络暂时不可达（区块未加载 / 控制器未连网 / 维度未加载 / 世界加载时
     * RS 图尚未重建）时，单元放入此队列，由 {@link #tickPending(MinecraftServer)} 在服务端每约 1 秒尝试
     * 重新解析一次。一旦 RS 可达即自动接入并移出队列，玩家无需重新插拔单元。
     * <p>
     * 这是修复「绑定后塞入驱动器但 AE 读不到 RS」的关键：AE2 只在挂载时调一次
     * {@code getAvailableStacks}，之后仅依赖 {@code requestUpdate} 才重新查询。若挂载瞬间 RS 未就绪，
     * 监听从未登记，单元会永久为空；此机制保证 RS 上线后自愈。
     * <p>
     * 用 WeakHashMap 以单元实例为键 —— 单元被驱动器卸载并 GC 后条目自动消失，不会内存泄漏。
     */
    private static final Map<RSNetworkCellInventory, Boolean> PENDING = new WeakHashMap<>();
    /**
     * 需要向 AE2 重新推送刷新的单元（RS 变动 / AE2 操作后）。服务端每约 1 秒对其中仍为脏的单元
     * 调用 {@code requestUpdate}，确保终端实时更新。弱引用，单元卸载后自动清理。
     */
    private static final Map<RSNetworkCellInventory, Boolean> NEEDS_NOTIFY = new WeakHashMap<>();
    private static final int RETRY_INTERVAL_TICKS = 20; // ~1s @ 20TPS
    private static int tickCounter = 0;

    /** 由 {@link com.ae2rsbridge.AE2RSBridge} 在服务端刻事件（{@code ServerTickEvent.Post}）中调用。 */
    public static void tickPending(MinecraftServer server) {
        if (++tickCounter % RETRY_INTERVAL_TICKS != 0) {
            return;
        }
        // 1) 重试尚未解析出 RS 网络的单元（自愈）。
        if (!PENDING.isEmpty()) {
            for (RSNetworkCellInventory cell : new ArrayList<>(PENDING.keySet())) {
                try {
                    cell.resolveNetwork(server);
                } catch (Throwable t) {
                    LOGGER.warn("[rs2ae_cell] tickPending 中 resolveNetwork 异常（已忽略）", t);
                }
            }
        }
        // 2) 向 AE2 重新推送需要刷新的单元（RS 变动 / AE2 操作导致内容变化）。
        if (!NEEDS_NOTIFY.isEmpty()) {
            for (RSNetworkCellInventory cell : new ArrayList<>(NEEDS_NOTIFY.keySet())) {
                try {
                    if (cell.dirty && cell.gridNode != null) {
                        IStorageProvider.requestUpdate(cell.gridNode);
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[rs2ae_cell] tickPending 中 requestUpdate 异常（已忽略）", t);
                }
                if (!cell.dirty) {
                    NEEDS_NOTIFY.remove(cell);
                }
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
    /** 绑定 RS 方块所在维度；通过 {@code MinecraftServer.getLevel(dim)} 取得 Level，不依赖 AE2 的 host。 */
    @Nullable private final ResourceLocation dimLoc;
    @Nullable private final BlockPos bound;
    @Nullable private ServerLevel level;
    @Nullable private Network network;
    @Nullable private RootStorage root;
    @Nullable private RootStorageListener listener;
    /** 驱动器（AE2 ME 驱动器）的网格节点，用于 requestUpdate 推刷新。从 host lambda 反射得到。 */
    @Nullable private IManagedGridNode gridNode;
    @Nullable private KeyCounter cache;
    private final CellFilter filter;
    private boolean dirty = true;

    public RSNetworkCellInventory(ItemStack is, @Nullable ISaveProvider host) {
        this.host = host;
        this.bound = RSNetworkStorageCellItem.getBoundRsBlock(is);
        this.dimLoc = RSNetworkStorageCellItem.getBoundDimension(is);
        this.filter = (is.getItem() instanceof RSNetworkStorageCellItem item) ? item.getFilter() : CellFilter.ALL;
        // AE2 把驱动器包成 ISaveProvider lambda 传进来；从中反射出驱动器网格节点，供 requestUpdate 推刷新。
        this.gridNode = extractGridNode(host);
        LOGGER.info("[rs2ae_cell] cell constructed; bound={}, dim={}, host={}, gridNode={}",
                bound, dimLoc, host != null ? host.getClass().getSimpleName() : "null",
                gridNode != null ? "ok" : "null");
        // 立刻尝试解析（若在服务端线程且维度已加载）；否则入队由 tick 重试。
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            resolveNetwork(server);
        }
        if (root == null) {
            // RS 尚未可达：加入待解析队列，由服务端刻轮询重试，一旦 RS 上线即自动接入，无需玩家重新插拔单元。
            registerPending();
        }
    }

    /**
     * 从 AE2 传入的 {@link ISaveProvider}（实为驱动器生成的 lambda，其内部捕获了 {@code DriveBlockEntity}）中，
     * 反射出被捕获的驱动器，进而取得它的网格节点。该节点用于 {@link IStorageProvider#requestUpdate} 推刷新。
     * <p>
     * 若反射因 AE2 内部结构变动而失败，返回 null（降级为挂载 / 操作时触发的刷新，仅外部 RS 变动的实时性减弱）。
     */
    @Nullable
    private static IManagedGridNode extractGridNode(@Nullable ISaveProvider host) {
        if (host == null) {
            return null;
        }
        try {
            for (Field f : host.getClass().getDeclaredFields()) {
                if (IGridConnectedBlockEntity.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object captured = f.get(host);
                    if (captured instanceof IGridConnectedBlockEntity gcb) {
                        return gcb.getMainNode();
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[rs2ae_cell] 无法从 host 反射出驱动器网格节点（推刷新将降级为挂载/操作时触发）", t);
        }
        return null;
    }

    private void resolveNetwork(@Nullable MinecraftServer server) {
        try {
            if (bound == null) {
                LOGGER.warn("[rs2ae_cell] resolveNetwork: 未绑定 RS 网络方块，无法解析");
                return;
            }
            if (dimLoc == null) {
                LOGGER.warn("[rs2ae_cell] resolveNetwork: 绑定数据缺少维度（旧版单元？请重新潜行右键 RS 方块绑定）");
                return;
            }
            if (server == null) {
                server = ServerLifecycleHooks.getCurrentServer();
            }
            if (server == null) {
                LOGGER.warn("[rs2ae_cell] resolveNetwork: 取不到 MinecraftServer（未在服务端线程？）");
                return;
            }
            ServerLevel lvl = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimLoc));
            if (lvl == null) {
                LOGGER.warn("[rs2ae_cell] resolveNetwork: 找不到维度 {} 的 ServerLevel（维度尚未加载？）", dimLoc);
                registerPending();
                return;
            }
            this.level = lvl;
            if (lvl.isClientSide()) {
                return;
            }
            BlockEntity be = lvl.getBlockEntity(bound);
            if (be == null) {
                LOGGER.warn("[rs2ae_cell] resolveNetwork: 绑定坐标 {} 处无 BlockEntity（方块被移除 / 区块未加载？）",
                        bound);
                registerPending();
                return;
            }
            var cap = RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability();
            NetworkNodeContainerProvider provider = null;
            Direction usedDir = null;
            for (Direction d : DIRECTIONS) {
                try {
                    NetworkNodeContainerProvider p = lvl.getCapability(cap, bound, d);
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
                    // 之前连着、现在断了 → 清掉 AE 侧残留旧视图（仅标记待刷新，由 tick 推，不可同步 requestUpdate）。
                    NEEDS_NOTIFY.put(this, Boolean.TRUE);
                }
                registerPending();
                LOGGER.warn("[rs2ae_cell] resolveNetwork: 在 {} 处找到 RS 能力，但其网络节点尚未加入 RS 网络"
                        + "（控制器未连接？），将定时重试", bound);
                return;
            }
            if (net == this.network) {
                // 同一网络，监听已登记，无需重复。
                return;
            }
            // 网络变了（或首次解析）：清理旧绑定后重新登记监听。
            if (this.network != null) {
                detach();
            }
            this.network = net;
            this.root = this.network.getComponent(StorageNetworkComponent.class);
            PENDING.remove(this);
            int size = (this.root != null) ? this.root.getAll().size() : -1;
            LOGGER.info("[rs2ae_cell] resolveNetwork: 成功解析 RS 网络 @{} (dir={}, root={}, 资源种类数={})",
                    bound, usedDir, root != null, size);
            registerListener();
            // 解析成功 → 标记待刷新，由服务端 tick 推 requestUpdate（绝不可在构造/挂载期同步 requestUpdate，否则重入栈溢出）。
            // 初始挂载时 AE2 会自行读取 getAvailableStacks，无需主动 requestUpdate。
            NEEDS_NOTIFY.put(this, Boolean.TRUE);
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
                self.markDirtyAndNotify();
            } catch (Throwable t) {
                // 监听回调里的异常不要向外冒泡（RS 线程），仅记录。
                LOGGER.warn("[rs2ae_cell] listener 回调异常（已忽略）", t);
            }
        };
        this.listener = holder[0];
        r.addListener(this.listener);
    }

    /**
     * 置脏标并登记到待推送队列，由服务端 tick（{@link #tickPending}）统一调用
     * {@link IStorageProvider#requestUpdate} 推刷新。
     * <p>
     * <b>禁止在此同步调用 requestUpdate</b>：单元格在 AE2 网格挂载 / 重建存储缓存期间被构造，
     * 此时若同步 requestUpdate → {@code refreshNodeStorageProvider} 重入 → 本单元再次被构造
     * → {@code resolveNetwork} → 再次 requestUpdate，形成无限递归直至 StackOverflowError，
     * 拖垮整个 AE 网格（这正是 41f5ec1 引入的致命 bug）。推送只能发生在服务端 tick 这种
     * 非挂载期的、安全的上下文中。
     */
    private void markDirtyAndNotify() {
        this.dirty = true;
        NEEDS_NOTIFY.put(this, Boolean.TRUE);
    }

    private void rebuild() {
        try {
            RootStorage r = getRoot();
            if (r == null) {
                // RS 网络尚未解析（单元放入时 RS 未上线，或绑定方块暂未联网）：重试解析。
                MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
                if (srv != null) {
                    resolveNetwork(srv);
                }
                r = getRoot();
            }
            cache = new KeyCounter();
            if (r == null) {
                // 仍无网络：保持 dirty 以便 AE2 下次查询时继续重试，直到 RS 上线。
                dirty = true;
                return;
            }
            try {
                addAllToCache(r);
            } catch (Throwable cme) {
                // RS 重建期间 getAll 可能抛 ConcurrentModificationException：用最新 RS 状态重试一次。
                LOGGER.warn("[rs2ae_cell] rebuild 首次 getAll 异常，用最新 RS 状态重试一次", cme);
                RootStorage r2 = getRoot();
                if (r2 != null) {
                    cache = new KeyCounter();
                    addAllToCache(r2);
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

    private void addAllToCache(RootStorage r) {
        for (ResourceAmount ra : r.getAll()) {
            try {
                com.refinedmods.refinedstorage.api.resource.ResourceKey resource = ra.resource();
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

    /**
     * 取得当前 RS 存储组件（RootStorage）。
     * <p>
     * 每次实时从 {@code network} 取，绝不使用过期实例——RS 重建网络存储时会换一个<b>新</b>的
     * {@code RootStorage} 实例；若仍持有旧实例，其 {@code extractSources} 正在被回收，
     * 在它上面调用 {@code extract/insert/getAll} 极易触发 {@code CompositeStorageImpl.extract} 的
     * {@link java.util.ConcurrentModificationException}（这正是 ME IO 端口批量导入时丢物品的元凶）。
     * 若取到的新实例与已注册监听的实例不同，自动把监听迁移过去，保证刷新不失效。
     */
    @Nullable
    private RootStorage getRoot() {
        if (network == null) {
            return null;
        }
        RootStorage r;
        try {
            r = network.getComponent(StorageNetworkComponent.class);
        } catch (Throwable t) {
            LOGGER.warn("[rs2ae_cell] getComponent(StorageNetworkComponent) 失败（已忽略）", t);
            return null;
        }
        if (r != null && r != this.root) {
            try {
                if (this.root != null && this.listener != null) {
                    this.root.removeListener(this.listener);
                }
            } catch (Throwable ignore) {
                // 旧实例可能正在被回收，忽略移除失败
            }
            this.root = r;
            registerListener();
        }
        return r;
    }

    @Override
    public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
        // 单向桥接：AE 经此单元把物品写入 RS 网络。
        if (amount <= 0 || key == null || !filter.test(key)) {
            return 0;
        }
        com.refinedmods.refinedstorage.api.resource.ResourceKey rsKey = KeyConverter.toRSKey(key);
        if (rsKey == null) {
            return 0;
        }
        // 只读查询（SIMULATE）：不修改 RS，绝不会触发 CME，直接走。
        if (mode != Actionable.MODULATE) {
            try {
                RootStorage r = getRoot();
                if (r == null) {
                    return 0;
                }
                return r.insert(rsKey, (int) Math.min(amount, Integer.MAX_VALUE), Action.SIMULATE, AE_ACTOR);
            } catch (Throwable t) {
                LOGGER.error("[rs2ae_cell] insert(SIMULATE) 失败；返回 0", t);
                return 0;
            }
        }
        // 真实写入（MODULATE）：RS 的 CompositeStorageImpl 在删除/新增物品后做内部簿记时（line 112）会抛
        // ConcurrentModificationException，而物品在异常抛出前已写入 RS。若直接 try/catch 返回 0，会导致
        // 「RS 已收到物品、AE 却以为没写入」→ AE 不扣减自身库存 → <b>重复计数</b>。
        // 正确做法：先用 SIMULATE 探明可写入量 sim，再 EXECUTE；无论成功还是抛 CME 都按 sim 上报
        // （物品确实已进入 RS），AE 据此扣减、RS 据此增加 → 不重复。
        try {
            RootStorage r = getRoot();
            if (r == null) {
                return 0;
            }
            int size = (int) Math.min(amount, Integer.MAX_VALUE);
            long sim = r.insert(rsKey, size, Action.SIMULATE, AE_ACTOR);
            if (sim <= 0) {
                return 0;
            }
            try {
                r.insert(rsKey, (int) sim, Action.EXECUTE, AE_ACTOR);
            } catch (Throwable t) {
                LOGGER.warn("[rs2ae_cell] insert(EXECUTE) 触发 RS 内部 ConcurrentModificationException；"
                        + "按已写入量 {} 上报（物品已写入 RS，未重复）", sim);
            }
            markDirtyAndNotify();
            return sim;
        } catch (Throwable t) {
            LOGGER.error("[rs2ae_cell] insert 失败；返回 0", t);
            return 0;
        }
    }

    @Override
    public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0 || key == null || !filter.test(key)) {
            return 0;
        }
        com.refinedmods.refinedstorage.api.resource.ResourceKey rsKey = KeyConverter.toRSKey(key);
        if (rsKey == null) {
            return 0;
        }
        // 只读查询（SIMULATE）：不修改 RS，绝不会触发 CME，直接走。
        if (mode != Actionable.MODULATE) {
            try {
                RootStorage r = getRoot();
                if (r == null) {
                    return 0;
                }
                return r.extract(rsKey, (int) Math.min(amount, Integer.MAX_VALUE), Action.SIMULATE, AE_ACTOR);
            } catch (Throwable t) {
                LOGGER.error("[rs2ae_cell] extract(SIMULATE) 失败；返回 0", t);
                return 0;
            }
        }
        // 真实删除（MODULATE）：RS 的 CompositeStorageImpl.extract 在删除物品、做内部簿记时（line 112）
        // 会抛 ConcurrentModificationException——而物品在异常抛出前已被删除。若这里直接 try/catch 返回 0，
        // 会导致「RS 物品已消失、AE 却以为没拿到」的<b>凭空丢失</b>（ME IO 端口批量导入 RS→AE 时正是此症状）。
        // 正确做法：先用 SIMULATE 探明可提取量 sim，再 EXECUTE；无论 EXECUTE 成功还是抛 CME，
        // 都按 sim 上报（物品确实已离开 RS），AE 据此入账 → 不丢物品。
        try {
            RootStorage r = getRoot();
            if (r == null) {
                return 0;
            }
            int size = (int) Math.min(amount, Integer.MAX_VALUE);
            long sim = r.extract(rsKey, size, Action.SIMULATE, AE_ACTOR);
            if (sim <= 0) {
                return 0;
            }
            try {
                r.extract(rsKey, (int) sim, Action.EXECUTE, AE_ACTOR);
            } catch (Throwable t) {
                LOGGER.warn("[rs2ae_cell] extract(EXECUTE) 触发 RS 内部 ConcurrentModificationException；"
                        + "按已提取量 {} 上报（物品已离开 RS，未丢失）", sim);
            }
            markDirtyAndNotify();
            return sim;
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
