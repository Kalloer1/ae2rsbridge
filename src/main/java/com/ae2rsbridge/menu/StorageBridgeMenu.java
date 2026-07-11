package com.ae2rsbridge.menu;

import appeng.api.config.AccessRestriction;
import appeng.menu.AEBaseMenu;
import appeng.menu.MenuOpener;
import appeng.menu.implementations.PriorityMenu;
import appeng.menu.locator.MenuLocators;
import com.ae2rsbridge.AE2RSBridge;
import com.ae2rsbridge.blockentity.StorageBridgeBlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;

public class StorageBridgeMenu extends AEBaseMenu {

    private final StorageBridgeBlockEntity bridgeBlockEntity;

    // 服务端 -> 客户端 状态同步槽位
    private final DataSlot ae2PrioritySlot = DataSlot.standalone();
    private final DataSlot rsPrioritySlot = DataSlot.standalone();
    private final DataSlot ae2AccessSlot = DataSlot.standalone();
    private final DataSlot activeOutputSlot = DataSlot.standalone();
    private final DataSlot ae2ConnectedSlot = DataSlot.standalone();
    private final DataSlot rsConnectedSlot = DataSlot.standalone();
    private final DataSlot feEnergySlot = DataSlot.standalone();
    private final DataSlot nonStackableOnlySlot = DataSlot.standalone();

    // 客户端动作名称
    private static final String ACTION_TOGGLE_OUTPUT = "toggleActiveOutput";
    private static final String ACTION_TOGGLE_STACKABLE_FILTER = "toggleStackableFilter";
    private static final String ACTION_OPEN_AE2_PRIORITY = "openAE2Priority";
    private static final String ACTION_OPEN_RS_PRIORITY = "openRSPriority";

    public StorageBridgeMenu(int id, Inventory ip, StorageBridgeBlockEntity be) {
        super(AE2RSBridge.STORAGE_BRIDGE_MENU.get(), id, ip, be);
        this.bridgeBlockEntity = be;

        this.createPlayerInventorySlots(ip);

        // 注册所有同步槽位
        this.addDataSlot(ae2PrioritySlot);
        this.addDataSlot(rsPrioritySlot);
        this.addDataSlot(ae2AccessSlot);
        this.addDataSlot(activeOutputSlot);
        this.addDataSlot(ae2ConnectedSlot);
        this.addDataSlot(rsConnectedSlot);
        this.addDataSlot(feEnergySlot);
        this.addDataSlot(nonStackableOnlySlot);

        // 从 BE 初始化（服务端）
        ae2PrioritySlot.set(be.getAE2Priority());
        rsPrioritySlot.set(be.getRSPriority());
        ae2AccessSlot.set(be.getAE2Access().ordinal());
        activeOutputSlot.set(be.isActiveOutput() ? 1 : 0);
        ae2ConnectedSlot.set(be.isAE2Connected() ? 1 : 0);
        rsConnectedSlot.set(be.isRSConnected() ? 1 : 0);
        feEnergySlot.set(be.getFEEnergy());
        nonStackableOnlySlot.set(be.isNonStackableOnly() ? 1 : 0);

        // 访问模式（Settings.ACCESS）由 ServerSettingToggleButton 通过 ConfigButtonPacket
        // 自动处理，这里无需再注册对应的客户端动作。
        registerClientAction(ACTION_TOGGLE_OUTPUT, this::toggleActiveOutput);
        registerClientAction(ACTION_TOGGLE_STACKABLE_FILTER, this::toggleStackableFilter);
        registerClientAction(ACTION_OPEN_AE2_PRIORITY,
                () -> openPriorityGui(StorageBridgeBlockEntity.PriorityTarget.AE2));
        registerClientAction(ACTION_OPEN_RS_PRIORITY,
                () -> openPriorityGui(StorageBridgeBlockEntity.PriorityTarget.RS));
    }

