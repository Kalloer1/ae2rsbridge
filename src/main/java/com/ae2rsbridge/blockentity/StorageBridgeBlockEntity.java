package com.ae2rsbridge.blockentity;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.energy.IAEPowerStorage;
import appeng.api.storage.ISubMenuHost;
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.IStorageProvider;
import appeng.api.util.AECableType;
import appeng.api.util.IConfigManager;
import appeng.api.util.IConfigurableObject;
import appeng.helpers.IPriorityHost;
import appeng.me.helpers.BlockEntityNodeListener;
import appeng.me.helpers.IGridConnectedBlockEntity;
import appeng.menu.ISubMenu;
import com.ae2rsbridge.AE2RSBridge;
import com.ae2rsbridge.bridge.BridgeConfigManager;
import com.ae2rsbridge.bridge.BridgeEnergyStorage;
import com.ae2rsbridge.integration.ae2.RSNetworkToAEStorage;
import com.ae2rsbridge.integration.rs.AE2NetworkToRSStorage;
import com.ae2rsbridge.integration.rs.BridgeNetworkNode;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.common.api.support.network.AbstractNetworkNodeContainerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.EnumSet;

/**
 * 存储桥接方块实体。
 * <p>
 * 同时承载两侧：
 * <ul>
 *     <li><b>RS2 侧</b>：继承 {@link AbstractNetworkNodeContainerBlockEntity}，其内置的
 *         {@link BridgeNetworkNode}（RS2 {@code ExternalStorageNetworkNode}）把 AE2 库存作为
 *         外部存储接入 RS 网络。节点生命周期、增量 diff、终端刷新全部由 RS2 自动处理，
 *         无需旧版 RS1 的 {@code NetworkNodeManager}/{@code unloaded} 等 hack。</li>
 *     <li><b>AE2 侧</b>：实现 {@link IGridConnectedBlockEntity}，把 {@link RSNetworkToAEStorage}
 *         挂载到 AE2 网格，使 RS 库存对 AE2 终端可见。</li>
 * </ul>
 */
