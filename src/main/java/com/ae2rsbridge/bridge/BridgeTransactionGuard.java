package com.ae2rsbridge.bridge;

/**
 * 防递归线程局部守卫。
 * <p>
 * 在双向桥接中，AE2 的插入操作可能触发 RS 的插入操作，反之亦然，
 * 从而导致无限递归。此守卫使用 ThreadLocal 标记当前线程是否正在
 * 处理桥接事务，以打破递归循环。
 */
public class BridgeTransactionGuard {

    /** 线程局部标记，表示当前线程是否正在处理桥接事务 */
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    /**
     * 尝试开始一个桥接事务。
     *
     * @return 如果成功开始则返回 true；如果当前线程已有活跃事务则返回 false
     */
    public static boolean begin() {
        if (ACTIVE.get()) {
            return false;
        }
        ACTIVE.set(true);
        return true;
    }

    /**
     * 结束当前线程的桥接事务。
     */
    public static void end() {
        ACTIVE.remove();
    }

    /**
     * 检查当前线程是否正在处理桥接事务。
     *
     * @return 如果有活跃事务则返回 true
     */
    public static boolean isActive() {
        return ACTIVE.get();
    }
}
