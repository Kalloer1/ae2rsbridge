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
        // 未绑定的单元完全惰性：不注册为存储源，绝不干扰 AE 网络。
        if (RSNetworkStorageCellItem.getBoundRsBlock(is) == null) {
            return null;
        }
        return new RSNetworkCellInventory(is, host);
    }

    // 过滤策略由物品自身携带（RSNetworkStorageCellItem.getFilter()），
    // RSNetworkCellInventory 构造时从中读取，无需此处额外处理。
}
