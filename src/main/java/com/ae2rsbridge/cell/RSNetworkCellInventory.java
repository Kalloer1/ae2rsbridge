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
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * 同网络单主单元表：避免<b>两个单元绑定同一个 RS 网络</b>时被 AE2 当成两个独立存储源，
     * 从而把同一份 RS 内容重复计入（幻影数量 / 重复计数 / 提取错乱）——这正是「两个单元绑同网络放进驱动器」的严重 bug。
     * <p>
     * 以 (维度 + 方块坐标) 为键，记录当前<b>唯一生效</b>的主单元（primary）。只有主单元向 AE2 暴露 RS 内容；
     * 同一网络上的其余单元进入「闲置 (inert)」：不读取、不写入、不注册监听，仅打日志 + tooltip 提示。
     * <p>
     * 键由绑定数据 (dim + blockpos) 构成，与 AE2 的 host 无关；用 {@link WeakReference} 持有主单元，
     * 主单元被驱动器卸载并 GC 后条目自动失效，另一个同网络单元可在下次重试时接管，无需玩家重插拔。
     * host==null 的预览单元（物品渲染 / 创造栏等非网格上下文）不参与抢占，避免它们抢走主单元导致真正装入驱动器的单元失效。
     */
    private static final Map<NetworkKey, WeakReference<RSNetworkCellInventory>> PRIMARY =
            new ConcurrentHashMap<>();

    /** 同网络单主表的键：(维度, 方块坐标)。 */
    private static final class NetworkKey {
        final ResourceLocation dim;
        final long pos;

        NetworkKey(ResourceLocation dim, long pos) {
            this.dim = dim;
            this.pos = pos;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof NetworkKey other)) {
                return false;
            }
            return pos == other.pos && dim.equals(other.dim);
        }

        @Override
        public int hashCode() {
            return 31 * dim.hashCode() + Long.hashCode(pos);
        }

        @Override
        public String toString() {
            return dim + "@" + pos;
        }
    }

    /** 由 {@link com.ae2rsbridge.AE2RSBridge} 在服务端刻事件（{@code ServerTickEvent.Post}）中调用。 */
    public static void tickPending(MinecraftServer server) {
        if (++tickCounter % RETRY_INTERVAL_TICKS != 0) {
            return;
        }
        // 清理同网络主表中已 GC 的失效条目，避免静态映射无限增长。
        synchronized (PRIMARY) {
            PRIMARY.entrySet().removeIf(e -> e.getValue().get() == null);
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
            LOGGER.info("[rs2ae_cell] 单元已加入待解析队列（RS 未就绪 / 待抢占同网络主单元），服务端将每秒重试");
        }
    }

    /**
     * 在同网络单主表中抢占本单元所在 RS 网络的主单元身份（仅 {@code host!=null} 的网格内单元参与）。
     * <ul>
     *   <li>网络空闲 → 本单元成为 primary，正常暴露内容。</li>
     *   <li>网络已被另一存活单元占用 → 本单元进入 <b>inert</b>：不读不写不监听，避免 AE2 把同一 RS 内容重复计入。</li>
     *   <li>原记录指向已 GC 的主单元 → 视为空闲，本单元接管。</li>
     * </ul>
     * 预览单元（host==null）不会调用本方法。
     */
    private void tryClaim() {
        if (bound == null || dimLoc == null) {
            return;
        }
        NetworkKey key = new NetworkKey(dimLoc, bound.asLong());
        synchronized (PRIMARY) {
            WeakReference<RSNetworkCellInventory> ref = PRIMARY.get(key);
            RSNetworkCellInventory prim = (ref == null) ? null : ref.get();
            if (prim == null) {
                PRIMARY.put(key, new WeakReference<>(this));
                this.isPrimary = true;
                this.isInert = false;
            } else if (prim == this) {
                this.isPrimary = true;
                this.isInert = false;
            } else {
                this.isPrimary = false;
                this.isInert = true;
                LOGGER.warn("[rs2ae_cell] 单元绑定的 RS 网络 {} 已被另一个单元占用"
                        + "（同一 RS 网络只能有一个单元生效，防重复计入）；本单元进入闲置状态", key);
            }
        }
    }

    /** 单元从驱动器卸载时释放主单元身份，使同网络的其它单元可接管。 */
    private void releaseClaim() {
        if (bound == null || dimLoc == null) {
            return;
        }
        NetworkKey key = new NetworkKey(dimLoc, bound.asLong());
        synchronized (PRIMARY) {
            WeakReference<RSNetworkCellInventory> ref = PRIMARY.get(key);
            if (ref != null && ref.get() == this) {
                PRIMARY.remove(key);
            }
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

    /**
     * 是否「预览单元」：AE2 在非网格上下文（物品渲染 / 创造栏 / tooltip）也会调用
     * {@code getCellInventory(is, host)}，此时 host 为 null。这类单元不加入网格，
     * 也不参与同网络主单元抢占（否则会抢走真正装入驱动器的单元的主身份）。它们仍照常读取 RS 供渲染。
     */
    private final boolean isPreview;
    /** 是否当前网络唯一生效的主单元（向 AE2 暴露内容、注册监听）。 */
    private boolean isPrimary = false;
    /** 是否闲置：欲抢占但同网络已被另一主单元占用。闲置单元不读不写不监听，避免 AE2 双计数。 */
    private boolean isInert = false;

    public RSNetworkCellInventory(ItemStack is, @Nullable ISaveProvider host) {
        this.host = host;
        this.bound = RSNetworkStorageCellItem.getBoundRsBlock(is);
        this.dimLoc = RSNetworkStorageCellItem.getBoundDimension(is);
        // 过滤策略按物品栈当前的存入模式动态读取（单磁盘 + NBT 模式切换）：
        // 「仅不可堆叠」模式 → NON_STACKABLE（读全部、仅不可堆叠可写）；否则 → ALL（读写全部）。
        this.filter = (is.getItem() instanceof RSNetworkStorageCellItem)
                ? RSNetworkStorageCellItem.getFilter(is) : CellFilter.ALL;
        // AE2 把驱动器包成 ISaveProvider lambda 传进来；从中反射出驱动器网格节点，供 requestUpdate 推刷新。
        this.gridNode = extractGridNode(host);
        // 预览单元（host==null）：AE2 的 ME IO 端口调用 StorageCells.getCellInventory(cell, null) 时
        // host 即为 null（见 AE2 IOPortBlockEntity.transferContents 第 286 行），故「处于 ME IO 端口」
        // ⇔ host==null ⇔ isPreview。预览单元不接入网格、不参与同网络主单元抢占；且其 getAvailableStacks
        // / extract 一律返回空（见下方守卫），ME IO 端口便无法把它当成普通单元格抽干 RS（防物品进虚空）。
        // 普通驱动器 / ME 箱传进来的 host 非 null，不受影响。
        this.isPreview = (host == null);
        if (!isPreview) {
            tryClaim();
        }
        LOGGER.debug("[rs2ae_cell] cell constructed; bound={}, dim={}, host={}, gridNode={}, primary={}, inert={}, preview={}",
                bound, dimLoc, host != null ? host.getClass().getSimpleName() : "null",
                gridNode != null ? "ok" : "null", isPrimary, isInert, isPreview);
        // 立刻尝试解析（若在服务端线程且维度已加载）；否则入队由 tick 重试。
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            resolveNetwork(server);
        }
        if (isInert) {
            // 同网络已被占用：保持入队，由服务端刻重试抢占（原主单元卸载后本单元可接管）。
            registerPending();
        } else if (root == null) {
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
            // 每次解析都重新尝试抢占：若原主单元已卸载 GC，闲置单元可在此接管并成为主单元。
            if (!isPreview) {
                tryClaim();
            }
            if (isInert) {
                // 同网络仍被占用：保持入队，等原主单元卸载后再接管。本单元不连接 RS、不注册监听。
                registerPending();
                return;
            }
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
                if (aeKey != null && filter.allowsRead(aeKey)) {
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
        // ME IO 端口导出保护：ME IO 端口以 host==null（即 isPreview）调用本单元。对其暴露为空，
        // ME IO 端口便不会尝试导出（避免把 RS 抽干进虚空）。普通驱动器 / ME 箱 host 非 null，正常暴露。
        if (isPreview) {
            return;
        }
        if (isInert) {
            // 闲置单元：不向 AE2 暴露任何内容（避免与同网络主单元重复计入）。
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
        // 闲置单元：不写入（避免与同网络主单元重复计入）。
        if (isInert) {
            return 0;
        }
        // 单向桥接：AE 经此单元把物品写入 RS 网络。写入受 CellFilter.allowsInsert 限制
        // （NON_STACKABLE 仅接受不可堆叠物品；读取范围 allowsRead 与此无关，详见 CellFilter）。
        if (amount <= 0 || key == null || !filter.allowsInsert(key)) {
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
        // 真实写入（MODULATE）——按<b>实测库存变化量</b>上报，物理守恒，杜绝虚空消失/重复：
        // RS 的 CompositeStorageImpl 在写入后做内部簿记时可能抛 ConcurrentModificationException，
        // 此时物品可能只写入了一部分、甚至没写入。旧实现「无论成败都按 SIMULATE 的 sim 上报」是错的——
        // insert 返回值是「AE 应从自身库存扣减的数量」；若 EXECUTE 中途 CME 导致实际只写入部分/未写入，
        // AE 却按全额 sim 扣减，就会「AE 扣了、RS 没收到」→ 物品凭空消失。
        // 正确做法：EXECUTE 前记 before=get(key)，EXECUTE 后记 after，用 (after-before) 作为真正进入 RS
        // 的数量上报；AE 只扣这么多，与 RS 实际增量严格一致 → 绝不丢、绝不重复。
        try {
            RootStorage r = getRoot();
            if (r == null) {
                return 0;
            }
            int size = (int) Math.min(amount, Integer.MAX_VALUE);
            long before = r.get(rsKey);
            // 先 SIMULATE 探明 RS 能否接收（满了则直接返回 0，不触碰 EXECUTE，避免无谓的 CME 风险）。
            long sim = r.insert(rsKey, size, Action.SIMULATE, AE_ACTOR);
            if (sim <= 0) {
                return 0;
            }
            try {
                r.insert(rsKey, (int) sim, Action.EXECUTE, AE_ACTOR);
            } catch (Throwable t) {
                LOGGER.warn("[rs2ae_cell] insert(EXECUTE) 触发 RS 内部异常；改按实测库存变化量上报（不丢不重）", t);
            }
            RootStorage r2 = getRoot();
            long after = (r2 != null) ? r2.get(rsKey) : before;
            long actual = after - before;
            if (actual < 0) {
                actual = 0;        // 库存反常减少：不上报负数
            }
            if (actual > size) {
                actual = size;     // 保护：不超过本次请求量
            }
            if (actual > 0) {
                markDirtyAndNotify();
            }
            return actual;
        } catch (Throwable t) {
            LOGGER.error("[rs2ae_cell] insert 失败；返回 0", t);
            return 0;
        }
    }

    @Override
    public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
        // 闲置单元：不提取（避免与同网络主单元重复计入）。
        if (isInert) {
            return 0;
        }
        // ME IO 端口导出保护：本单元格是通往 RS 的实时窗口。ME IO 端口以 host==null（即 isPreview）调用本单元，
        // 此时一律拒绝抽取（SIMULATE / MODULATE 都不抽），RS 物品安全留在 RS，避免被「导出」语义抽干、
        // 且因 AE 网络无真实存储而进虚空。普通 AE 网络读取（终端取放）宿主非 null，不受影响。
        if (isPreview) {
            LOGGER.debug("[rs2ae_cell] 预览/ME IO 端口上下文尝试抽取 RS 内容；已拒绝对 RS 网络的抽干（防物品进虚空）");
            return 0;
        }
        // 提取（把 RS 物品拉进 AE）属「读取」范畴：不受写入限制，两种类型都允许取出全部物品。
        if (amount <= 0 || key == null || !filter.allowsRead(key)) {
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
        // 真实删除（MODULATE）——按<b>实测库存变化量</b>上报，物理守恒，杜绝虚空消失/重复：
        // RS 的 CompositeStorageImpl.extract 在删除物品、做内部簿记时可能抛 ConcurrentModificationException，
        // 此时物品可能只删了一部分、甚至没删。旧实现「无论成败都按 SIMULATE 的 sim 上报」是错的——
        // 它假设 EXECUTE 一定完整删除了 sim 个，一旦 CME 中途抛出导致部分/未删除，AE 却按全额 sim 入账，
        // 就会 AE 与 RS 账目不平 →「凭空消失」或「凭空多出」。
        // 正确做法：EXECUTE 前用 RootStorage.get(key) 记下真实库存 before，EXECUTE 后再取 after，
        // 用 (before-after) 作为真正离开 RS 的数量上报给 AE。服务端单线程，before/after 夹住单次
        // EXECUTE 之间没有其它代码运行，delta 精确等于本次操作的实际效果，绝不会丢/重。
        try {
            RootStorage r = getRoot();
            if (r == null) {
                return 0;
            }
            int size = (int) Math.min(amount, Integer.MAX_VALUE);
            long before = r.get(rsKey);
            if (before <= 0) {
                return 0;
            }
            int want = (int) Math.min((long) size, before);
            if (want <= 0) {
                return 0;
            }
            try {
                r.extract(rsKey, want, Action.EXECUTE, AE_ACTOR);
            } catch (Throwable t) {
                LOGGER.warn("[rs2ae_cell] extract(EXECUTE) 触发 RS 内部异常；改按实测库存变化量上报（不丢不重）", t);
            }
            RootStorage r2 = getRoot();
            long after = (r2 != null) ? r2.get(rsKey) : before;
            long actual = before - after;
            if (actual < 0) {
                actual = 0;        // 库存反常增加：不上报负数
            }
            if (actual > want) {
                actual = want;     // 保护：不超过本次请求量
            }
            if (actual > 0) {
                markDirtyAndNotify();
            }
            return actual;
        } catch (Throwable t) {
            LOGGER.error("[rs2ae_cell] extract 失败；返回 0", t);
            return 0;
        }
    }

    @Override
    public Component getDescription() {
        if (isInert) {
            return Component.literal("RS Network Cell (闲置: 同网络已被占用)");
        }
        return Component.literal("RS Network Cell (" + filter.name().toLowerCase() + ")");
    }

    @Override
    public CellState getStatus() {
        if (isInert) {
            return CellState.EMPTY;
        }
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
        // 单元从网格卸载：释放同网络主单元身份，使同网络其它单元可接管。
        if (isPrimary) {
            releaseClaim();
            isPrimary = false;
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
