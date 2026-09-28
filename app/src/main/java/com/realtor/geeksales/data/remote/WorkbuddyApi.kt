package com.realtor.geeksales.data.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 知行朋友圈联系人（对齐 TMA 客户字段） */
@Serializable
data class WbContact(
    val id: String = "",
    val name: String = "",
    val nickname: String? = null,
    val phone: String = "",
    val wechat: String? = null,
    /** S/A/B/C/D/V */
    val tier: String? = null,
    val source: String? = null,
    /** 下次跟进 YYYY-MM-DD */
    @SerialName("nextFollowupDate") val nextFollowupDate: String? = null,
    val tags: List<WbTag> = emptyList(),
    @SerialName("tagIds") val tagIds: List<String> = emptyList(),
    val memo: String? = null,
    /** 自定义扩展字段（线上模板自定义字段，key→value） */
    @SerialName("customFields") val customFields: Map<String, String> = emptyMap(),
    /** 幂等键：App 端传本地记录 ID（tma-<id>），重复上传不会产生重复数据 */
    @SerialName("externalId") val externalId: String? = null
)

/** 线上模板元数据：tiers（分层）/ identityTags（身份标签）/ attributeTags（属性标签），驱动 App 筛选与标签选择器 */
data class WbSchemaBundle(
    val fields: List<WbRemoteField> = emptyList(),
    val tiers: List<String> = listOf("S", "A", "B", "C", "D", "V", "U"),
    val identityTags: List<String> = emptyList(),
    val attributeTags: List<String> = emptyList()
)

/** 批量接口响应（POST /contacts/batch、/followups/batch）：逐条独立处理，errors 含失败明细 */
@Serializable
data class WbBatchContactResult(
    val id: String = "",
    val name: String = "",
    val tier: String? = null,
    val tags: List<WbTag> = emptyList()
)

@Serializable
data class WbBatchError(
    val index: Int = -1,
    val message: String = ""
)

@Serializable
data class WbBatchResponse(
    val items: List<WbBatchContactResult> = emptyList(),
    val errors: List<WbBatchError> = emptyList(),
    val created: Int = 0
)

/** 批次信息（云端「数据可追溯·时光机」规范）：每次批量同步上报 batchName/source/deviceInfo */
data class WbBatchMeta(
    val batchName: String = "",
    val source: String = "tma",
    val deviceInfo: String = ""
)

@Serializable
data class WbTag(
    val id: String = "",
    val name: String = "",
    val category: String? = null,
    val color: String? = null
)

/** 联系人分页响应 */
@Serializable
data class WbContactPage(
    val items: List<WbContact> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("pageSize") val pageSize: Int = 20
)

/** 线上模板字段定义（GET /api/schema / POST /api/schema/fields 的载荷） */
data class WbRemoteField(
    val key: String,
    val label: String,
    val type: String = "text",
    val group: String? = null,
    val required: Boolean = false,
    val options: List<String> = emptyList(),
    val order: Int = 0
)

/** 知行朋友圈跟进记录（对应 TMA 通话登记/跟进历史） */
@Serializable
data class WbFollowup(
    val id: String = "",
    @SerialName("contactId") val contactId: String = "",
    val content: String = "",
    @SerialName("followupType") val followupType: String? = null,
    @SerialName("followupDate") val followupDate: String? = null,
    @SerialName("nextFollowupDate") val nextFollowupDate: String? = null
)

@Serializable
data class WbFollowupPage(
    val items: List<WbFollowup> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("pageSize") val pageSize: Int = 20
)

/** 知行朋友圈消息记录（短信同步；线上需新增 /api/messages 接口） */
@Serializable
data class WbMessage(
    val id: String = "",
    @SerialName("contactId") val contactId: String? = null,
    val phone: String = "",
    val body: String = "",
    /** in=收到, out=发出 */
    val direction: String = "in",
    /** 短信时间，如 "2026-09-28 14:30" */
    @SerialName("messageDate") val messageDate: String? = null
)

