package com.ae2rsbridge.cell;

import appeng.api.storage.cells.ICellHandler;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import com.ae2rsbridge.item.RSNetworkStorageCellItem;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jetbrains.annotations.Nullable;

/**
 * 把 {@link RSNetworkStorageCellItem} 注册到 AE2 的存储单元体系，
 * 使 AE2 在 ME 驱动器中把它当作原生存储单元处理（双向桥接 RS 网络）。
 */
public class RSNetworkCellHandler implements ICellHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RSNetworkCellHandler.class);

    @Override
    public boolean isCell(ItemStack is) {
        return is.getItem() instanceof RSNetworkStorageCellItem;
    }

    @Override
    public StorageCell getCellInventory(ItemStack is, @Nullable ISaveProvider host) {
        // 未绑定的单元完全惰性：不注册为存储源，绝不干扰 AE 网络。
        if (RSNetworkStorageCellItem.getBoundRsBlock(is) == null) {
            LOGGER.info("[rs2ae_cell] getCellInventory: 单元未绑定，返回 null（不接入网格）");
            return null;
        }
        LOGGER.info("[rs2ae_cell] getCellInventory: 已绑定单元，创建 RS 网络存储（host={}）",
                host != null ? host.getClass().getSimpleName() : "null");
        return new RSNetworkCellInventory(is, host);
    }

    // 过滤策略由物品自身携带（RSNetworkStorageCellItem.getFilter()），
    // RSNetworkCellInventory 构造时从中读取，无需此处额外处理。
}
