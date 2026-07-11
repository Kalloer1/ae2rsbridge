package com.ae2rsbridge.integration.rs;

import com.ae2rsbridge.AE2RSBridge;
import net.minecraftforge.fluids.FluidStack;
import com.ae2rsbridge.blockentity.StorageBridgeBlockEntity;
import com.refinedmods.refinedstorage.apiimpl.network.node.NetworkNode;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.IStorage;
import com.refinedmods.refinedstorage.api.storage.IStorageProvider;
import com.refinedmods.refinedstorage.apiimpl.network.node.ConnectivityStateChangeCause;
import com.refinedmods.refinedstorage.apiimpl.storage.cache.FluidStorageCache;
import com.refinedmods.refinedstorage.apiimpl.storage.cache.ItemStorageCache;
import com.refinedmods.refinedstorage.util.LevelUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import com.refinedmods.refinedstorage.util.LevelUtils;

import javax.annotation.Nonnull;
import java.util.List;

public class BridgeNetworkNode extends NetworkNode implements IStorageProvider {

    public static final ResourceLocation ID = new ResourceLocation(AE2RSBridge.MODID, "bridge");

    private BridgeNodeOwner owner;

    public BridgeNetworkNode(BridgeNodeOwner owner, Level level, BlockPos pos) {
        super(level, pos);
        this.owner = owner;
    }

    public void setOwner(BridgeNodeOwner owner) {
        this.owner = owner;
    }

    @Override
    public int getEnergyUsage() {
        return 0;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Nonnull
    @Override
    public ItemStack getItemStack() {
        if (owner != null) {
            return owner.getDisplayStack();
        }
        return ItemStack.EMPTY;
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    private BridgeNodeOwner resolveOwner() {
        if (this.owner != null) {
            return this.owner;
        }
        if (level != null && level.getBlockEntity(pos) instanceof StorageBridgeBlockEntity be) {
            this.owner = be;
            return be;
        }
        return null;
    }

    @Override
    public void addItemStorages(List<IStorage<ItemStack>> storages) {
        BridgeNodeOwner actualOwner = resolveOwner();
        if (actualOwner != null) {
            AENetworkToRSItemStorage itemStorage = actualOwner.getAeToRsItemStorage();
            if (itemStorage != null) {
                storages.add(itemStorage);
            }
        }
    }

    @Override
    public void addFluidStorages(List<IStorage<FluidStack>> storages) {
        BridgeNodeOwner actualOwner = resolveOwner();
        if (actualOwner != null) {
            AENetworkToRSFluidStorage fluidStorage = actualOwner.getAeToRsFluidStorage();
            if (fluidStorage != null) {
                storages.add(fluidStorage);
            }
        }
    }

    @Override
    protected void onConnectedStateChange(INetwork network, boolean state, ConnectivityStateChangeCause cause) {
        network.getNodeGraph().runActionWhenPossible(
                ItemStorageCache.INVALIDATE_ACTION.apply(
                        com.refinedmods.refinedstorage.api.storage.cache.InvalidateCause.CONNECTED_STATE_CHANGED));
        network.getNodeGraph().runActionWhenPossible(
                FluidStorageCache.INVALIDATE_ACTION.apply(
                        com.refinedmods.refinedstorage.api.storage.cache.InvalidateCause.CONNECTED_STATE_CHANGED));

        LevelUtils.updateBlock(level, pos);
    }

    @Override
    public CompoundTag writeConfiguration(CompoundTag tag) {
        // NBT_ID is handled by super.writeConfiguration
        return super.writeConfiguration(tag);
    }

    @Override
    public void readConfiguration(CompoundTag tag) {
        super.readConfiguration(tag);
        if (level != null && level.getBlockEntity(pos) instanceof StorageBridgeBlockEntity be) {
            this.owner = be;
        }
    }
}
