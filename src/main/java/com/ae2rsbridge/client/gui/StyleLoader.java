package com.ae2rsbridge.client.gui;

import appeng.client.gui.style.ScreenStyle;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轻量级 ScreenStyle 加载器。
 *
 * <p>AE2 自带的 {@code StyleManager} 在解析 include 时把资源命名空间硬编码为 {@code ae2}
 * （见 {@code StyleManager#loadMergedJsonTree} 里的 {@code AppEng.makeId(...)}），
 * 因此它无法读取本模组 {@code assets/ae2rsbridge/screens/} 下的 JSON。</p>
 *
 * <p>本类复刻了 AE2 的 include 合并逻辑，但改用 {@code ae2rsbridge} 命名空间，
 * 并直接复用 AE2 的 {@link ScreenStyle#GSON} 与 {@link ScreenStyle#validate()}，
 * 从而让我们的界面能享受与 AE2 完全一致的 JSON 样式体系。</p>
 */
public final class StyleLoader {

    private static final String NAMESPACE = "ae2rsbridge";
    private static final String PROP_INCLUDES = "includes";

    /**
     * 需要按 key 逐项合并（而非整体覆盖）的顶层属性，
     * 与 AE2 {@code StyleManager#combineLayers} 保持一致。
     */
    private static final String[] MERGE_KEYS = {
            "slots", "text", "palette", "images", "terminalStyle", "widgets"
    };

    private StyleLoader() {
    }

    /**
     * 从 {@code ae2rsbridge} 命名空间加载并校验一个界面样式文档。
     *
     * @param path 以 {@code /} 开头的资源路径，例如 {@code /screens/storage_bridge.json}
     */
    public static ScreenStyle load(String path) {
        try {
            JsonObject document = loadMergedJsonTree(path, new HashSet<>());
            ScreenStyle style = ScreenStyle.GSON.fromJson(document, ScreenStyle.class);
            // 只要求最终合并后的文档是完整有效的（palette 齐全等）
            style.validate();
            return style;
        } catch (FileNotFoundException e) {
            throw new RuntimeException("找不到界面 JSON 文件: " + path, e);
        } catch (Exception e) {
            throw new RuntimeException("读取界面 JSON 文件失败: " + path, e);
        }
    }

    private static JsonObject loadMergedJsonTree(String path, Set<String> loadedFiles) throws IOException {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("路径需要以斜杠开头: " + path);
        }
        // 归一化相对路径（处理 include 里的 ..）
        if (path.contains("..")) {
            path = URI.create(path).normalize().toString();
        }
        if (!loadedFiles.add(path)) {
            throw new IllegalStateException("检测到循环 include: " + loadedFiles);
        }

        ResourceManager resourceManager = Minecraft.getInstance().getResourceManager();
        ResourceLocation resourceId = new ResourceLocation(NAMESPACE, path.substring(1));
        var resource = resourceManager.getResource(resourceId)
                .orElseThrow(() -> new FileNotFoundException(resourceId.toString()));

        JsonObject document;
        try (Reader reader = resourceManager.openAsReader(resourceId)) {
            document = ScreenStyle.GSON.fromJson(reader, JsonObject.class);
        }

        if (document.has(PROP_INCLUDES)) {
            String basePath = getBasePath(path);
            String[] includes = ScreenStyle.GSON.fromJson(document.get(PROP_INCLUDES), String[].class);

            List<JsonObject> layers = new ArrayList<>();
            for (String include : includes) {
                layers.add(loadMergedJsonTree(basePath + include, loadedFiles));
            }
            layers.add(document);
            document = combineLayers(layers);
        }

        return document;
    }

    private static String getBasePath(String path) {
        int lastSep = path.lastIndexOf('/');
        return lastSep == -1 ? "" : path.substring(0, lastSep + 1);
    }

    private static JsonObject combineLayers(List<JsonObject> layers) {
        JsonObject result = new JsonObject();

        // 先逐层覆盖所有顶层属性
        for (JsonObject layer : layers) {
            for (Map.Entry<String, JsonElement> entry : layer.entrySet()) {
                result.add(entry.getKey(), entry.getValue());
            }
        }

        // 再对需要深合并的属性按 key 合并（高层覆盖低层）
        for (String key : MERGE_KEYS) {
            mergeObjectKeys(key, layers, result);
        }

        return result;
    }

    private static void mergeObjectKeys(String propertyName, List<JsonObject> layers, JsonObject target) {
        JsonObject mergedObject = null;
        for (JsonObject layer : layers) {
            JsonElement layerEl = layer.get(propertyName);
            if (layerEl != null && layerEl.isJsonObject()) {
                if (mergedObject == null) {
                    mergedObject = new JsonObject();
                }
                for (Map.Entry<String, JsonElement> entry : layerEl.getAsJsonObject().entrySet()) {
                    mergedObject.add(entry.getKey(), entry.getValue());
                }
            }
        }
        if (mergedObject != null) {
            target.add(propertyName, mergedObject);
        }
    }
}
