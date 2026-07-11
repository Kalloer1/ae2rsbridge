package com.ae2rsbridge.bridge;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Setting;
import appeng.api.config.Settings;
import appeng.api.util.IConfigManager;
import appeng.api.util.UnsupportedSettingException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 桥接方块配置管理器
 * 实现 AE2 的 IConfigManager 接口以支持枚举类型设置，
 * 同时额外支持 Integer 类型的优先级设置
 */
public class BridgeConfigManager implements IConfigManager {

    private final Map<Setting<?>, Enum<?>> enumSettings = new IdentityHashMap<>();
    private final Map<String, Integer> intSettings = new HashMap<>();
    private boolean nonStackableOnly = BridgeConfigSettings.NON_STACKABLE_ONLY_DEFAULT;
    private final Runnable changeListener;

    public BridgeConfigManager(Runnable changeListener) {
        this.changeListener = changeListener;
        registerAllSettings();
    }

    public BridgeConfigManager() {
        this(null);
    }

    /**
     * 注册所有配置项
     */
    private void registerAllSettings() {
        // 直接复用 AE2 内置的 ACCESS 设置，才能获得内置的读/写/读写三态图标，
        // 并让 ServerSettingToggleButton 发出的 ConfigButtonPacket 能命中本配置管理器。
        registerSetting(Settings.ACCESS, AccessRestriction.READ_WRITE);

        registerIntSetting(BridgeConfigSettings.AE2_PRIORITY_KEY, BridgeConfigSettings.AE2_PRIORITY_DEFAULT);
        registerIntSetting(BridgeConfigSettings.RS_PRIORITY_KEY, BridgeConfigSettings.RS_PRIORITY_DEFAULT);
    }

    // ===== IConfigManager 接口实现（枚举设置） =====

    @Override
    public Set<Setting<?>> getSettings() {
        return enumSettings.keySet();
    }

    @Override
    public <T extends Enum<T>> void registerSetting(Setting<T> setting, T defaultValue) {
        enumSettings.put(setting, defaultValue);
    }

    @Override
    public <T extends Enum<T>> T getSetting(Setting<T> setting) {
        Enum<?> value = enumSettings.get(setting);
        if (value == null) {
            throw new UnsupportedSettingException("Setting " + setting.getName() + " is not supported.");
        }
        return setting.getEnumClass().cast(value);
    }

    @Override
    public <T extends Enum<T>> void putSetting(Setting<T> setting, T newValue) {
        if (!enumSettings.containsKey(setting)) {
            throw new UnsupportedSettingException("Setting " + setting.getName() + " is not supported.");
        }
        enumSettings.put(setting, newValue);
        onChanged();
    }

    // ===== Integer 类型设置 =====

    /**
     * 注册 Integer 类型设置
     *
     * @param key          设置键
     * @param defaultValue 默认值
     */
    public void registerIntSetting(String key, int defaultValue) {
        intSettings.put(key, defaultValue);
    }

    /**
     * 获取 Integer 类型设置值
     *
     * @param key 设置键
     * @return 设置值
     */
    public int getIntSetting(String key) {
        Integer value = intSettings.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Setting " + key + " is not supported.");
        }
        return value;
    }

    /**
     * 设置 Integer 类型设置值
     *
     * @param key   设置键
     * @param value 新值
     */
    public void putIntSetting(String key, int value) {
        if (!intSettings.containsKey(key)) {
            throw new IllegalArgumentException("Setting " + key + " is not supported.");
        }
        intSettings.put(key, value);
        onChanged();
    }

    // ===== NBT 读写 =====

    @Override
    public void writeToNBT(CompoundTag destination) {
        for (Map.Entry<Setting<?>, Enum<?>> entry : enumSettings.entrySet()) {
            destination.putString(entry.getKey().getName(), entry.getValue().name());
        }
        for (Map.Entry<String, Integer> entry : intSettings.entrySet()) {
            destination.putInt(entry.getKey(), entry.getValue());
        }
        destination.putBoolean(BridgeConfigSettings.NON_STACKABLE_ONLY_KEY, nonStackableOnly);
    }

    @Override
    public boolean readFromNBT(CompoundTag src) {
        boolean anythingRead = false;

        for (Setting<?> setting : enumSettings.keySet()) {
            if (src.contains(setting.getName(), Tag.TAG_STRING)) {
                String value = src.getString(setting.getName());
                try {
                    setting.setFromString(this, value);
                    anythingRead = true;
                } catch (IllegalArgumentException e) {
                }
            }
        }

        // 兼容迁移：旧版本把访问模式存在自定义键 "ae2_access" 下，
        // 现在改用 AE2 内置 Settings.ACCESS（键名 "access"）。
        // 若旧键存在且新键缺失，则把旧值迁移过来。
        if (!src.contains(Settings.ACCESS.getName(), Tag.TAG_STRING)
                && src.contains(BridgeConfigSettings.LEGACY_AE2_ACCESS_KEY, Tag.TAG_STRING)) {
            try {
                Settings.ACCESS.setFromString(this, src.getString(BridgeConfigSettings.LEGACY_AE2_ACCESS_KEY));
                anythingRead = true;
            } catch (IllegalArgumentException ignored) {
            }
        }

        for (String key : intSettings.keySet()) {
            if (src.contains(key, Tag.TAG_INT)) {
                intSettings.put(key, src.getInt(key));
                anythingRead = true;
            }
        }

        if (src.contains(BridgeConfigSettings.NON_STACKABLE_ONLY_KEY, Tag.TAG_BYTE)) {
            this.nonStackableOnly = src.getBoolean(BridgeConfigSettings.NON_STACKABLE_ONLY_KEY);
            anythingRead = true;
        }

        return anythingRead;
    }

    // ===== 工具方法 =====

    /**
     * 通知配置变更
     */
    private void onChanged() {
        if (changeListener != null) {
            changeListener.run();
        }
    }

    /**
     * 获取 AE2 侧优先级
     */
    public int getAE2Priority() {
        return getIntSetting(BridgeConfigSettings.AE2_PRIORITY_KEY);
    }

    /**
     * 设置 AE2 侧优先级
     */
    public void setAE2Priority(int priority) {
        putIntSetting(BridgeConfigSettings.AE2_PRIORITY_KEY, priority);
    }

    /**
     * 获取 RS 侧优先级
     */
    public int getRSPriority() {
        return getIntSetting(BridgeConfigSettings.RS_PRIORITY_KEY);
    }

    /**
     * 设置 RS 侧优先级
     */
    public void setRSPriority(int priority) {
        putIntSetting(BridgeConfigSettings.RS_PRIORITY_KEY, priority);
    }

    /**
     * 获取“仅输送不可堆叠物品”开关状态
     */
    public boolean isNonStackableOnly() {
        return nonStackableOnly;
    }

    /**
     * 设置“仅输送不可堆叠物品”开关状态
     */
    public void setNonStackableOnly(boolean value) {
        if (this.nonStackableOnly != value) {
            this.nonStackableOnly = value;
            onChanged();
        }
    }
}