public class StorageBridgeBlockEntity extends AbstractNetworkNodeContainerBlockEntity<BridgeNetworkNode>
        implements IGridConnectedBlockEntity, IStorageProvider, IAEPowerStorage,
        IConfigurableObject, IPriorityHost, ISubMenuHost {

    private static final double AE_DRAW_RATE = 1000.0;

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageBridgeBlockEntity.class);

    private final IManagedGridNode mainNode;
    private final RSNetworkToAEStorage rsToAeStorage;
    private final AE2NetworkToRSStorage aeToRsStorage;
    private final BridgeEnergyStorage energyBridge;

    private boolean wasRSConnected = false;
    private boolean wasAE2Connected = false;

    private final BridgeConfigManager configManager;

    public StorageBridgeBlockEntity(BlockPos pos, BlockState state) {
        super(AE2RSBridge.STORAGE_BRIDGE_ENTITY.get(), pos, state, new BridgeNetworkNode(0));
        this.energyBridge = new BridgeEnergyStorage();
        this.rsToAeStorage = new RSNetworkToAEStorage(this);
        this.aeToRsStorage = new AE2NetworkToRSStorage(this);
        this.configManager = new BridgeConfigManager(this::onConfigChanged);

        this.mainNode = GridHelper.createManagedNode(this, BlockEntityNodeListener.INSTANCE)
                .setVisualRepresentation(state.getBlock())
                .setInWorldNode(true)
                .setExposedOnSides(EnumSet.allOf(Direction.class))
                .setTagName("proxy")
                .setIdlePowerUsage(0.0)
                .addService(IStorageProvider.class, this)
                .addService(IAEPowerStorage.class, this);
    }

    /**
     * RS2 容器初始化完成回调：把由 AE2 支撑的外部存储提供者注入节点，并设置 RS 侧优先级。
     */
    @Override
    protected void containerInitialized() {
        mainNetworkNode.initialize(aeToRsStorage);
        mainNetworkNode.getStorageConfiguration().setInsertPriority(getRSPriority());
    }

    /** 配置变化时：同步 RS 节点优先级，并请求 AE2 重新挂载本存储（使 AE2 优先级立即生效）。 */
    private void onConfigChanged() {
        setChanged();
        if (mainNetworkNode != null) {
            mainNetworkNode.getStorageConfiguration().setInsertPriority(getRSPriority());
        }
        if (mainNode != null && mainNode.isReady()) {
            IStorageProvider.requestUpdate(mainNode);
        }
    }

    // ===== RS2 侧访问 =====

    @Nullable
    public Network getRSNetwork() {
        return mainNetworkNode.getNetwork();
    }

    public AE2NetworkToRSStorage getAE2NetworkToRSStorage() {
        return aeToRsStorage;
    }

    // ===== AE2 侧访问（供 RS 存储提供者读取） =====

    @Nullable
    public appeng.api.storage.MEStorage getAE2Storage() {
        IGrid grid = mainNode.getGrid();
        if (grid == null) {
            return null;
        }
        return grid.getStorageService().getInventory();
    }

    // ===== IGridConnectedBlockEntity =====

    @Override
    public IManagedGridNode getMainNode() {
        return mainNode;
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State state) {
        // mainNode 加入/离开网格时主动让 AE2 重新读取挂载的 RS 存储，
        // 避免“RS 网络先连好、AE2 节点后就绪”导致首次读取为空且此后不再刷新。
        if (mainNode != null && mainNode.isReady()) {
            IStorageProvider.requestUpdate(mainNode);
        }
    }

    @Override
    public void saveChanges() {
        setChanged();
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return AECableType.SMART;
    }

    @Override
    public IConfigManager getConfigManager() {
        return configManager;
    }

    @Override
    public void mountInventories(IStorageMounts storageMounts) {
        LOGGER.info("[ae2rsbridge][diag] mountInventories: RS network null? " + (getRSNetwork() == null)
                + " AE2Access=" + getAE2Access() + " priority=" + getAE2Priority());
        // 将 AE2 侧优先级传递给 AE2 存储系统，否则 RS 桥默认 0 优先级，
        // 永远竞争不过 ME 驱动器/存储总线等。
        storageMounts.mount(rsToAeStorage, getAE2Priority());
    }

    // ===== IAEPowerStorage =====

    @Override
    public double injectAEPower(double amt, Actionable mode) {
        return energyBridge.injectAEPower(amt, mode);
    }

    @Override
    public double extractAEPower(double amt, Actionable mode, PowerMultiplier usePowerMultiplier) {
        return 0;
    }

    @Override
    public double getAEMaxPower() {
        return energyBridge.getAEMaxPower();
    }

    @Override
    public double getAECurrentPower() {
        return energyBridge.getAECurrentPower();
    }

    @Override
    public boolean isAEPublicPowerStorage() {
        return true;
    }

    @Override
    public AccessRestriction getPowerFlow() {
        return AccessRestriction.WRITE;
    }

    // ===== 显示 / 状态 =====

    public ItemStack getDisplayStack() {
        return new ItemStack(AE2RSBridge.STORAGE_BRIDGE_ITEM.get());
    }

    public Component getAE2Status() {
        return isAE2Connected() ? Component.literal("已连接") : Component.literal("未连接");
    }

    public Component getRSStatus() {
        return isRSConnected() ? Component.literal("已连接") : Component.literal("未连接");
    }

    public boolean isAE2Connected() {
        return mainNode.isReady();
    }

    public boolean isRSConnected() {
        Network network = mainNetworkNode.getNetwork();
        return network != null && mainNetworkNode.isActive();
    }

    public double getAEEnergy() {
        return energyBridge.getAECurrentPower();
    }

    public int getFEEnergy() {
        return energyBridge.getFECurrentPower();
    }

    public boolean isActiveOutput() {
        return energyBridge.isActiveOutput();
    }

    public void setActiveOutput(boolean active) {
        energyBridge.setActiveOutput(active);
    }

    // ===== 配置访问器（供菜单与界面使用） =====

    public int getAE2Priority() {
        return configManager.getAE2Priority();
    }

    public void setAE2Priority(int priority) {
        configManager.setAE2Priority(priority);
    }

    public AccessRestriction getAE2Access() {
        return configManager.getSetting(appeng.api.config.Settings.ACCESS);
    }

    public void setAE2Access(AccessRestriction access) {
        configManager.putSetting(appeng.api.config.Settings.ACCESS, access);
    }

    public int getRSPriority() {
        return configManager.getRSPriority();
    }

    public void setRSPriority(int priority) {
        configManager.setRSPriority(priority);
    }

    public boolean isNonStackableOnly() {
        return configManager.isNonStackableOnly();
    }

    public void setNonStackableOnly(boolean value) {
        configManager.setNonStackableOnly(value);
    }

    // ===== 能量能力（供 NeoForge 注册） =====

    public IEnergyStorage getEnergyStorage() {
        return energyBridge;
    }

    // ===== 服务端 tick =====

    public void serverTick() {
        if (level == null || level.isClientSide()) {
            return;
        }

        // === RS2 节点驱动 ===
        // 我们继承的是 AbstractNetworkNodeContainerBlockEntity（最简基类），
        // 没有走 AbstractBaseNetworkNodeContainerBlockEntity + NetworkNodeBlockEntityTicker
        // 这条标准路线，所以 setActive() 永远没人调、isActive() 永远 false，
        // 反映到 GUI 上就是"RS 网络未连接"。这里手动驱动：按网络存在性同步 activeness，
        // 并调用节点的 doWork()（基类能量抽取 + 我们的 detectChanges）让 RS 网络能感知 AE2 库存。
        if (mainNetworkNode != null) {
            boolean networkPresent = mainNetworkNode.getNetwork() != null;
            if (mainNetworkNode.isActive() != networkPresent) {
                mainNetworkNode.setActive(networkPresent);
            }
            if (networkPresent) {
                mainNetworkNode.doWork();
            }
        }

        energyBridge.resetTickExtract();
        drawEnergyFromAE2();
        if (energyBridge.isActiveOutput()) {
            pushEnergyToNeighbors();
        }

        boolean currentAE2Connected = isAE2Connected();
        if (currentAE2Connected != wasAE2Connected) {
            wasAE2Connected = currentAE2Connected;
            onAE2ConnectionChanged(currentAE2Connected);
        }

        boolean currentRSConnected = isRSConnected();
        if (currentRSConnected != wasRSConnected) {
            wasRSConnected = currentRSConnected;
            onRSConnectionChanged(currentRSConnected);
        }
    }

    private void onAE2ConnectionChanged(boolean connected) {
        // RS2 节点会在下一次 doWork 时通过 detectChanges 自动拾取 AE2 库存变化，
        // 无需像 RS1 那样手动重扫 RS 节点图。
    }

    private void onRSConnectionChanged(boolean connected) {
        // RS 连接状态变化：让 AE2 重新读取本存储（刷新 RS 物品在 AE2 终端的可见性）。
        if (mainNode.isReady()) {
            IGrid aeGrid = mainNode.getGrid();
            if (aeGrid != null) {
                var storageService = aeGrid.getService(appeng.api.networking.storage.IStorageService.class);
                if (storageService != null) {
                    storageService.invalidateCache();
                }
            }
        }
    }

    private void drawEnergyFromAE2() {
        IGrid grid = mainNode.getGrid();
        if (grid == null) {
            return;
        }
        IEnergyService energyService = grid.getEnergyService();
        if (energyService == null) {
            return;
        }
        double space = energyBridge.getAEMaxPower() - energyBridge.getAECurrentPower();
        if (space <= 0) {
            return;
        }
        double toDraw = Math.min(AE_DRAW_RATE, space);
        double extracted = energyService.extractAEPower(toDraw, Actionable.MODULATE, PowerMultiplier.ONE);
        if (extracted > 0) {
            energyBridge.injectAEPower(extracted, Actionable.MODULATE);
        }
    }

    private void pushEnergyToNeighbors() {
        if (level == null || level.isClientSide()) {
            return;
        }
        int maxPushPerTick = com.ae2rsbridge.config.BridgeConfig.getEnergyOutputRateFEPerTick();
        if (maxPushPerTick <= 0) {
            return; // 配置为 0 关闭主动输出
        }
        int energyToPush = Math.min(energyBridge.getFECurrentPower(), maxPushPerTick);
        if (energyToPush <= 0) {
            return;
        }
        int totalPushed = 0;

        for (Direction dir : Direction.values()) {
            if (totalPushed >= energyToPush) {
                break;
            }
            BlockPos adjacentPos = worldPosition.relative(dir);
            IEnergyStorage energyStorage = level.getCapability(
                    Capabilities.EnergyStorage.BLOCK, adjacentPos, dir.getOpposite());
            if (energyStorage != null && energyStorage.canReceive()) {
                int pushed = energyStorage.receiveEnergy(energyToPush - totalPushed, false);
                totalPushed += pushed;
            }
        }

        if (totalPushed > 0) {
            energyBridge.extractEnergy(totalPushed, false);
        }
    }

    // ===== 生命周期 =====

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        // 基类已初始化 RS2 容器（注册 RS 节点）；这里再安排 AE2 网格节点创建。
        LOGGER.info("[ae2rsbridge][diag] clearRemoved() at " + worldPosition
                + " levelSet=" + (level != null) + " isClient=" + (level != null && level.isClientSide()));
        GridHelper.onFirstTick(this, StorageBridgeBlockEntity::onFirstTick);
    }

    private static void onFirstTick(StorageBridgeBlockEntity be) {
        if (be.level == null || be.level.isClientSide()) {
            return;
        }
        if (!be.mainNode.isReady()) {
            be.mainNode.create(be.level, be.worldPosition);
            LOGGER.info("[ae2rsbridge][diag] onFirstTick: mainNode.create() called, isReady="
                    + be.mainNode.isReady());
        }
        be.wasRSConnected = be.isRSConnected();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // 基类已移除 RS2 容器；这里销毁 AE2 网格节点。
        mainNode.destroy();
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        mainNode.saveToNBT(tag);
        tag.putInt("feEnergy", energyBridge.getFECurrentPower());
        tag.putBoolean("activeOutput", energyBridge.isActiveOutput());
        configManager.writeToNBT(tag, registries);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        mainNode.loadFromNBT(tag);
        if (tag.contains("feEnergy")) {
            energyBridge.setFEEnergy(tag.getInt("feEnergy"));
        }
        if (tag.contains("activeOutput")) {
            energyBridge.setActiveOutput(tag.getBoolean("activeOutput"));
        }
        configManager.readFromNBT(tag, registries);
    }

    // ===== IPriorityHost - 当前正在编辑的优先级目标 =====

    private PriorityTarget priorityTarget = PriorityTarget.AE2;

    public enum PriorityTarget { AE2, RS }

    public void setPriorityTarget(PriorityTarget target) {
        this.priorityTarget = target;
    }

    public PriorityTarget getPriorityTarget() {
        return priorityTarget;
    }

    @Override
    public int getPriority() {
        return switch (priorityTarget) {
            case AE2 -> configManager.getAE2Priority();
            case RS -> configManager.getRSPriority();
        };
    }

    @Override
    public void setPriority(int newValue) {
        switch (priorityTarget) {
            case AE2 -> {
                configManager.setAE2Priority(newValue);
                // 优先级改变后必须让 AE2 重新 mount 本节点，否则新优先级不生效
                IStorageProvider.requestUpdate(mainNode);
            }
            case RS -> configManager.setRSPriority(newValue);
        }
        setChanged();
    }

    // ===== ISubMenuHost - 子菜单返回主菜单 =====

    @Override
    public void returnToMainMenu(Player player, ISubMenu subMenu) {
        if (player instanceof ServerPlayer serverPlayer) {
            com.ae2rsbridge.block.StorageBridgeBlock.openMainMenu(serverPlayer, this);
        }
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return new ItemStack(AE2RSBridge.STORAGE_BRIDGE_ITEM.get());
    }
}
