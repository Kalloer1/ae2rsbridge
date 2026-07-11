package com.ae2rsbridge.blockentity;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
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
import com.ae2rsbridge.integration.rs.AENetworkToRSFluidStorage;
import com.ae2rsbridge.integration.rs.AENetworkToRSItemStorage;
import com.ae2rsbridge.integration.rs.BridgeNetworkNode;
import com.ae2rsbridge.integration.rs.BridgeNodeOwner;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.node.INetworkNode;
import com.refinedmods.refinedstorage.api.network.node.INetworkNodeProxy;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.capability.NetworkNodeProxyCapability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumSet;

public class StorageBridgeBlockEntity extends BlockEntity
        implements IGridConnectedBlockEntity, IStorageProvider, IAEPowerStorage,
        INetworkNodeProxy<BridgeNetworkNode>, BridgeNodeOwner, IConfigurableObject,
        IPriorityHost, ISubMenuHost {

    private static final double AE_DRAW_RATE = 1000.0;

    private final IManagedGridNode mainNode;
    private final RSNetworkToAEStorage rsToAeStorage;
    private final AENetworkToRSItemStorage aeToRsItemStorage;
    private final AENetworkToRSFluidStorage aeToRsFluidStorage;
    private final BridgeEnergyStorage energyBridge;
    private LazyOptional<IEnergyStorage> energyCapability;

    @Nullable
    private BridgeNetworkNode rsNode;
    private LazyOptional<INetworkNodeProxy<BridgeNetworkNode>> nodeProxyCapability;
    private boolean rsNodeRegistered = false;
    private boolean initialized = false;

    private boolean wasRSConnected = false;
    private boolean wasAE2Connected = false;

    private final BridgeConfigManager configManager;

    public StorageBridgeBlockEntity(BlockPos pos, BlockState state) {
        super(AE2RSBridge.STORAGE_BRIDGE_ENTITY.get(), pos, state);
        this.energyBridge = new BridgeEnergyStorage();
        this.rsToAeStorage = new RSNetworkToAEStorage(this);
        this.aeToRsItemStorage = new AENetworkToRSItemStorage(this);
        this.aeToRsFluidStorage = new AENetworkToRSFluidStorage(this);
        this.energyCapability = LazyOptional.of(() -> energyBridge);
        this.nodeProxyCapability = LazyOptional.of(() -> this);

        this.configManager = new BridgeConfigManager(this::setChanged);

        this.mainNode = GridHelper.createManagedNode(this, BlockEntityNodeListener.INSTANCE)
                .setVisualRepresentation(state.getBlock())
                .setInWorldNode(true)
                .setExposedOnSides(EnumSet.allOf(Direction.class))
                .setTagName("proxy")
                .setIdlePowerUsage(0.0)
                .addService(IStorageProvider.class, this)
                .addService(IAEPowerStorage.class, this);
    }

    @Override
    public IManagedGridNode getMainNode() {
        return mainNode;
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
        storageMounts.mount(rsToAeStorage);
    }

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

    @Nonnull
    @Override
    public BridgeNetworkNode getNode() {
        if (rsNode == null && level != null) {
            rsNode = new BridgeNetworkNode(this, level, worldPosition);
        }
        if (rsNode == null) {
            throw new IllegalStateException("BridgeNetworkNode not initialized");
        }
        return rsNode;
    }

    @Override
    public AENetworkToRSItemStorage getAeToRsItemStorage() {
        return aeToRsItemStorage;
    }

    @Override
    public AENetworkToRSFluidStorage getAeToRsFluidStorage() {
        return aeToRsFluidStorage;
    }

    @Override
    public ItemStack getDisplayStack() {
        return new ItemStack(AE2RSBridge.STORAGE_BRIDGE_ITEM.get());
    }

    @Nullable
    public INetwork getRSNetwork() {
        if (rsNode != null) {
            return rsNode.getNetwork();
        }
        return null;
    }

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

    public Component getAE2Status() {
        if (mainNode.isReady()) {
            return Component.literal("已连接");
        }
        return Component.literal("未连接");
    }

    public Component getRSStatus() {
        INetwork rsNetwork = getRSNetwork();
        if (rsNetwork != null && rsNetwork.canRun()) {
            return Component.literal("已连接");
        }
        return Component.literal("未连接");
    }

    public boolean isAE2Connected() {
        return mainNode.isReady();
    }

    public boolean isRSConnected() {
        INetwork rsNetwork = getRSNetwork();
        return rsNetwork != null && rsNetwork.canRun();
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

    public void serverTick() {
        if (level == null || level.isClientSide()) return;

        energyBridge.resetTickExtract();

        drawEnergyFromAE2();

        if (energyBridge.isActiveOutput()) {
            pushEnergyToNeighbors();
        }

        INetwork rsNetwork = getRSNetwork();
        boolean rsRunning = rsNetwork != null && rsNetwork.canRun();
        if (rsRunning) {
            aeToRsItemStorage.update(rsNetwork);
            aeToRsFluidStorage.update(rsNetwork);
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

        if ((!rsRunning || !rsNodeRegistered) && level instanceof ServerLevel serverLevel && (level.getGameTime() % 20 == 0 || !rsNodeRegistered)) {
            var manager = API.instance().getNetworkNodeManager(serverLevel);
            if (manager != null) {
                INetworkNode existingNode = manager.getNode(worldPosition);
                if (existingNode instanceof BridgeNetworkNode existingBridgeNode) {
                    existingBridgeNode.setOwner(this);
                    this.rsNode = existingBridgeNode;
                    if (existingBridgeNode.getNetwork() != null) {
                        existingBridgeNode.getNetwork().getNodeGraph().invalidate(
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM,
                                level, worldPosition);
                    }
                    rsNodeRegistered = true;
                } else if (this.rsNode != null) {
                    manager.setNode(worldPosition, this.rsNode);
                    rsNodeRegistered = true;
                }
            }
        }
    }

    private void onRSConnectionChanged(boolean connected) {
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

    private void onAE2ConnectionChanged(boolean connected) {
        if (level instanceof ServerLevel serverLevel) {
            var manager = API.instance().getNetworkNodeManager(serverLevel);
            if (manager != null) {
                INetworkNode node = manager.getNode(worldPosition);
                if (node instanceof BridgeNetworkNode bridgeNode && bridgeNode.getNetwork() != null) {
                    bridgeNode.getNetwork().getNodeGraph().invalidate(
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM,
                            level, worldPosition);
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

        int energyToPush = energyBridge.getFECurrentPower();
        if (energyToPush <= 0) {
            return;
        }

        energyToPush = Math.min(energyToPush, 2000);
        int totalPushed = 0;

        for (Direction dir : Direction.values()) {
            if (totalPushed >= energyToPush) {
                break;
            }
            BlockPos adjacentPos = worldPosition.relative(dir);
            BlockEntity be = level.getBlockEntity(adjacentPos);
            if (be != null) {
                var energyStorage = be.getCapability(ForgeCapabilities.ENERGY, dir.getOpposite()).orElse(null);
                if (energyStorage != null && energyStorage.canReceive()) {
                    int pushed = energyStorage.receiveEnergy(energyToPush - totalPushed, false);
                    totalPushed += pushed;
                }
            }
        }

        if (totalPushed > 0) {
            energyBridge.extractEnergy(totalPushed, false);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            initialized = false; // will be set by onFirstTick
            rsNodeRegistered = false;
        }
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        GridHelper.onFirstTick(this, StorageBridgeBlockEntity::onFirstTick);
    }

    private static void onFirstTick(StorageBridgeBlockEntity be) {
        if (be.level == null || be.level.isClientSide()) {
            return;
        }

        if (!be.mainNode.isReady()) {
            be.mainNode.create(be.level, be.worldPosition);
        }

        if (be.level instanceof ServerLevel serverLevel) {
            var manager = API.instance().getNetworkNodeManager(serverLevel);
            if (manager != null) {
                INetworkNode existingNode = manager.getNode(be.worldPosition);
                if (existingNode instanceof BridgeNetworkNode existingBridgeNode) {
                    existingBridgeNode.setOwner(be);
                    be.rsNode = existingBridgeNode;
                    // Re-register and force RS graph rebuild
                    manager.setNode(be.worldPosition, be.rsNode);
                    if (existingBridgeNode.getNetwork() != null) {
                        existingBridgeNode.getNetwork().getNodeGraph().invalidate(
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM,
                                be.level, be.worldPosition);
                    }
                } else {
                    be.rsNode = new BridgeNetworkNode(be, be.level, be.worldPosition);
                    manager.setNode(be.worldPosition, be.rsNode);
                }
            } else {
                be.rsNode = new BridgeNetworkNode(be, be.level, be.worldPosition);
            }
            be.rsNodeRegistered = true;
        }

        be.wasRSConnected = be.isRSConnected();
        be.initialized = true;
    }

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        mainNode.saveToNBT(tag);
        tag.putInt("feEnergy", energyBridge.getFECurrentPower());
        tag.putBoolean("activeOutput", energyBridge.isActiveOutput());
        configManager.writeToNBT(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        mainNode.loadFromNBT(tag);
        if (tag.contains("feEnergy")) {
            energyBridge.setFEEnergy(tag.getInt("feEnergy"));
        }
        if (tag.contains("activeOutput")) {
            energyBridge.setActiveOutput(tag.getBoolean("activeOutput"));
        }
        configManager.readFromNBT(tag);
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
            case AE2 -> configManager.setAE2Priority(newValue);
            case RS -> configManager.setRSPriority(newValue);
        }
        setChanged();
    }

    // ===== ISubMenuHost - 子菜单返回主菜单 =====
    @Override
    public void returnToMainMenu(Player player, ISubMenu subMenu) {
        // 重新打开主菜单
        if (player instanceof ServerPlayer serverPlayer) {
            com.ae2rsbridge.block.StorageBridgeBlock.openMainMenu(serverPlayer, this);
        }
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return new ItemStack(AE2RSBridge.STORAGE_BRIDGE_ITEM.get());
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        mainNode.destroy();

        if (level instanceof ServerLevel serverLevel) {
            var manager = API.instance().getNetworkNodeManager(serverLevel);
            if (manager != null) {
                INetworkNode node = manager.getNode(worldPosition);
                if (node != null) {
                    manager.removeNode(worldPosition);
                    if (node.getNetwork() != null) {
                        node.getNetwork().getNodeGraph().invalidate(
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM,
                                level, worldPosition);
                    }
                }
            }
        }
        this.rsNode = null;
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ENERGY) {
            return energyCapability.cast();
        }
        if (cap == NetworkNodeProxyCapability.NETWORK_NODE_PROXY_CAPABILITY) {
            return nodeProxyCapability.cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        energyCapability.invalidate();
        nodeProxyCapability.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        energyCapability = LazyOptional.of(() -> energyBridge);
        nodeProxyCapability = LazyOptional.of(() -> this);
    }
}
