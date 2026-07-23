package com.ae2rsbridge;

import com.ae2rsbridge.block.StorageBridgeBlock;
import com.ae2rsbridge.blockentity.StorageBridgeBlockEntity;
import com.ae2rsbridge.config.BridgeConfig;
import com.ae2rsbridge.init.client.InitScreens;
import com.ae2rsbridge.menu.StorageBridgeMenu;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(AE2RSBridge.MODID)
public class AE2RSBridge {

    public static final String MODID = "ae2rsbridge";

    private static final Logger LOGGER = LoggerFactory.getLogger(AE2RSBridge.class);

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, MODID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, MODID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, MODID);

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredHolder<Block, StorageBridgeBlock> STORAGE_BRIDGE_BLOCK =
            BLOCKS.register("storage_bridge", () -> new StorageBridgeBlock(
                    BlockBehaviour.Properties.of()
                            .sound(SoundType.METAL)
                            .strength(2.0f, 11.0f)
            ));

    public static final DeferredHolder<Item, Item> STORAGE_BRIDGE_ITEM =
            ITEMS.register("storage_bridge", () -> new BlockItem(
                    STORAGE_BRIDGE_BLOCK.get(),
                    new Item.Properties()
            ));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StorageBridgeBlockEntity>> STORAGE_BRIDGE_ENTITY =
            BLOCK_ENTITIES.register("storage_bridge", () ->
                    BlockEntityType.Builder.of(
                            StorageBridgeBlockEntity::new,
                            STORAGE_BRIDGE_BLOCK.get()
                    ).build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<StorageBridgeMenu>> STORAGE_BRIDGE_MENU =
            MENU_TYPES.register("storage_bridge", () -> IMenuTypeExtension.create(StorageBridgeMenu::fromNetwork));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB =
            CREATIVE_MODE_TABS.register("ae2rsbridge", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ae2rsbridge"))
                    .icon(() -> new ItemStack(STORAGE_BRIDGE_ITEM.get()))
                    .displayItems((params, output) -> output.accept(new ItemStack(STORAGE_BRIDGE_ITEM.get())))
                    .build());

    public AE2RSBridge(IEventBus modEventBus, ModContainer container) {
        LOGGER.info("[ae2rsbridge][init] AE2RSBridge mod constructing (RS2 + AE2 integration).");
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        MENU_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        container.registerConfig(ModConfig.Type.COMMON, BridgeConfig.SPEC);

        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(InitScreens::init); // RegisterMenuScreensEvent
    }

    /**
     * 注册方块实体的 NeoForge 能力。
     * <ul>
     *     <li>FE 能量：桥抽 AE2 能量再以 FE 推给相邻机器</li>
     *     <li>RS2 网络节点容器：把桥的 RS 节点接入 RS 网络（必须！否则线缆接不上桥）</li>
     * </ul>
     */
    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, STORAGE_BRIDGE_ENTITY.get(),
                (be, side) -> ((StorageBridgeBlockEntity) be).getEnergyStorage());

        // === 关键：让 RS 线缆能识别并连接桥的 RS 节点 ===
        // RS2 自己所有的方块(cable, controller, importer...)都在
        // com.refinedmods.refinedstorage.neoforge.ModInitializer#registerCapabilities 里
        // 用 RefinedStorageNeoForgeApi#getNetworkNodeContainerProviderCapability 注册。
        // 没有这一行,桥的 RS 节点永远不会被 RS 网络发现,RS 侧显示永远是"未连接"。
        event.registerBlockEntity(
                RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
                STORAGE_BRIDGE_ENTITY.get(),
                (be, side) -> ((StorageBridgeBlockEntity) be).getContainerProvider()
        );
    }
}
