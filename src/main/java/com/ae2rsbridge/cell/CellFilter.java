package com.ae2rsbridge.cell;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

/**
 * 决定 RS 网络存储单元的<b>读取</b>与<b>写入</b>范围。
 * <p>
 * 关键语义（用户需求）：不可堆叠磁盘<b>照样能读取 RS 网络里的所有物品</b>，
 * 只是<b>存入</b>时只接受不可堆叠物品。因此读取范围 ({@link #allowsRead}) 两种类型都返回 true，
 * 只有<b>写入范围</b> ({@link #allowsInsert}) 会因类型不同而异 —— 绝不能把写入限制也套到读取上。
 * <ul>
 *   <li>{@link #ALL} — 读取全部、写入全部（默认全类型磁盘）。</li>
 *   <li>{@link #NON_STACKABLE} — 读取全部，但<b>写入</b>仅接受不可堆叠物品
 *       （{@code maxStackSize<=1} 的 {@link AEItemKey}；流体与其它类型一律拒收）。
 *       用于把工具/盔甲这类最占 AE 磁盘类型位的物品单独倒进 RS。</li>
 * </ul>
 */
public enum CellFilter {
    ALL {
        @Override
        public boolean allowsRead(AEKey key) {
            return true;
        }

        @Override
        public boolean allowsInsert(AEKey key) {
            return true;
        }
    },
    NON_STACKABLE {
        @Override
        public boolean allowsRead(AEKey key) {
            // 读取不受限：照样把 RS 里的全部物品暴露给 AE2。
            return true;
        }

        @Override
        public boolean allowsInsert(AEKey key) {
            // 仅允许不可堆叠物品写入（流体等其它类型返回 false）。
            return key instanceof AEItemKey itemKey && itemKey.getMaxStackSize() <= 1;
        }
    };

    /** 该单元是否把此资源暴露给 AE2 读取（浏览 / 提取）。两种类型都返回 true。 */
    public abstract boolean allowsRead(AEKey key);

    /** 该单元是否允许把此资源<b>写入</b> RS 网络。{@link #NON_STACKABLE} 仅允许不可堆叠物品。 */
    public abstract boolean allowsInsert(AEKey key);
}
