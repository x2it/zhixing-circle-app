package com.realtor.geeksales.data.schema

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模板字段定义（schema）。
 *
 * 这是 App 与线上「知行朋友圈」模板对齐的契约：
 * - 内置字段（builtin=true）：key 对应本地 Customer 列，值存主表
 * - 扩展字段（builtin=false）：线上模板新增的自定义字段，值存 customer_fields（EAV）
 *
 * 线上模板加什么字段，App 表单/详情/导入导出就跟随显示什么——"万物可插"。
 */
data class FieldDef(
    /** 稳定标识：内置字段用 Customer 列名；扩展字段用线上 schema key */
    val key: String,
    /** 显示名（跟随线上模板 label，可被覆盖） */
    val label: String,
    /** 类型：text / number / tel / date / select / multiselect / textarea */
    val type: String = "text",
    /** 分组名：基础信息 / 联系信息 / 购房需求 / 意向与跟进 / 其他 */
    val group: String = "其他",
    val required: Boolean = false,
    /** select / multiselect 的可选项 */
    val options: List<String> = emptyList(),
    /** 显示顺序 */
    val order: Int = 0,
    /** true=存 Customer 列；false=存 customer_fields */
    val builtin: Boolean = true
)

/** 内置字段 key 集合（值存 Customer 主表列） */
object BuiltinKeys {
    const val NAME = "name"
    const val PHONE = "phone"
    const val PHONE2 = "phone2"
    const val GENDER = "gender"
    const val AGE = "age"
    const val WECHAT = "wechat"
    const val SOURCE = "source"
    const val TAGS = "tags"
    const val EMAIL = "email"
    const val IM = "im"
    const val COMPANY = "company"
    const val JOB_TITLE = "jobTitle"
    const val BIRTHDAY = "birthday"
    const val NICKNAME = "nickname"
    const val ADDRESS = "address"
    const val WEBSITE = "website"
    const val AREA_PREF = "areaPref"
    const val BUDGET_MIN = "budgetMin"
    const val BUDGET_MAX = "budgetMax"
    const val HOUSE_TYPE = "houseType"
    const val TARGET_PROJECT = "targetProject"
    const val INTENT_LEVEL = "intentLevel"
    const val NEXT_FOLLOW_AT = "nextFollowAt"
    const val NOTE = "note"

    /** 所有内置 key */
    val ALL: Set<String> = setOf(
        NAME, PHONE, PHONE2, GENDER, AGE, WECHAT, SOURCE, TAGS,
        EMAIL, IM, COMPANY, JOB_TITLE, BIRTHDAY, NICKNAME, ADDRESS, WEBSITE,
        AREA_PREF, BUDGET_MIN, BUDGET_MAX, HOUSE_TYPE, TARGET_PROJECT,
        INTENT_LEVEL, NEXT_FOLLOW_AT, NOTE
    )
}

/**
 * Schema 存储：内置默认模板（离线可用）+ 线上模板拉取合并 + 本地自定义字段。
 * 合并规则：内置字段的 label/order 可被线上覆盖；线上新增的 key 追加为扩展字段；
 * 本地自定义字段（数据页添加）始终保留。
 */
