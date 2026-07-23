package com.ae2rsbridge;

import appeng.api.storage.StorageCells;
import com.ae2rsbridge.cell.CellFilter;
import com.ae2rsbridge.cell.RSNetworkCellHandler;
import com.ae2rsbridge.cell.RSNetworkCellInventory;
import com.ae2rsbridge.item.RSNetworkStorageCellItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RS Network Cell —— 把 Refined Storage 2 网络以 AE2 原生存储单元形式暴露给 AE2 网格。
 * <b>单向桥接</b>：只有 AE 网络能读取并在 RS 网络中存入物品；RS 网络无法反向访问 AE 网络。
 * 玩家潜行右键 RS 线缆/控制器绑定，放入 ME 驱动器即可让 AE2 终端浏览并修改该 RS 网络。
 */
@Mod(AE2RSBridge.MODID)
public class AE2RSBridge {

    public static final String MODID = "rs2ae_cell";

    private static final Logger LOGGER = LoggerFactory.getLogger(AE2RSBridge.class);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, MODID);

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredHolder<Item, RSNetworkStorageCellItem> RS_NETWORK_CELL_ITEM =
            ITEMS.register("rs_network_cell",
                    () -> new RSNetworkStorageCellItem(new Item.Properties(), CellFilter.ALL));

    public static final DeferredHolder<Item, RSNetworkStorageCellItem> RS_NETWORK_CELL_NONSTACKABLE_ITEM =
            ITEMS.register("rs_network_cell_nonstackable",
                    () -> new RSNetworkStorageCellItem(new Item.Properties(), CellFilter.NON_STACKABLE));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB =
            CREATIVE_MODE_TABS.register(MODID, () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.rs2ae_cell"))
                    .icon(() -> new ItemStack(RS_NETWORK_CELL_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(new ItemStack(RS_NETWORK_CELL_ITEM.get()));
                        output.accept(new ItemStack(RS_NETWORK_CELL_NONSTACKABLE_ITEM.get()));
                    })
                    .build());

    public AE2RSBridge(IEventBus modEventBus, ModContainer container) {
        LOGGER.info("[rs2ae_cell] RS Network Cell mod constructing (RS2 -> AE2 native cell).");
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
        // 服务端每刻轮询待解析的 RS 网络单元，使挂载时 RS 未就绪的单元能在 RS 上线后自愈。
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, this::onServerTick);
    }

    private void onServerTick(ServerTickEvent.Post event) {
        RSNetworkCellInventory.tickPending(event.getServer());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        StorageCells.addCellHandler(new RSNetworkCellHandler());
        LOGGER.info("[rs2ae_cell] registered RSNetworkCellHandler with AE2 StorageCells.");
    }
}