    @Override
    public void broadcastChanges() {
        // 广播前先从 BE 刷新同步槽位
        ae2PrioritySlot.set(bridgeBlockEntity.getAE2Priority());
        rsPrioritySlot.set(bridgeBlockEntity.getRSPriority());
        ae2AccessSlot.set(bridgeBlockEntity.getAE2Access().ordinal());
        activeOutputSlot.set(bridgeBlockEntity.isActiveOutput() ? 1 : 0);
        ae2ConnectedSlot.set(bridgeBlockEntity.isAE2Connected() ? 1 : 0);
        rsConnectedSlot.set(bridgeBlockEntity.isRSConnected() ? 1 : 0);
        feEnergySlot.set(bridgeBlockEntity.getFEEnergy());
        nonStackableOnlySlot.set(bridgeBlockEntity.isNonStackableOnly() ? 1 : 0);

        super.broadcastChanges();
    }

    // ===== 客户端调用入口（由界面按钮触发） =====

    /** 客户端：切换主动能量输出 */
    public void requestToggleOutput() {
        sendClientAction(ACTION_TOGGLE_OUTPUT);
    }

    /** 客户端：切换“仅输送不可堆叠物品”过滤开关 */
    public void requestToggleStackableFilter() {
        sendClientAction(ACTION_TOGGLE_STACKABLE_FILTER);
    }

    /** 客户端：打开 AE2 侧优先级子界面 */
    public void requestOpenAE2Priority() {
        sendClientAction(ACTION_OPEN_AE2_PRIORITY);
    }

    /** 客户端：打开 RS 侧优先级子界面 */
    public void requestOpenRSPriority() {
        sendClientAction(ACTION_OPEN_RS_PRIORITY);
    }

    // ===== 服务端处理 =====

    /** 打开 AE2 标准优先级设置界面（子菜单） */
    private void openPriorityGui(StorageBridgeBlockEntity.PriorityTarget target) {
        if (!isServerSide() || !(getPlayer() instanceof ServerPlayer serverPlayer)) {
            return;
        }
        bridgeBlockEntity.setPriorityTarget(target);
        MenuOpener.open(PriorityMenu.TYPE, serverPlayer, MenuLocators.forBlockEntity(bridgeBlockEntity));
    }

    public void toggleActiveOutput() {
        if (isServerSide()) {
            bridgeBlockEntity.setActiveOutput(!bridgeBlockEntity.isActiveOutput());
            bridgeBlockEntity.saveChanges();
        }
    }

    public void toggleStackableFilter() {
        if (isServerSide()) {
            bridgeBlockEntity.setNonStackableOnly(!bridgeBlockEntity.isNonStackableOnly());
            bridgeBlockEntity.saveChanges();
        }
    }

    public static StorageBridgeMenu fromNetwork(int windowId, Inventory inv, FriendlyByteBuf buf) {
        var pos = buf.readBlockPos();
        var be = inv.player.level().getBlockEntity(pos);
        if (!(be instanceof StorageBridgeBlockEntity bridgeBE)) {
            throw new IllegalStateException("Could not find StorageBridgeBlockEntity at " + pos);
        }
        return new StorageBridgeMenu(windowId, inv, bridgeBE);
    }

    public StorageBridgeBlockEntity getBridgeBlockEntity() {
        return bridgeBlockEntity;
    }

    // ===== 供界面读取的同步值 =====
    public int getAE2Priority() { return ae2PrioritySlot.get(); }
    public int getRSPriority() { return rsPrioritySlot.get(); }
    public AccessRestriction getAE2Access() { return AccessRestriction.values()[ae2AccessSlot.get()]; }
    public boolean isActiveOutput() { return activeOutputSlot.get() != 0; }
    public boolean isAE2Connected() { return ae2ConnectedSlot.get() != 0; }
    public boolean isRSConnected() { return rsConnectedSlot.get() != 0; }
    public int getFEEnergy() { return feEnergySlot.get(); }
    public boolean isNonStackableOnly() { return nonStackableOnlySlot.get() != 0; }

    @Override
    public ItemStack quickMoveStack(Player player, int idx) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return bridgeBlockEntity != null && !bridgeBlockEntity.isRemoved();
    }
}
