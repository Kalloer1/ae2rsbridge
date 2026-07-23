package com.ae2rsbridge.cell;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

/**
 * 决定 RS 网络存储单元向 AE2 暴露哪些资源。
 * <ul>
 *   <li>{@link #ALL} — 暴露 RS 网络中的全部物品与流体（默认）。</li>
 *   <li>{@link #NON_STACKABLE} — 仅暴露不可堆叠物品（maxStackSize<=1 的
 *       {@link AEItemKey}），用于把这类最占 AE 磁盘类型位的物品放进 RS，从而释放 AE 磁盘的 63 类型配额。</li>
 * </ul>
 */
public enum CellFilter {
    ALL {
        @Override
        public boolean test(AEKey key) {
            return true;
        }
    },
    NON_STACKABLE {
        @Override
        public boolean test(AEKey key) {
            return key instanceof AEItemKey itemKey && itemKey.getMaxStackSize() <= 1;
        }
    };

    public abstract boolean test(AEKey key);
}