@Singleton
class SchemaStore @Inject constructor(
    @ApplicationContext ctx: Context
) {
    private val prefs = ctx.getSharedPreferences("tma_prefs", Context.MODE_PRIVATE)

    private companion object {
        const val KEY_SCHEMA = "schema_json_v1"
        const val KEY_LOCAL_FIELDS = "schema_local_fields_v1"
    }

    /** 内置默认模板（房产销售场景，开箱即用） */
    fun defaultSchema(): List<FieldDef> = listOf(
        FieldDef(BuiltinKeys.NAME, "姓名", "text", "基础信息", required = true, order = 1),
        FieldDef(BuiltinKeys.PHONE, "手机号", "tel", "基础信息", required = true, order = 2),
        FieldDef(BuiltinKeys.PHONE2, "备用电话", "tel", "基础信息", order = 3),
        FieldDef(BuiltinKeys.GENDER, "性别", "text", "基础信息", order = 4),
        FieldDef(BuiltinKeys.AGE, "年龄", "number", "基础信息", order = 5),
        FieldDef(BuiltinKeys.WECHAT, "微信", "text", "基础信息", order = 6),
        FieldDef(BuiltinKeys.SOURCE, "来源", "text", "基础信息", order = 7),
        FieldDef(BuiltinKeys.TAGS, "标签", "text", "基础信息", order = 8),
        FieldDef(BuiltinKeys.EMAIL, "邮箱", "text", "联系信息", order = 10),
        FieldDef(BuiltinKeys.IM, "即时消息", "text", "联系信息", order = 11),
        FieldDef(BuiltinKeys.COMPANY, "公司", "text", "联系信息", order = 12),
        FieldDef(BuiltinKeys.JOB_TITLE, "职位", "text", "联系信息", order = 13),
        FieldDef(BuiltinKeys.BIRTHDAY, "生日", "date", "联系信息", order = 14),
        FieldDef(BuiltinKeys.NICKNAME, "昵称", "text", "联系信息", order = 15),
        FieldDef(BuiltinKeys.ADDRESS, "地址", "text", "联系信息", order = 16),
        FieldDef(BuiltinKeys.WEBSITE, "网站", "text", "联系信息", order = 17),
        FieldDef(BuiltinKeys.AREA_PREF, "意向区域", "text", "购房需求", order = 20),
        FieldDef(BuiltinKeys.BUDGET_MIN, "预算下限(万)", "number", "购房需求", order = 21),
        FieldDef(BuiltinKeys.BUDGET_MAX, "预算上限(万)", "number", "购房需求", order = 22),
        FieldDef(BuiltinKeys.HOUSE_TYPE, "房型偏好", "text", "购房需求", order = 23),
        FieldDef(BuiltinKeys.TARGET_PROJECT, "意向楼盘", "text", "购房需求", order = 24),
        FieldDef(BuiltinKeys.INTENT_LEVEL, "意向等级", "select", "意向与跟进", options = listOf("A", "B", "C", "D", "U"), order = 30),
        FieldDef(BuiltinKeys.NEXT_FOLLOW_AT, "下次跟进", "date", "意向与跟进", order = 31),
        FieldDef(BuiltinKeys.NOTE, "备注", "textarea", "意向与跟进", order = 32)
    )

    /** 当前生效 schema：本地缓存（含线上合并与自定义）或默认模板 */
    fun current(): List<FieldDef> {
        val cached = prefs.getString(KEY_SCHEMA, null)
        if (cached.isNullOrBlank()) return defaultSchema()
        return runCatching {
            val list = parseArray(cached).mapNotNull { el -> runCatching { FieldDefSerializer.fromJson(el) }.getOrNull() }
            if (list.isEmpty()) defaultSchema() else list
        }.getOrDefault(defaultSchema())
    }

    /** 将线上模板合并进当前 schema 并缓存（线上定义为准，保留本地自定义） */
    fun mergeRemote(remote: List<FieldDef>): List<FieldDef> {
        val local = current()
        val localCustom = local.filter { !it.builtin }
        val merged = mergeFields(local, remote)
        // 保留本地自定义字段（数据页添加的），避免被线上覆盖删除
        val result = merged + localCustom.filter { lc -> merged.none { it.key == lc.key } }
        save(result)
        return result
    }

    /** 添加本地自定义字段（数据页），保存并返回新 schema */
    fun addLocalField(def: FieldDef): List<FieldDef> {
        val cur = current()
        val next = cur + def.copy(builtin = false)
        save(next)
        return next
    }

    /** 移除本地自定义字段 */
    fun removeLocalField(key: String): List<FieldDef> {
        val cur = current()
        val next = cur.filter { it.key != key || it.builtin }
        save(next)
        return next
    }

    fun save(schema: List<FieldDef>) {
        val json = "[" + schema.joinToString(",") { FieldDefSerializer.toJson(it) } + "]"
        prefs.edit().putString(KEY_SCHEMA, json).apply()
    }

    /** 轻量 JSON 数组解析：提取每个 {…} 对象（options 数组内不含花括号） */
    private fun parseArray(s: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out.add(s.substring(start, i + 1))
                        start = -1
                    }
                }
            }
            i++
        }
        return out
    }

    /** 合并：内置字段以本地为基础、线上覆盖 label/type/options/order；线上新增 key 追加为扩展 */
    private fun mergeFields(local: List<FieldDef>, remote: List<FieldDef>): List<FieldDef> {
        val byKey = LinkedHashMap<String, FieldDef>()
        local.forEach { byKey[it.key] = it }
        remote.forEach { r ->
            if (r.key.isBlank()) return@forEach
            val exist = byKey[r.key]
            if (exist != null && exist.builtin) {
                // 内置：覆盖展示配置
                byKey[r.key] = exist.copy(
                    label = r.label.ifBlank { exist.label },
                    type = r.type.ifBlank { exist.type },
                    options = r.options.ifEmpty { exist.options },
                    order = r.order
                )
            } else {
                // 新增 key → 扩展字段（builtin=false）
                byKey[r.key] = FieldDef(
                    key = r.key,
                    label = r.label.ifBlank { r.key },
                    type = r.type.ifBlank { "text" },
                    group = r.group.ifBlank { "其他" },
                    required = r.required,
                    options = r.options,
                    order = r.order,
                    builtin = false
                )
            }
        }
        return byKey.values.sortedBy { it.order }
    }
}

/** FieldDef JSON 序列化（轻量，无 kotlinx 依赖） */
object FieldDefSerializer {
    fun toJson(f: FieldDef): String {
        val base = "{\"key\":\"${esc(f.key)}\",\"label\":\"${esc(f.label)}\",\"type\":\"${esc(f.type)}\",\"group\":\"${esc(f.group)}\",\"required\":${f.required},\"order\":${f.order},\"builtin\":${f.builtin}"
        if (f.options.isEmpty()) return "$base}"
        val opts = f.options.joinToString(",", prefix = "[", postfix = "]") { "\"${esc(it)}\"" }
        return "$base,\"options\":$opts}"
    }

    fun fromJson(s: String): FieldDef {
        fun str(k: String): String {
            val m = Regex("\"$k\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(s)
            return m?.groupValues?.get(1)?.replace("\\\"", "\"") ?: ""
        }
        fun bool(k: String, def: Boolean = false): Boolean {
            val m = Regex("\"$k\"\\s*:\\s*(true|false)").find(s)
            return m?.groupValues?.get(1)?.toBoolean() ?: def
        }
        fun int(k: String): Int {
            val m = Regex("\"$k\"\\s*:\\s*(\\d+)").find(s)
            return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        fun list(k: String): List<String> {
            val m = Regex("\"$k\"\\s*:\\s*\\[([^\\]]*)\\]").find(s)
            if (m == null) return emptyList()
            return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(m.groupValues[1])
                .map { it.groupValues[1].replace("\\\"", "\"") }.toList()
        }
        return FieldDef(
            key = str("key"),
            label = str("label"),
            type = str("type").ifBlank { "text" },
            group = str("group").ifBlank { "其他" },
            required = bool("required"),
            options = list("options"),
            order = int("order"),
            builtin = bool("builtin", def = true)
        )
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