@Serializable
data class WbMessagePage(
    val items: List<WbMessage> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("pageSize") val pageSize: Int = 20
)

/** 知行朋友圈通话记录（通话备份/同步；线上 /api/calls，需先开启 call-sync 开关） */
@Serializable
data class WbCall(
    val id: String = "",
    @SerialName("contactId") val contactId: String? = null,
    val phone: String = "",
    /** in=呼入 / out=呼出 / missed=未接；只接受这三种，其他 400 */
    val direction: String = "in",
    /** 通话时长（秒）；未接填 0 */
    val duration: Long = 0,
    /** 通话时间，格式必须 "YYYY-MM-DD HH:mm" */
    @SerialName("callDate") val callDate: String? = null,
    val note: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null
)

@Serializable
data class WbCallPage(
    val items: List<WbCall> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("pageSize") val pageSize: Int = 20
)

sealed class WbResult<out T> {
    data class Success<T>(val data: T) : WbResult<T>()
    data class Error(val message: String) : WbResult<Nothing>()
}

/**
 * 知行朋友圈（WorkBuddy）API 客户端。
 * 基础地址 {应用访问地址}/api，请求头 X-API-Key 认证。
 */
@Singleton
class WorkbuddyApi @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val apiKeyStore: ApiKeyStore
) {
    companion object {
        /** 知行朋友圈默认应用地址；可在「数据」页修改，平台迁移/关停时切换 */
        const val DEFAULT_BASE_URL = "https://people.app.workbuddy.host/api"
        const val PAGE_SIZE = 100
        private const val PREFS = "tma_prefs"
        private const val KEY_BASE_URL = "wb_base_url"
    }

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 当前 API 基础地址（可配置，默认知行朋友圈） */
    fun baseUrl(): String =
        prefs.getString(KEY_BASE_URL, null)?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: DEFAULT_BASE_URL

    /** 修改 API 基础地址（平台迁移时使用） */
    fun setBaseUrl(url: String) {
        prefs.edit().putString(KEY_BASE_URL, url.trim().trimEnd('/')).apply()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val apiKey: String? get() = apiKeyStore.load()

    /** 分页拉取全部联系人 */
    suspend fun fetchAllContacts(): WbResult<List<WbContact>> = withContext(Dispatchers.IO) {
        val all = mutableListOf<WbContact>()
        var page = 1
        while (true) {
            when (val r = fetchContactsPage(page)) {
                is WbResult.Error -> return@withContext r
                is WbResult.Success -> {
                    all += r.data.items
                    if (r.data.items.size < PAGE_SIZE || all.size >= r.data.total) break
                    page++
                }
            }
        }
        WbResult.Success(all)
    }

    suspend fun fetchContactsPage(page: Int): WbResult<WbContactPage> = withContext(Dispatchers.IO) {
        val body = get("/contacts?page=$page&pageSize=$PAGE_SIZE")
            ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
        runCatching { json.decodeFromString<WbContactPage>(body) }
            .map { WbResult.Success(it) as WbResult<WbContactPage> }
            .getOrElse { WbResult.Error("响应解析失败：${it.message}") }
    }

    /** 拉取全部标签（name→id 映射用于导出打标） */
    suspend fun fetchAllTags(): WbResult<List<WbTag>> = withContext(Dispatchers.IO) {
        val body = get("/tags?pageSize=100")
            ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
        val arr = runCatching {
            val el = json.parseToJsonElement(body)
            (el as? JsonObject)?.get("items") as? JsonArray ?: el as? JsonArray
        }.getOrNull()
        val list = arr?.mapNotNull { el ->
            runCatching { json.decodeFromJsonElement(WbTag.serializer(), el) }.getOrNull()
        }.orEmpty()
        WbResult.Success(list)
    }

    /** 创建标签（线上不存在同名标签时，导出前自动创建；category=custom 与线上自定义标签对齐） */
    suspend fun createTag(name: String, category: String = "custom", color: String = "#3b82f6"): WbResult<WbTag> =
        withContext(Dispatchers.IO) {
            val body = buildString {
                append("{\"name\":\"${esc(name)}\",")
                append("\"category\":\"${esc(category)}\",")
                append("\"color\":\"${esc(color)}\"}")
            }
            val resp = post("/tags", body)
                ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
            if (!is2xx(resp)) return@withContext WbResult.Error(extractError(resp))
            runCatching {
                val el = json.parseToJsonElement(resp)
                WbTag(
                    id = (el as? JsonObject)?.get("id")?.jsonPrimitive?.content.orEmpty(),
                    name = (el as? JsonObject)?.get("name")?.jsonPrimitive?.content.orEmpty(),
                    category = (el as? JsonObject)?.get("category")?.jsonPrimitive?.content,
                    color = (el as? JsonObject)?.get("color")?.jsonPrimitive?.content
                )
            }.fold(
                { WbResult.Success(it) as WbResult<WbTag> },
                { WbResult.Error("解析失败：$resp") }
            )
        }

    suspend fun createContact(c: WbContact): WbResult<String> = withContext(Dispatchers.IO) {
        val resp = post("/contacts", buildContactJson(c))
            ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
        if (!is2xx(resp)) return@withContext WbResult.Error(extractError(resp))
        // 从响应中解析新建联系人的 id，供本地回写 wbContactId
        val id = runCatching {
            val el = json.parseToJsonElement(resp)
            (el as? JsonObject)?.get("id")?.jsonPrimitive?.content
        }.getOrNull()
        if (id.isNullOrBlank()) WbResult.Error("创建成功但未返回联系人 id")
        else WbResult.Success(id)
    }

    suspend fun updateContact(id: String, c: WbContact): WbResult<Unit> = withContext(Dispatchers.IO) {
        val resp = put("/contacts/$id", buildContactJson(c))
            ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
        if (is2xx(resp)) WbResult.Success(Unit) else WbResult.Error(extractError(resp))
    }

    /** 分页拉取全部跟进记录 */
    suspend fun fetchAllFollowups(): WbResult<List<WbFollowup>> = withContext(Dispatchers.IO) {
        val all = mutableListOf<WbFollowup>()
        var page = 1
        while (true) {
            val body = get("/followups?page=$page&pageSize=$PAGE_SIZE")
                ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
            val r = runCatching { json.decodeFromString<WbFollowupPage>(body) }
                .getOrElse { return@withContext WbResult.Error("响应解析失败：${it.message}") }
            all += r.items
            if (r.items.size < PAGE_SIZE || all.size >= r.total) break
            page++
        }
        WbResult.Success(all)
    }

    /** 推送一条跟进记录（通话登记历史） */
    suspend fun createFollowup(f: WbFollowup): WbResult<Unit> = withContext(Dispatchers.IO) {
        val resp = post("/followups", buildFollowupJson(f))
            ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
        if (is2xx(resp)) WbResult.Success(Unit) else WbResult.Error(extractError(resp))
    }

    // ---- 消息（短信）同步：对应线上 /api/messages ----

    /** 分页拉取全部消息（短信） */
    suspend fun fetchAllMessages(): WbResult<List<WbMessage>> = withContext(Dispatchers.IO) {
        val all = mutableListOf<WbMessage>()
        var page = 1
        while (true) {
            val body = get("/messages?page=$page&pageSize=$PAGE_SIZE")
                ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key（线上 /api/messages 接口需已开放）")
            val r = runCatching { json.decodeFromString<WbMessagePage>(body) }
                .getOrElse { return@withContext WbResult.Error("响应解析失败：${it.message}") }
            all += r.items
            if (r.items.size < PAGE_SIZE || all.size >= r.total) break
            page++
        }
        WbResult.Success(all)
    }

    /** 推送一条消息（短信） */
    suspend fun createMessage(m: WbMessage): WbResult<Unit> = withContext(Dispatchers.IO) {
        val body = buildString {
            append("{")
            if (!m.contactId.isNullOrBlank()) append("\"contactId\":\"${esc(m.contactId)}\",")
            append("\"phone\":\"${esc(m.phone)}\",")
            append("\"body\":\"${esc(m.body)}\",")
            append("\"direction\":\"${esc(m.direction)}\",")
            if (!m.messageDate.isNullOrBlank()) append("\"messageDate\":\"${esc(m.messageDate)}\"")
            append("}")
        }
        val resp = post("/messages", body)
            ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key（线上 /api/messages 接口需已开放）")
        if (is2xx(resp)) WbResult.Success(Unit) else WbResult.Error(extractError(resp))
    }

    /** 查询线上短信同步开关；false 时 messages 接口会返回 403，应提示用户先在网页开启 */
    suspend fun smsSyncEnabled(): WbResult<Boolean> = withContext(Dispatchers.IO) {
        val body = get("/settings/sms-sync")
            ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
        val v = runCatching {
            val el = json.parseToJsonElement(body)
            (el as? JsonObject)?.get("smsSyncEnabled")?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        }.getOrNull()
        if (v == null) WbResult.Error("响应解析失败：$body")
        else WbResult.Success(v)
    }

    /** 一键开启线上短信同步开关（PUT /api/settings/sms-sync {"enabled":true}），失败返回错误 */
    suspend fun setSmsSync(enabled: Boolean): WbResult<Unit> = withContext(Dispatchers.IO) {
        val resp = put("/settings/sms-sync", "{\"enabled\":$enabled}")
            ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
        if (is2xx(resp)) WbResult.Success(Unit) else WbResult.Error(extractError(resp))
    }

    // ---- 通话记录同步：对应线上 /api/calls ----

    /** 查询线上通话同步开关；false 时 calls 接口返回 403 */
    suspend fun callSyncEnabled(): WbResult<Boolean> = withContext(Dispatchers.IO) {
        val body = get("/settings/call-sync")
            ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
        val v = runCatching {
            val el = json.parseToJsonElement(body)
            (el as? JsonObject)?.get("callSyncEnabled")?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        }.getOrNull()
        if (v == null) WbResult.Error("响应解析失败：$body")
        else WbResult.Success(v)
    }

    /** 一键开启线上通话同步开关（PUT /api/settings/call-sync {"enabled":true}） */
    suspend fun setCallSync(enabled: Boolean): WbResult<Unit> = withContext(Dispatchers.IO) {
        val resp = put("/settings/call-sync", "{\"enabled\":$enabled}")
            ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
        if (is2xx(resp)) WbResult.Success(Unit) else WbResult.Error(extractError(resp))
    }

    /** 分页拉取全部通话记录（需 call-sync 开关已开启，否则 403） */
    suspend fun fetchAllCalls(): WbResult<List<WbCall>> = withContext(Dispatchers.IO) {
        val all = mutableListOf<WbCall>()
        var page = 1
        while (true) {
            val body = get("/calls?page=$page&pageSize=$PAGE_SIZE")
                ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
            val r = runCatching { json.decodeFromString<WbCallPage>(body) }
                .getOrElse { return@withContext WbResult.Error("响应解析失败：${it.message}") }
            all += r.items
            if (r.items.size < PAGE_SIZE || all.size >= r.total) break
            page++
        }
        WbResult.Success(all)
    }

    /** 批量上传通话记录（POST /api/calls/batch，≤100 条/批；direction 只允许 in/out/missed，callDate 必须 YYYY-MM-DD HH:mm） */
    suspend fun createCallsBatch(items: List<WbCall>, batchMeta: WbBatchMeta = WbBatchMeta()): WbResult<WbBatchResponse> =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext WbResult.Success(WbBatchResponse())
            if (items.size > 100) return@withContext WbResult.Error("批量上传单次最多 100 条，请分批")
            val body = buildString {
                append("{\"batchName\":\"${esc(batchMeta.batchName)}\",")
                append("\"source\":\"${esc(batchMeta.source)}\",")
                append("\"deviceInfo\":\"${esc(batchMeta.deviceInfo)}\",")
                append("\"items\":[")
                append(items.joinToString(",") { buildCallJson(it) })
                append("]}")
            }
            val resp = post("/calls/batch", body)
                ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
            if (!is2xx(resp)) return@withContext WbResult.Error(extractError(resp))
            runCatching { json.decodeFromString<WbBatchResponse>(resp) }
                .map { WbResult.Success(it) as WbResult<WbBatchResponse> }
                .getOrElse { WbResult.Error("批量响应解析失败：${it.message}") }
        }

    /** 删除一条线上通话记录（DELETE /api/calls/:id） */
    suspend fun deleteCall(id: String): WbResult<Unit> = withContext(Dispatchers.IO) {
        val resp = delete("/calls/$id")
            ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
        if (is2xx(resp)) WbResult.Success(Unit) else WbResult.Error(extractError(resp))
    }

    private fun buildCallJson(c: WbCall): String = buildString {
        append("{")
        if (!c.contactId.isNullOrBlank()) append("\"contactId\":\"${esc(c.contactId)}\",")
        append("\"phone\":\"${esc(c.phone)}\",")
        append("\"direction\":\"${esc(c.direction)}\",")
        append("\"duration\":${c.duration},")
        if (!c.callDate.isNullOrBlank()) append("\"callDate\":\"${esc(c.callDate)}\"")
        if (!c.note.isNullOrBlank()) append(",\"note\":\"${esc(c.note)}\"")
        append("}")
    }

    /** 拉取线上模板：字段定义 + 分层 tiers + 身份/属性标签（GET /api/schema） */
    suspend fun fetchSchema(): WbResult<WbSchemaBundle> = withContext(Dispatchers.IO) {
        val body = get("/schema")
            ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val arr = runCatching {
            when (val el = json.parseToJsonElement(body)) {
                is JsonArray -> el
                is JsonObject -> el["items"] as? JsonArray ?: el["fields"] as? JsonArray ?: JsonArray(emptyList())
                else -> JsonArray(emptyList())
            }
        }.getOrNull() ?: JsonArray(emptyList())
        val list = arr.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                WbRemoteField(
                    key = o["key"]?.jsonPrimitive?.content.orEmpty(),
                    label = o["label"]?.jsonPrimitive?.content.orEmpty(),
                    type = o["type"]?.jsonPrimitive?.content.orEmpty(),
                    group = o["group"]?.jsonPrimitive?.content,
                    required = o["required"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    options = (o["options"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.content }.orEmpty(),
                    order = o["order"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                )
            }.getOrNull()
        }.filter { it.key.isNotBlank() }
        fun strList(k: String): List<String> =
            (root?.get(k) as? JsonArray)?.mapNotNull { it.jsonPrimitive.content }.orEmpty()
        WbResult.Success(
            WbSchemaBundle(
                fields = list,
                tiers = strList("tiers").ifEmpty { strList("levels") }.ifEmpty { listOf("S", "A", "B", "C", "D", "V", "U") },
                identityTags = strList("identityTags"),
                attributeTags = strList("attributeTags")
            )
        )
    }

    /** 推送自定义字段定义到线上模板（POST /api/schema/fields），线上模板即字段集 */
    suspend fun pushFieldDef(def: WbRemoteField): WbResult<Unit> = withContext(Dispatchers.IO) {
        val body = buildString {
            append("{\"key\":\"${esc(def.key)}\",")
            append("\"label\":\"${esc(def.label)}\",")
            append("\"type\":\"${esc(def.type)}\",")
            if (!def.group.isNullOrBlank()) append("\"group\":\"${esc(def.group)}\",")
            append("\"required\":${def.required},")
            if (def.options.isNotEmpty()) {
                append("\"options\":[${def.options.joinToString(",") { "\"${esc(it)}\"" }}],")
            }
            append("\"order\":${def.order}}")
        }
        val resp = post("/schema/fields", body)
            ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
        if (is2xx(resp)) WbResult.Success(Unit) else WbResult.Error(extractError(resp))
    }

    /**
     * 批量上传联系人（POST /api/contacts/batch）。
     * 单次 ≤100 条；externalId 幂等（同 externalId 重复上传直接返回已存在对象）；
     * 逐条独立处理，失败明细在 errors 中，不影响其余。
     * batchMeta 为云端「数据可追溯（批次）」规范：batchName/source/deviceInfo。
     */
    suspend fun createContactsBatch(items: List<WbContact>, batchMeta: WbBatchMeta = WbBatchMeta()): WbResult<WbBatchResponse> =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext WbResult.Success(WbBatchResponse())
            if (items.size > 100) return@withContext WbResult.Error("批量上传单次最多 100 条，请分批")
            val body = buildString {
                append("{\"batchName\":\"${esc(batchMeta.batchName)}\",")
                append("\"source\":\"${esc(batchMeta.source)}\",")
                append("\"deviceInfo\":\"${esc(batchMeta.deviceInfo)}\",")
                append("\"items\":[")
                append(items.joinToString(",") { buildContactJson(it) })
                append("]}")
            }
            val resp = post("/contacts/batch", body)
                ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
            if (!is2xx(resp)) return@withContext WbResult.Error(extractError(resp))
            runCatching { json.decodeFromString<WbBatchResponse>(resp) }
                .map { WbResult.Success(it) as WbResult<WbBatchResponse> }
                .getOrElse { WbResult.Error("批量响应解析失败：${it.message}") }
        }

    /** 批量上传跟进记录（POST /api/followups/batch），≤100 条/批；带批次信息 */
    suspend fun createFollowupsBatch(items: List<WbFollowup>, batchMeta: WbBatchMeta = WbBatchMeta()): WbResult<WbBatchResponse> =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext WbResult.Success(WbBatchResponse())
            if (items.size > 100) return@withContext WbResult.Error("批量上传单次最多 100 条，请分批")
            val body = buildString {
                append("{\"batchName\":\"${esc(batchMeta.batchName)}\",")
                append("\"source\":\"${esc(batchMeta.source)}\",")
                append("\"deviceInfo\":\"${esc(batchMeta.deviceInfo)}\",")
                append("\"items\":[")
                append(items.joinToString(",") { buildFollowupJson(it) })
                append("]}")
            }
            val resp = post("/followups/batch", body)
                ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
            if (!is2xx(resp)) return@withContext WbResult.Error(extractError(resp))
            runCatching { json.decodeFromString<WbBatchResponse>(resp) }
                .map { WbResult.Success(it) as WbResult<WbBatchResponse> }
                .getOrElse { WbResult.Error("批量响应解析失败：${it.message}") }
        }

    /**
     * 查询最近一次批次状态（GET /api/batches?limit=1）。
     * 返回 status（active / reverted / …）；无批次或接口不可用时返回 null。
     * 同步前检查：若上次批次被回滚（reverted），云端已撤销该批数据，不应立刻把本地数据再推回去。
     */
    suspend fun fetchLatestBatchStatus(): WbResult<String?> = withContext(Dispatchers.IO) {
        val body = get("/batches?limit=1")
            ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
        val status: String? = runCatching {
            val el = json.parseToJsonElement(body)
            val arr: JsonArray? = when (el) {
                is JsonArray -> el
                is JsonObject -> el["items"] as? JsonArray ?: el["batches"] as? JsonArray
                else -> null
            }
            arr?.firstOrNull()?.jsonObject?.get("status")?.jsonPrimitive?.content
        }.getOrNull()
        WbResult.Success(status)
    }

    // ---- 底层 HTTP ----
    private fun buildContactJson(c: WbContact): String {
        val parts = mutableListOf<String>()
        fun put(k: String, v: String?) {
            val value = v?.trim().orEmpty()
            if (value.isNotEmpty()) parts.add("\"$k\":\"${esc(value)}\"")
        }
        put("name", c.name)
        put("phone", c.phone)
        put("nickname", c.nickname)
        put("wechat", c.wechat)
        put("tier", c.tier)
        put("source", c.source)
        put("nextFollowupDate", c.nextFollowupDate)
        put("memo", c.memo)
        put("externalId", c.externalId)
        if (c.tagIds.isNotEmpty()) {
            parts.add("\"tagIds\":[${c.tagIds.joinToString(",") { "\"${esc(it)}\"" }}]")
        }
        if (c.customFields.isNotEmpty()) {
            val cf = c.customFields.entries.joinToString(",") { (k, v) -> "\"${esc(k)}\":\"${esc(v)}\"" }
            parts.add("\"customFields\":{$cf}")
        }
        return "{" + parts.joinToString(",") + "}"
    }

    private fun buildFollowupJson(f: WbFollowup): String = buildString {
        append("{")
        append("\"contactId\":\"${esc(f.contactId)}\",")
        append("\"content\":\"${esc(f.content)}\",")
        if (!f.followupType.isNullOrBlank()) append("\"followupType\":\"${esc(f.followupType)}\",")
        if (!f.followupDate.isNullOrBlank()) append("\"followupDate\":\"${esc(f.followupDate)}\",")
        if (!f.nextFollowupDate.isNullOrBlank()) append("\"nextFollowupDate\":\"${esc(f.nextFollowupDate)}\"")
        append("}")
    }

    private fun esc(s: String): String = buildString(s.length) {
        s.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    private fun get(path: String): String? = request("GET", path, null)
    private fun post(path: String, body: String): String? = request("POST", path, body)
    private fun put(path: String, body: String): String? = request("PUT", path, body)
    private fun delete(path: String): String? = request("DELETE", path, null)

    private fun request(method: String, path: String, body: String?): String? {
        val key = apiKeyStore.load() ?: return null
        // 第一通道：URL 参数认证（线上官方要求：公网经平台网关时自定义请求头会被剥离，?api_key= 才可靠）
        var resp = execute(method, path, body, key, key)
        // 兜底：query 认证失败时试请求头（本地直连或旧版服务端）
        if (resp != null && isAuthFailure(resp)) {
            resp = execute(method, path, body, key, null)
        }
        return resp
    }

    private fun execute(method: String, path: String, body: String?, key: String, queryKey: String?): String? {
        val sep = if (path.contains("?")) "&" else "?"
        val url = baseUrl() + path + if (queryKey != null) "$sep" + "api_key=$queryKey" else ""
        val b = Request.Builder()
            .url(url)
            // 双头兼容：X-API-Key（早期约定）+ Authorization: Bearer（线上官方推荐）
            .header("X-API-Key", key)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return runCatching {
            client.newCall(b).execute().use { resp ->
                if (!resp.isSuccessful) {
                    resp.body?.string()?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}"
                } else {
                    resp.body?.string()
                }
            }
        }.getOrNull()
    }

    /** 判断响应是否为认证失败（未登录/会话过期/UNAUTHORIZED），用于降级重试 */
    private fun isAuthFailure(body: String): Boolean =
        body.contains("未登录") || body.contains("会话已过期") ||
            body.contains("UNAUTHORIZED") || body.startsWith("HTTP 401")

    private fun is2xx(body: String): Boolean = runCatching {
        val el = json.parseToJsonElement(body)
        val code = (el as? JsonObject)?.get("code")?.jsonPrimitive?.content?.toIntOrNull()
        code == null || code in 200..299
    }.getOrDefault(true)

    private fun extractError(body: String): String {
        val msg = runCatching {
            val el = json.parseToJsonElement(body)
            (el as? JsonObject)?.get("message")?.jsonPrimitive?.content
                ?: (el as? JsonObject)?.get("error")?.jsonPrimitive?.content
        }.getOrNull()
        return msg?.takeIf { it.isNotBlank() } ?: "服务端返回错误：${body.take(120)}"
    }
}
