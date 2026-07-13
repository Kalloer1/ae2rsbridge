package com.ae2rsbridge.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 桥接模组的配置类，使用 Forge ConfigSpec 定义各项可配置参数。
 * 包括能量转换比例、缓冲容量、传输限制、存储优先级和同步间隔。
 */
public class BridgeConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue ENERGY_CONVERSION_RATIO;
    public static final ModConfigSpec.DoubleValue ENERGY_BUFFER_CAPACITY;
    public static final ModConfigSpec.DoubleValue MAX_TRANSFER_PER_TICK;
    public static final ModConfigSpec.IntValue BRIDGE_PRIORITY;
    public static final ModConfigSpec.IntValue SYNC_INTERVAL;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment("AE2-RS Bridge 配置").push("bridge");

        // 1 AE = 2 FE 的转换比例
        ENERGY_CONVERSION_RATIO = BUILDER
                .comment("1 AE 能量等于多少 FE 能量（默认 2.0）")
                .defineInRange("energyConversionRatio", 2.0, 0.0, Double.MAX_VALUE);

        // AE 能量缓冲池容量
        ENERGY_BUFFER_CAPACITY = BUILDER
                .comment("桥接方块内部 AE 能量缓冲池容量（默认 10000.0）")
                .defineInRange("energyBufferCapacity", 10000.0, 1.0, Double.MAX_VALUE);

        // 每 tick 最大传输量
        MAX_TRANSFER_PER_TICK = BUILDER
                .comment("每 tick 最大 AE 能量传输量（默认 100.0）")
                .defineInRange("maxTransferPerTick", 100.0, 1.0, Double.MAX_VALUE);

        // 存储优先级
        BRIDGE_PRIORITY = BUILDER
                .comment("桥接存储的优先级（默认 0）")
                .defineInRange("bridgePriority", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);

        // 同步间隔
        SYNC_INTERVAL = BUILDER
                .comment("AE2 与 RS 之间的同步间隔（ticks，默认 10）")
                .defineInRange("syncInterval", 10, 1, Integer.MAX_VALUE);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    /** 获取能量转换比例（1 AE = ? FE） */
    public static double getEnergyConversionRatio() {
        return ENERGY_CONVERSION_RATIO.get();
    }

    /** 获取 AE 能量缓冲池容量 */
    public static double getEnergyBufferCapacity() {
        return ENERGY_BUFFER_CAPACITY.get();
    }

    /** 获取每 tick 最大 AE 能量传输量 */
    public static double getMaxTransferPerTick() {
        return MAX_TRANSFER_PER_TICK.get();
    }

    /** 获取桥接存储优先级 */
    public static int getBridgePriority() {
        return BRIDGE_PRIORITY.get();
    }

    /** 获取同步间隔（ticks） */
    public static int getSyncInterval() {
        return SYNC_INTERVAL.get();
    }
}
