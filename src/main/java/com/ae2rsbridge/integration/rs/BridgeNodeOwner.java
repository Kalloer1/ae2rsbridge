package com.ae2rsbridge.integration.rs;

import net.minecraft.world.item.ItemStack;

/**
 * 桥接方块实体实现此接口，供 BridgeNetworkNode 访问存储包装器和显示信息。
 */
public interface BridgeNodeOwner {
    /** 获取 AE2→RS 物品存储包装器 */
    AENetworkToRSItemStorage getAeToRsItemStorage();

    /** 获取 AE2→RS 流体存储包装器 */
    AENetworkToRSFluidStorage getAeToRsFluidStorage();

    /** 获取用于 RS 控制器 GUI 显示的物品栈 */
    ItemStack getDisplayStack();
}
