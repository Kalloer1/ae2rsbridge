package com.ae2rsbridge.cell;

import appeng.api.storage.cells.ICellHandler;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import com.ae2rsbridge.item.RSNetworkStorageCellItem;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 把 {@link RSNetworkStorageCellItem} 注册到 AE2 的存储单元体系，
 * 使 AE2 在 ME 驱动器中把它当作原生存储单元处理。
 */
public class RSNetworkCellHandler implements ICellHandler {

    @Override
    public boolean isCell(ItemStack is) {
        return is.getItem() instanceof RSNetworkStorageCellItem;
    }

    @Override
    public StorageCell getCellInventory(ItemStack is, @Nullable ISaveProvider host) {
        return new RSNetworkCellInventory(is, host);
    }
}
