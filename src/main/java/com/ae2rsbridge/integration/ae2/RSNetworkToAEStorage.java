package com.ae2rsbridge.integration.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.ae2rsbridge.blockentity.StorageBridgeBlockEntity;
import com.ae2rsbridge.bridge.BridgeTransactionGuard;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IStackList;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

public class RSNetworkToAEStorage implements MEStorage {

    private final StorageBridgeBlockEntity bridge;

    public RSNetworkToAEStorage(StorageBridgeBlockEntity bridge) {
        this.bridge = bridge;
    }

    private INetwork getRSNetwork() {
        return bridge.getRSNetwork();
    }

    private static Action toRSAction(Actionable mode) {
        return mode == Actionable.MODULATE ? Action.PERFORM : Action.SIMULATE;
    }

    @Override
    public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0 || key == null) {
            return 0;
        }

        // 访问模式限制：仅读取/无访问时禁止 AE2 向 RS 写入
        if (!bridge.getAE2Access().isAllowInsertion()) {
            return 0;
        }

        // 仅输送不可堆叠物品：开启时，可堆叠物品（石头、原木等）保留在 AE，不写入 RS
        if (bridge.isNonStackableOnly() && AEItemKey.is(key)
                && ((AEItemKey) key).getMaxStackSize() > 1) {
            return 0;
        }

        INetwork network = getRSNetwork();
        if (network == null || !network.canRun()) {
            return 0;
        }

        if (!BridgeTransactionGuard.begin()) {
            return 0;
        }
        try {
            int size = (int) Math.min(amount, Integer.MAX_VALUE);

            if (AEItemKey.is(key)) {
                AEItemKey itemKey = (AEItemKey) key;
                ItemStack prototype = itemKey.toStack(1);
                ItemStack remainder = network.insertItem(prototype, size, toRSAction(mode));
                return (long) size - remainder.getCount();
            }

            if (AEFluidKey.is(key)) {
                AEFluidKey fluidKey = (AEFluidKey) key;
                FluidStack prototype = fluidKey.toStack(1);
                FluidStack remainder = network.insertFluid(prototype, size, toRSAction(mode));
                return (long) size - remainder.getAmount();
            }

            return 0;
        } finally {
            BridgeTransactionGuard.end();
        }
    }

    @Override
    public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0 || key == null) {
            return 0;
        }

        // 访问模式限制：仅写入/无访问时禁止 AE2 从 RS 读取
        if (!bridge.getAE2Access().isAllowExtraction()) {
            return 0;
        }

        INetwork network = getRSNetwork();
        if (network == null || !network.canRun()) {
            return 0;
        }

        if (!BridgeTransactionGuard.begin()) {
            return 0;
        }
        try {
            int size = (int) Math.min(amount, Integer.MAX_VALUE);

            if (AEItemKey.is(key)) {
                AEItemKey itemKey = (AEItemKey) key;
                ItemStack prototype = itemKey.toStack(1);
                ItemStack extracted = network.extractItem(prototype, size, toRSAction(mode));
                return extracted.getCount();
            }

            if (AEFluidKey.is(key)) {
                AEFluidKey fluidKey = (AEFluidKey) key;
                FluidStack prototype = fluidKey.toStack(1);
                FluidStack extracted = network.extractFluid(prototype, size, toRSAction(mode));
                return extracted.getAmount();
            }

            return 0;
        } finally {
            BridgeTransactionGuard.end();
        }
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        // 访问模式限制：无读取权限时不暴露 RS 存储内容给 AE2
        if (!bridge.getAE2Access().isAllowExtraction()) {
            return;
        }

        INetwork network = getRSNetwork();
        if (network == null || !network.canRun()) {
            return;
        }

        if (!BridgeTransactionGuard.begin()) {
            return;
        }
        try {
            var itemCache = network.getItemStorageCache();
            if (itemCache != null) {
                IStackList<ItemStack> itemList = itemCache.getList();
                if (itemList != null) {
                    for (StackListEntry<ItemStack> entry : itemList.getStacks()) {
                        ItemStack stack = entry.getStack();
                        if (stack != null && !stack.isEmpty()) {
                            AEItemKey key = AEItemKey.of(stack);
                            if (key != null) {
                                out.add(key, stack.getCount());
                            }
                        }
                    }
                }
            }

            var fluidCache = network.getFluidStorageCache();
            if (fluidCache != null) {
                IStackList<FluidStack> fluidList = fluidCache.getList();
                if (fluidList != null) {
                    for (StackListEntry<FluidStack> entry : fluidList.getStacks()) {
                        FluidStack stack = entry.getStack();
                        if (stack != null && !stack.isEmpty()) {
                            AEFluidKey key = AEFluidKey.of(stack);
                            if (key != null) {
                                out.add(key, stack.getAmount());
                            }
                        }
                    }
                }
            }
        } finally {
            BridgeTransactionGuard.end();
        }
    }

    @Override
    public Component getDescription() {
        return Component.literal("RS Network Storage");
    }
}