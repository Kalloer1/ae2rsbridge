# 移植到 Minecraft 1.21.1 + NeoForge（分支 `1.21.1-neoforge`）

本分支从 `main`（1.20.1 / Forge 47）派生，目标是移植到 **1.21.1 / NeoForge 21.1.x**。
本文件记录移植状态与后续必做项。**当前分支只完成了构建骨架，源码尚未按新 API 迁移，无法直接编译。**

---

## 版本目标

| 项目 | 1.20.1（main） | 1.21.1（本分支） |
| --- | --- | --- |
| Minecraft | 1.20.1 | 1.21.1 |
| 加载器 | Forge 47.3.0 | NeoForge 21.1.x |
| Java | 17 | **21** |
| 构建插件 | ForgeGradle 6 | **ModDevGradle 2.x** |
| 模组元数据 | `META-INF/mods.toml` | `META-INF/neoforge.mods.toml` |
| pack_format | 15 | **34** |
| AE2 | forge 15.4.10 | 1.21.1 版（约 19.x，API 有变） |
| Refined Storage | 1.12.4（旧 API） | **RS2 v2.0.x（全新 API，需重写）** |

---

## 已完成（构建骨架）

- [x] `gradle.properties`：切到 1.21.1 / NeoForge / Java 21，新增 parchment、依赖版本占位。
- [x] `build.gradle`：改为 ModDevGradle（`net.neoforged.moddev`），配置 NeoForge maven、Refined Mods maven、client/server runs。
- [x] `META-INF/mods.toml` → `META-INF/neoforge.mods.toml`：依赖 `forge` 改为 `neoforge`，`mandatory=true` 改为 `type="required"`。
- [x] `pack.mcmeta`：pack_format 15 → 34。
- [x] `mod_version` → 1.1.0（区分 1.20.1 线）。

---

## 待完成（源码迁移）

### 0. 依赖 jar（阻塞项，必须先做）
- [ ] 获取 **AE2 1.21.1** 与 **RS2 v2.0.x（NeoForge）** 的依赖 jar 或 maven 坐标。
- [ ] 在 `build.gradle` 的 `dependencies {}` 中取消注释并填入正确坐标；本地 `libs/` 里的 1.20.1 jar 需替换/移除。
- [ ] 首次 `./gradlew --refresh-dependencies` 拉取，确认能解析。

### 1. NeoForge 通用 API 迁移（全部 18 个 java 文件都要过一遍）
- [ ] 事件总线：Forge `MinecraftForge.EVENT_BUS` / `@Mod.EventBusSubscriber` → NeoForge `NeoForge.EVENT_BUS` / `@EventBusSubscriber`。
- [ ] 注册：`DeferredRegister` 包名 `net.minecraftforge.registries` → `net.neoforged.neoforge.registries`；`RegistryObject` → `DeferredHolder`。
- [ ] 能力系统（重点）：Forge `ICapabilityProvider` / `LazyOptional` → NeoForge `Capabilities` + `RegisterCapabilitiesEvent`（能量 `IEnergyStorage`、物品/流体 handler 的暴露方式全变）。
- [ ] `@Mod` 主类构造签名（NeoForge 传入 `IEventBus` / `ModContainer`）。
- [ ] 网络包：Forge `SimpleChannel` → NeoForge `PayloadRegistrar` + `CustomPacketPayload`（若本模组有自定义包）。
- [ ] 菜单/GUI：`MenuType`、`AbstractContainerMenu`、Screen 注册入口变化（`RegisterMenuScreensEvent`）。
- [ ] 1.21 原版变化：`Component`、`ResourceLocation.parse(...)`（构造器改为工厂方法）、注册表访问等。

### 2. AE2 集成迁移（`integration/ae2/`）
- [ ] `RSNetworkToAEStorage`：对照 1.21.1 的 AE2 `MEStorage` / `IStorageProvider` / `IStorageMounts` 接口（包路径与方法签名可能变化）。
- [ ] `AEItemKey` / `AEFluidKey` / `AEKey` API 复核。
- [ ] `IPriorityHost`、`mountInventories`、`requestUpdate` 等调用点复核。

### 3. Refined Storage 集成迁移（`integration/rs/` —— 最大工作量，基本重写）
> RS2 是从零重写，旧的 `com.refinedmods.refinedstorage.apiimpl.*` 全部不存在。
- [ ] 重新调研 RS2 的对外 API（`refinedmods.refinedstorage.api.*` 新架构）：网络、节点、存储、Grid 缓存模型。
- [ ] `BridgeNetworkNode`：RS2 的 NetworkNode / 节点注册机制完全不同，需重写。
- [ ] `AENetworkToRSItemStorage` / `AENetworkToRSFluidStorage`：RS2 的外部存储（External Storage）接口与「资源（Resource）」抽象重写；RS2 统一了 item/fluid/chemical 为通用 Resource，可能需要合并逻辑。
- [ ] 缓存 / 监听器：旧的 `ItemStorageCache` / `ItemGridStorageCacheListener` / `reAttachListeners` / `invalidate` 语义在 RS2 中不存在，需按 RS2 的存储通道与变更通知机制重做。
- [ ] `BridgeTransactionGuard` 防重入逻辑保留思路，但落点需按 RS2 调用链重新确认。
- [ ] `non_stackable_only` 路由开关、优先级双向逻辑按新 API 重接。

### 4. 数据/资源
- [ ] blockstates / models / lang / recipes 复核（1.21 数据生成 API 有变，若用 datagen 需迁移到 NeoForge datagen）。
- [ ] 确认 `assets/.../screens/*.json` 若依赖 AE2 的 style 系统，AE2 1.21.1 的 GUI style 是否兼容。

### 5. 验证
- [ ] `./gradlew build` 通过。
- [ ] `./gradlew runClient` 起服，验证桥接方块、双向读写、终端刷新、优先级、开关等（对照 main 上已修复的行为回归测试）。

---

## 注意
- 本分支 `libs/` 内仍是 1.20.1 的 AE2/RS jar，**不要**用它们编译 1.21.1；仅作历史参考，迁移完成后应替换。
- `build.gradle` 中 ModDevGradle、NeoForge、依赖的具体版本号均标注了「verify」，落地前请到 neoforged.net / Modrinth 核对当时最新的 1.21.1 版本。
- main（1.20.1 Forge 线）继续维护 bug 修复；两条线暂不合并。
