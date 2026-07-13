package com.ae2rsbridge.integration.rs;

import com.refinedmods.refinedstorage.api.network.impl.node.externalstorage.ExternalStorageNetworkNode;

/**
 * RS2 网络节点：桥接方块在 RS 网络侧的承载节点。
 * <p>
 * 直接继承 RS2 的 {@link ExternalStorageNetworkNode}（它已实现 {@code StorageProvider}），
 * 通过 {@link #initialize(ExternalStorageProvider)} 把由 AE2 支撑的
 * {@link AE2NetworkToRSStorage} 注入为外部存储。RS2 会：
 * <ul>
 *     <li>自动管理节点生命周期（容器注册/注销、区块重载），无需旧版 RS1 的
 *         {@code NetworkNodeManager}/{@code unloaded} 之类的 hack；</li>
 *     <li>由 {@code ExternalStorage.detectChanges()} 做增量 diff 并把变化推送到 RS 网络
 *         （含 RS 终端刷新），无需手写缓存差异推送；</li>
 *     <li>在节点激活时把外部存储接入网络（{@code onActiveChanged}），未激活时断开。</li>
 * </ul>
 * 节点数（能量消耗）设为 0，能量由 AE2 侧以 FE 形式补充（见 BridgeEnergyStorage）。
 */
public class BridgeNetworkNode extends ExternalStorageNetworkNode {

    public BridgeNetworkNode(long energyUsage) {
        // RS2 外部存储节点需要一个时钟供应器（用于插入追踪），这里沿用 RS2 原版做法用系统时间。
        super(energyUsage, System::currentTimeMillis);
    }

    /**
     * 网络每次 tick 调用：先执行基类能量抽取（0 消耗），再把 AE2 库存变化推送给 RS 网络。
     * detectChanges 内部做增量 diff，仅在确有变化时才通知网络存储组件，开销很小。
     */
    @Override
    public void doWork() {
        super.doWork();
        this.detectChanges();
    }
}
