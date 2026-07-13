package com.ae2rsbridge;

import com.ae2rsbridge.block.StorageBridgeBlock;
import com.ae2rsbridge.blockentity.StorageBridgeBlockEntity;
import com.ae2rsbridge.config.BridgeConfig;
import com.ae2rsbridge.init.client.InitScreens;
import com.ae2rsbridge.menu.StorageBridgeMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.flag.FeatureFlags;
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
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.RegistryObject;

@Mod(AE2RSBridge.MODID)
public class AE2RSBridge {

    public static final String MODID = "ae2rsbridge";

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

    public static final RegistryObject<StorageBridgeBlock> STORAGE_BRIDGE_BLOCK =
            BLOCKS.register("storage_bridge", () -> new StorageBridgeBlock(
                    BlockBehaviour.Properties.of()
                            .sound(SoundType.METAL)
                            .strength(2.0f, 11.0f)
            ));

    public static final RegistryObject<Item> STORAGE_BRIDGE_ITEM =
            ITEMS.register("storage_bridge", () -> new BlockItem(
                    STORAGE_BRIDGE_BLOCK.get(),
                    new Item.Properties()
            ));

    public static final RegistryObject<BlockEntityType<StorageBridgeBlockEntity>> STORAGE_BRIDGE_ENTITY =
            BLOCK_ENTITIES.register("storage_bridge", () ->
                    BlockEntityType.Builder.of(
                            StorageBridgeBlockEntity::new,
                            STORAGE_BRIDGE_BLOCK.get()
                    ).build(null));

    public static final RegistryObject<MenuType<StorageBridgeMenu>> STORAGE_BRIDGE_MENU =
            MENU_TYPES.register("storage_bridge", () ->
                    new MenuType<>(StorageBridgeMenu::fromNetwork, FeatureFlags.VANILLA));

    public static final RegistryObject<CreativeModeTab> CREATIVE_TAB =
            CREATIVE_MODE_TABS.register("ae2rsbridge", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ae2rsbridge"))
                    .icon(() -> new ItemStack(STORAGE_BRIDGE_ITEM.get()))
                    .displayItems((params, output) -> output.accept(new ItemStack(STORAGE_BRIDGE_ITEM.get())))
                    .build());

    public AE2RSBridge() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        MENU_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, BridgeConfig.SPEC);

        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(this::clientSetup);
    }

    /**
     * 注册方块实体的 NeoForge 能力。这里把方块实体的能量存储以 FE 能力
     * （{@code Capabilities.EnergyStorage.BLOCK}）对外暴露，供相邻机器接收能量。
     */
    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, STORAGE_BRIDGE_ENTITY.get(),
                (be, side) -> ((StorageBridgeBlockEntity) be).getEnergyStorage());
    }

    private void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(InitScreens::init);
    }
}
