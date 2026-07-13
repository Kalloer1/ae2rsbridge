package com.ae2rsbridge.bridge;

/**
 * 防递归线程局部守卫（可重入）。
 * <p>
 * 在双向桥接中，AE2 的插入/提取操作可能触发 RS 的插入/提取，反之亦然，
 * 从而导致无限递归。此守卫使用 ThreadLocal 计数标记当前线程是否正在
 * 处理桥接事务，以打破递归循环。
 * <p>
 * 必须用<b>计数</b>而非布尔，否则嵌套的 {@code begin()/end()} 会破坏外层守卫：
 * 内层 {@code begin()} 不改变状态、但内层 {@code finally} 的 {@code end()} 会把外层
 * 守卫提前清除，导致后续读取在"无守卫"状态下把 RS 镜像回 AE 的物品又数进来，
 * 引发翻倍或物品被错误删减（RS 终端看不到 AE 物品）。
 */
public class BridgeTransactionGuard {

    /** 线程局部重入深度，0 表示当前无桥接事务 */
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    /**
     * 开始一个桥接事务。可重入：嵌套调用只增加深度，不会破坏外层。
     */
    public static void begin() {
        DEPTH.set(DEPTH.get() + 1);
    }

    /**
     * 结束当前线程的桥接事务。只在深度降回 0 时真正解除守卫。
     */
    public static void end() {
        int d = DEPTH.get();
        if (d > 0) {
            DEPTH.set(d - 1);
        }
    }

    /**
     * 检查当前线程是否正在处理桥接事务（深度 > 0）。
     *
     * @return 如果有活跃事务则返回 true
     */
    public static boolean isActive() {
        return DEPTH.get() > 0;
    }
}
