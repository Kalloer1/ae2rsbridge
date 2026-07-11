package com.ae2rsbridge;

import com.ae2rsbridge.block.StorageBridgeBlock;
import com.ae2rsbridge.blockentity.StorageBridgeBlockEntity;
import com.ae2rsbridge.config.BridgeConfig;
import com.ae2rsbridge.integration.rs.BridgeNetworkNode;
import com.ae2rsbridge.init.client.InitScreens;
import com.ae2rsbridge.menu.StorageBridgeMenu;
import com.refinedmods.refinedstorage.apiimpl.API;
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
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(AE2RSBridge.MODID)
public class AE2RSBridge {

    public static final String MODID = "ae2rsbridge";

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MODID);

    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, MODID);

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
                    IForgeMenuType.create(StorageBridgeMenu::fromNetwork));

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

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            API.instance().getNetworkNodeRegistry().add(BridgeNetworkNode.ID, (tag, level, pos) -> {
                BridgeNetworkNode node;
                if (level.getBlockEntity(pos) instanceof StorageBridgeBlockEntity be) {
                    node = new BridgeNetworkNode(be, level, pos);
                } else {
                    node = new BridgeNetworkNode(null, level, pos);
                }
                if (tag != null && !tag.isEmpty()) {
                    node.readConfiguration(tag);
                }
                return node;
            });
        });
    }

    private void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(InitScreens::init);
    }
}