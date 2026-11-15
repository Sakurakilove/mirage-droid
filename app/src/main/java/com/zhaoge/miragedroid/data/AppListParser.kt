package com.zhaoge.miragedroid.data

/**
 * 解析设备里的应用列表 JSON（p65 的 `/data/quickapp/apps.json` / `apps.json_hide`）。
 *
 * 固件里这个文件的结构是 `{"InstalledApps":[{...}]}`，不同固件版本字段名不完全一致
 * （package/pkg、appName/label/name、hideFlag），而且它是从 NSH 控制台 `cat` 出来的，
 * 输出前后可能混着 shell 回显/日志。这里做纯函数解析，便于 JVM 单测覆盖。
 */
data class AppEntry(
    val packageName: String,
    val name: String,
    val hidden: Boolean,
) {
    val displayName: String get() = name.ifEmpty { packageName.ifEmpty { "(未命名)" } }
}

data class AppList(val entries: List<AppEntry>) {
    val count: Int get() = entries.size
}

object AppListParser {
    /**
     * 从 `cat apps.json` 的原始输出里解析应用列表；失败返回 null。
     * 允许 JSON 前后有 shell 回显、日志行、提示符。
     */
    fun parse(raw: String): AppList? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val jsonText = extractJson(text) ?: return null
        val root = Json.parseOrNull(jsonText) ?: return null
        val items: List<JsonValue> = when (root) {
            is JsonValue.Obj -> (root.field("InstalledApps") as? JsonValue.Arr)?.items
                ?: (root.field("installedApps") as? JsonValue.Arr)?.items
                ?: findFirstArray(root)
            is JsonValue.Arr -> root.items
            else -> null
        } ?: return null
        val entries = ArrayList<AppEntry>(items.size)
        for (item in items) {
            val obj = item as? JsonValue.Obj ?: continue
            val packageName = obj.str("package")
                .ifEmpty { obj.str("pkg") }
                .ifEmpty { obj.str("packageName") }
            val name = obj.str("appName")
                .ifEmpty { obj.str("label") }
                .ifEmpty { obj.str("name") }
                .ifEmpty { obj.str("displayName") }
            val hidden = obj.bool("hideFlag") || obj.bool("hidden") || obj.bool("hide")
            if (packageName.isEmpty() && name.isEmpty()) continue
            entries.add(AppEntry(packageName, name, hidden))
        }
        return AppList(entries)
    }

    /** 从杂讯中截出第一个完整的 JSON 对象/数组（`{` 到最后一个 `}`，`[` 到最后一个 `]`）。 */
    fun extractJson(raw: String): String? {
        val objectStart = raw.indexOf('{')
        val objectEnd = raw.lastIndexOf('}')
        if (objectStart >= 0 && objectEnd > objectStart) {
            val candidate = raw.substring(objectStart, objectEnd + 1)
            if (Json.parseOrNull(candidate) != null) return candidate
        }
        val arrayStart = raw.indexOf('[')
        val arrayEnd = raw.lastIndexOf(']')
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            val candidate = raw.substring(arrayStart, arrayEnd + 1)
            if (Json.parseOrNull(candidate) != null) return candidate
        }
        return null
    }

    private fun findFirstArray(root: JsonValue.Obj): List<JsonValue>? {
        var best: List<JsonValue>? = null
        for (value in root.fields.values) {
            val array = value as? JsonValue.Arr ?: continue
            if (best == null || array.items.size > best.size) best = array.items
        }
        return best
    }
}
