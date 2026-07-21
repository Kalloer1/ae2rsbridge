package com.ae2rsbridge.init.client;

import com.ae2rsbridge.AE2RSBridge;
import com.ae2rsbridge.client.gui.StyleLoader;
import com.ae2rsbridge.client.screen.StorageBridgeScreen;
import com.ae2rsbridge.menu.StorageBridgeMenu;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.menu.AEBaseMenu;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

public final class InitScreens {

    private InitScreens() {
    }

    public static void init(RegisterMenuScreensEvent event) {
        register(event, AE2RSBridge.STORAGE_BRIDGE_MENU.get(), StorageBridgeScreen::new,
                "/screens/storage_bridge.json");
    }

    /**
     * 注册界面时按需从 ae2rsbridge 命名空间加载并校验 ScreenStyle，
     * 再交给 AEBaseScreen 的四参构造函数。
     */
    public static <M extends AEBaseMenu, U extends AEBaseScreen<M>> void register(RegisterMenuScreensEvent event,
            MenuType<M> type,
            StyledScreenFactory<M, U> factory,
            String stylePath) {
        event.<M, U>register(type, (menu, playerInv, title) -> {
            ScreenStyle style = StyleLoader.load(stylePath);
            return factory.create(menu, playerInv, title, style);
        });
    }

    @FunctionalInterface
    public interface StyledScreenFactory<T extends AbstractContainerMenu, U extends Screen & MenuAccess<T>> {
        U create(T t, Inventory pi, Component title, ScreenStyle style);
    }
}
