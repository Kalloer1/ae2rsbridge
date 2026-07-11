package com.ae2rsbridge.bridge;

/**
 * 桥接方块的整数类型配置键。
 *
 * <p>访问模式（AE2 侧存储访问权限）不再使用自定义 Setting，而是直接复用 AE2 内置的
 * {@code appeng.api.config.Settings.ACCESS}，这样才能自动获得 AE2 内置的
 * 读 / 写 / 读写三态图标，并与 {@code ServerSettingToggleButton} 无缝对接。</p>
 */
public final class BridgeConfigSettings {

    private BridgeConfigSettings() {
    }

    public static final String AE2_PRIORITY_KEY = "ae2_priority";
    public static final int AE2_PRIORITY_DEFAULT = 0;

    public static final String RS_PRIORITY_KEY = "rs_priority";
    public static final int RS_PRIORITY_DEFAULT = 0;

    /**
     * 仅输送不可堆叠物品开关。开启后，AE2 向 RS 写入时只放行不可堆叠物品
     * （装备、工具等），可堆叠物品（石头、原木等）保留在 AE 网络内。
     */
    public static final String NON_STACKABLE_ONLY_KEY = "non_stackable_only";
    public static final boolean NON_STACKABLE_ONLY_DEFAULT = false;

    /** 旧版本使用的自定义访问模式 NBT 键，用于一次性兼容迁移。 */
    public static final String LEGACY_AE2_ACCESS_KEY = "ae2_access";
}
