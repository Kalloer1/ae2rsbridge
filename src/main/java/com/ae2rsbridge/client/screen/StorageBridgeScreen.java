package com.ae2rsbridge.client.screen;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Settings;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.ServerSettingToggleButton;
import appeng.client.gui.widgets.TabButton;
import appeng.client.gui.widgets.ToggleButton;
import com.ae2rsbridge.menu.StorageBridgeMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 存储桥接方块的主界面，采用 AE2 的 {@link AEBaseScreen} 框架：
 * <ul>
 *     <li>左侧竖直工具栏放置访问模式开关与主动输出开关（AE2 内置图标）。</li>
 *     <li>右上角两个扳手标签页按钮分别打开 AE2 / RS 侧的标准优先级子界面。</li>
 *     <li>面板中央用文字展示 AE2 / RS 网络连接状态、能量与两侧优先级。</li>
 * </ul>
 * 背景使用 {@code generatedBackground}（AE2 面板边框），无需任何自定义美术贴图。
 */
public class StorageBridgeScreen extends AEBaseScreen<StorageBridgeMenu> {

    private final ServerSettingToggleButton<AccessRestriction> accessButton;
    private final ToggleButton outputButton;
    private final ToggleButton stackableFilterButton;

    public StorageBridgeScreen(StorageBridgeMenu menu, Inventory playerInventory,
                               Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);

        // 面板高度已放大，物品栏标题不会与状态文字重叠，保留显示便于玩家识别背包区域
        // 如需隐藏可取消下一行注释
        // setTextHidden("player_inventory_title", true);

        // ── 访问模式开关（复用 AE2 内置 Settings.ACCESS 三态图标） ──
        // 点击后由 SettingToggleButton 自动发送 ConfigButtonPacket，
        // 服务端在本方块的 IConfigManager 上循环切换。
        this.accessButton = new ServerSettingToggleButton<>(Settings.ACCESS, menu.getAE2Access());
        addToLeftToolbar(this.accessButton);

        // ── 主动能量输出开关 ──
        this.outputButton = new ToggleButton(
                Icon.AUTO_EXPORT_ON, Icon.AUTO_EXPORT_OFF,
                Component.translatable("gui.ae2rsbridge.toggle_output"),
                Component.translatable("gui.ae2rsbridge.toggle_output.hint"),
                state -> menu.requestToggleOutput());
        this.outputButton.setState(menu.isActiveOutput());
        addToLeftToolbar(this.outputButton);

        // ── 仅输送不可堆叠物品开关 ──
        // 开启后 AE2 向 RS 写入时只放行不可堆叠物品（装备/工具等），
        // 可堆叠物品（石头/原木等）保留在 AE 网络内。
        this.stackableFilterButton = new ToggleButton(
                Icon.VALID, Icon.INVALID,
                Component.translatable("gui.ae2rsbridge.stackable_filter"),
                Component.translatable("gui.ae2rsbridge.stackable_filter.hint"),
                state -> menu.requestToggleStackableFilter());
        this.stackableFilterButton.setState(menu.isNonStackableOnly());
        addToLeftToolbar(this.stackableFilterButton);

        // ── 两个优先级子界面入口（右上角扳手标签页） ──
        widgets.add("openAE2Priority", new TabButton(
                Icon.WRENCH,
                Component.translatable("gui.ae2rsbridge.ae2_priority_button"),
                btn -> menu.requestOpenAE2Priority()));
        widgets.add("openRSPriority", new TabButton(
                Icon.WRENCH,
                Component.translatable("gui.ae2rsbridge.rs_priority_button"),
                btn -> menu.requestOpenRSPriority()));
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        // 每帧把服务端同步过来的最新状态回填到按钮上
        this.accessButton.set(getMenu().getAE2Access());
        this.outputButton.setState(getMenu().isActiveOutput());
        this.stackableFilterButton.setState(getMenu().isNonStackableOnly());
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        // 注意：此处坐标为界面局部坐标（矩阵已平移到界面左上角）
        StorageBridgeMenu m = getMenu();

        int left = 8;
        int y = 22;

        // AE2 网络状态
        drawStatusLine(guiGraphics, left, y,
                Component.translatable("gui.ae2rsbridge.ae2_network"),
                m.isAE2Connected());
        y += 12;

        // RS 网络状态
        drawStatusLine(guiGraphics, left, y,
                Component.translatable("gui.ae2rsbridge.rs_network"),
                m.isRSConnected());
        y += 16;

        // 能量
        guiGraphics.drawString(this.font,
                Component.translatable("gui.ae2rsbridge.energy_stored", m.getFEEnergy()),
                left, y, 0x404040, false);
        y += 16;

        // 两侧优先级
        guiGraphics.drawString(this.font,
                Component.translatable("gui.ae2rsbridge.ae2_priority", m.getAE2Priority()),
                left, y, 0x404040, false);
        y += 12;
        guiGraphics.drawString(this.font,
                Component.translatable("gui.ae2rsbridge.rs_priority", m.getRSPriority()),
                left, y, 0x404040, false);
    }

    private void drawStatusLine(GuiGraphics g, int x, int y, Component label, boolean connected) {
        // 标签用默认灰色
        int labelWidth = this.font.width(label) + 4;
        g.drawString(this.font, label, x, y, 0x404040, false);
        // 状态词用绿色(已连接)/红色(未连接)
        Component status = connected
                ? Component.translatable("gui.ae2rsbridge.connected")
                : Component.translatable("gui.ae2rsbridge.disconnected");
        g.drawString(this.font, status, x + labelWidth, y,
                connected ? 0x1FA71F : 0xD03030, false);
    }
}
