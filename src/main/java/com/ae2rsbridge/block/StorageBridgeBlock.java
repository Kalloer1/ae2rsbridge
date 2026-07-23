package com.ae2rsbridge.block;

import com.ae2rsbridge.AE2RSBridge;
import com.ae2rsbridge.blockentity.StorageBridgeBlockEntity;
import com.ae2rsbridge.menu.StorageBridgeMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import com.mojang.serialization.MapCodec;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StorageBridgeBlock extends BaseEntityBlock {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageBridgeBlock.class);

    public StorageBridgeBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<StorageBridgeBlock> codec() {
        return simpleCodec(StorageBridgeBlock::new);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return AE2RSBridge.STORAGE_BRIDGE_ENTITY.get().create(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != AE2RSBridge.STORAGE_BRIDGE_ENTITY.get()) {
            return null;
        }
        return (l, p, s, be) -> ((StorageBridgeBlockEntity) be).serverTick();
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof StorageBridgeBlockEntity bridgeBE) {
                LOGGER.info("[ae2rsbridge][diag] useWithoutItem (right-click) on bridge at " + pos
                        + " ae2Ready=" + bridgeBE.isAE2Connected() + " rsActive=" + bridgeBE.isRSConnected());
                openMainMenu(serverPlayer, bridgeBE);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** 静态方法，供子菜单返回时重新打开主菜单 */
    public static void openMainMenu(ServerPlayer player, StorageBridgeBlockEntity bridgeBE) {
        player.openMenu(new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.translatable("block.ae2rsbridge.storage_bridge");
            }

            @Nullable
            @Override
            public AbstractContainerMenu createMenu(int windowId, Inventory inv, Player player) {
                return new StorageBridgeMenu(windowId, inv, bridgeBE);
            }
        }, buf -> buf.writeBlockPos(bridgeBE.getBlockPos()));
    }
}