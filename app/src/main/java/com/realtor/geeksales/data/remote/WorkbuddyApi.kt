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
    @SerialName("externalId") val externalId: String? = null,
    /** 服务端维护的时间戳（ISO-8601）：用于智能合并时对比“本地改动是否新于云端” */
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null
)

/** 线上模板元数据：tiers（分层）/ identityTags（身份标签）/ attributeTags（属性标签），驱动 App 筛选与标签选择器 */
data class WbSchemaBundle(
    val fields: List<WbRemoteField> = emptyList(),
    val tiers: List<String> = listOf("S", "A", "B", "C", "D", "V", "U"),
    val identityTags: List<String> = emptyList(),
    val attributeTags: List<String> = emptyList(),
    /** 分层中文语义（S→成交高价值…），模板可覆盖；空则 App 用默认语义 */
    val tierLabels: Map<String, String> = emptyMap()
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

    /**
     * 修改 API 基础地址（平台迁移时使用）。
     * 保存时自动清洗：去首尾空白、去尾部 ;/空格等脏字符；若路径不含 /api 自动补上
     * （线上固定 /api 前缀，用户通常只换域名；补 /api 可避免"提示成功但线上无数据"）。
     */
    fun setBaseUrl(url: String) {
        var cleaned = url.trim().trimEnd('/', ';', ' ', '，', '；')
        // 只保留协议+域名部分再拼 /api，防止粘入多余路径或参数
        val m = Regex("^(https?://[^/?#]+)").find(cleaned)
        val host = m?.groupValues?.get(1) ?: cleaned
        cleaned = host + if (host.endsWith("/api")) "" else "/api"
        prefs.edit().putString(KEY_BASE_URL, cleaned).apply()
    }

    /** 恢复默认地址（知行朋友圈官方网关） */
    fun resetBaseUrl() {
        prefs.edit().remove(KEY_BASE_URL).apply()
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
            .getOrElse { WbResult.Error("响应解析失败（若内容为网页请检查服务器地址是否缺少 /api）：${it.message}") }
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
            // 线上 /api/followups 返回裸数组（[]），与 contacts/tags 的 {"items":[...]} 不同：兼容两种格式
            val r = runCatching {
                val pageObj = runCatching { json.decodeFromString<WbFollowupPage>(body) }.getOrNull()
                if (pageObj != null) {
                    pageObj
                } else {
                    val arr = json.decodeFromString<List<WbFollowup>>(body)
                    WbFollowupPage(items = arr, total = arr.size, page = 1, pageSize = arr.size)
                }
            }.getOrElse { return@withContext WbResult.Error("响应解析失败（若内容为网页请检查服务器地址是否缺少 /api）：${it.message}") }
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
                .getOrElse { return@withContext WbResult.Error("响应解析失败（若内容为网页请检查服务器地址是否缺少 /api）：${it.message}") }
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

    /**
     * 批量推送短信（POST /api/messages/batch，单次≤100，逐条独立：单条失败不影响其余）。
     * 与通话/联系人一致的批次语义：线上自动归入批次，可时光机回溯。
     */
    suspend fun createMessagesBatch(items: List<WbMessage>, batchMeta: WbBatchMeta = WbBatchMeta()): WbResult<WbBatchResponse> =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext WbResult.Success(WbBatchResponse())
            if (items.size > 100) return@withContext WbResult.Error("批量上传单次最多 100 条，请分批")
            val body = buildString {
                append("{\"batchName\":\"${esc(batchMeta.batchName)}\",")
                append("\"source\":\"${esc(batchMeta.source)}\",")
                append("\"deviceInfo\":\"${esc(batchMeta.deviceInfo)}\",")
                append("\"items\":[")
                append(items.joinToString(",") { m ->
                    buildString {
                        append("{")
                        if (!m.contactId.isNullOrBlank()) append("\"contactId\":\"${esc(m.contactId)}\",")
                        append("\"phone\":\"${esc(m.phone)}\",")
                        append("\"body\":\"${esc(m.body)}\",")
                        append("\"direction\":\"${esc(m.direction)}\"")
                        // messageDate 为最后可选字段：前置逗号、不带尾逗号，JSON 永远合法
                        if (!m.messageDate.isNullOrBlank()) append(",\"messageDate\":\"${esc(m.messageDate)}\"")
                        append("}")
                    }
                })
                append("]}")
            }
            val resp = post("/messages/batch", body)
                ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
            if (!is2xx(resp)) return@withContext WbResult.Error(extractError(resp))
            runCatching { json.decodeFromString<WbBatchResponse>(resp) }
                .map { WbResult.Success(it) as WbResult<WbBatchResponse> }
                .getOrElse { WbResult.Error("批量响应解析失败（若内容为网页请检查服务器地址是否缺少 /api）：${it.message}") }
        }

    /** 查询线上短信同步开关；false 时 messages 接口会返回 403，应提示用户先在网页开启 */
    suspend fun smsSyncEnabled(): WbResult<Boolean> = withContext(Dispatchers.IO) {
        val body = get("/settings/sms-sync")
            ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
        val v = runCatching {
            val el = json.parseToJsonElement(body)
            (el as? JsonObject)?.get("smsSyncEnabled")?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        }.getOrNull()
        if (v == null) WbResult.Error("响应解析失败（若内容为网页请检查服务器地址是否缺少 /api）：$body")
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
        if (v == null) WbResult.Error("响应解析失败（若内容为网页请检查服务器地址是否缺少 /api）：$body")
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
                .getOrElse { return@withContext WbResult.Error("响应解析失败（若内容为网页请检查服务器地址是否缺少 /api）：${it.message}") }
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
                .getOrElse { WbResult.Error("批量响应解析失败（若内容为网页请检查服务器地址是否缺少 /api）：${it.message}") }
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

    /**
     * 拉取线上模板：分层 tiers + 语义 + 身份/属性标签（GET /api/templates）。
     * 线上返回模板数组（房产/保险/微商/社交等），默认取房产模板 preset-real-estate；
     * 模板换行业时在此切换（后续可在数据页提供模板选择器）。
     */
    suspend fun fetchSchema(): WbResult<WbSchemaBundle> = withContext(Dispatchers.IO) {
        val body = get("/templates")
            ?: return@withContext WbResult.Error("网络请求失败或未配置 API Key")
        val arr = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
            ?: return@withContext WbResult.Error("模板响应解析失败（请确认线上已开放 /api/templates）")
        // 优先房产模板；不存在则取第一个
        val target = arr.firstOrNull {
            (it as? JsonObject)?.get("id")?.jsonPrimitive?.content == "preset-real-estate"
        } ?: arr.firstOrNull() ?: return@withContext WbResult.Success(WbSchemaBundle())
        val o = target.jsonObject
        fun strList(k: String): List<String> =
            (o.get(k) as? JsonArray)?.mapNotNull { it.jsonPrimitive.content }.orEmpty()
        // 线上 tiers 为对象数组 [{value,label,description,color}]：取 value 列表 + description 语义
        val tiersArr = (o.get("tiers") as? JsonArray)?.mapNotNull { el ->
            runCatching {
                val jo = el.jsonObject
                val v = jo["value"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@runCatching null
                val desc = jo["description"]?.jsonPrimitive?.content
                    ?: jo["label"]?.jsonPrimitive?.content ?: v
                v to desc
            }.getOrNull()
        }.orEmpty()
        val tiers = tiersArr.map { it.first }
        val tierLabels = tiersArr.filter { it.second.isNotBlank() }.toMap()
        // 线上标签为对象数组 [{name,color}]：取 name
        fun tagList(k: String): List<String> =
            (o.get(k) as? JsonArray)?.mapNotNull { el ->
                (el as? JsonObject)?.get("name")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            }.orEmpty()
        // 线上模板字段 fields：[{key,label,type,options,group,required,order}] → App 扩展列，随模板动态
        val fields = (o.get("fields") as? JsonArray)?.mapNotNull { el ->
            runCatching {
                val jo = el.jsonObject
                val key = jo["key"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@runCatching null
                val label = jo["label"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: key
                val type = jo["type"]?.jsonPrimitive?.content ?: "text"
                val group = jo["group"]?.jsonPrimitive?.content
                val required = runCatching { jo["required"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() }.getOrNull() ?: false
                val order = runCatching { (jo["sortOrder"] ?: jo["order"])?.jsonPrimitive?.content?.toIntOrNull() }.getOrNull() ?: 0
                val options = (jo["options"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.content }.orEmpty()
                WbRemoteField(key = key, label = label, type = type, group = group, required = required, options = options, order = order)
            }.getOrNull()
        }.orEmpty()
        WbResult.Success(
            WbSchemaBundle(
                fields = fields,
                tiers = tiers.ifEmpty { listOf("S", "A", "B", "C", "D", "V", "U") },
                identityTags = tagList("identityTags"),
                attributeTags = tagList("attributeTags"),
                tierLabels = tierLabels
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
                .getOrElse { WbResult.Error("批量响应解析失败（若内容为网页请检查服务器地址是否缺少 /api）：${it.message}") }
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
                .getOrElse { WbResult.Error("批量响应解析失败（若内容为网页请检查服务器地址是否缺少 /api）：${it.message}") }
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
        // 认证只走 URL 参数（?api_key=）：线上经平台网关时自定义请求头会被剥离，
        // 放请求头（X-API-Key / Authorization）一律 401，纯 URL 参数才可靠
        return execute(method, path, body, key)
    }

    private fun execute(method: String, path: String, body: String?, key: String): String? {
        val sep = if (path.contains("?")) "&" else "?"
        val url = baseUrl() + path + "$sep" + "api_key=$key"
        val b = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return runCatching {
            client.newCall(b).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // 非 2xx：前缀标记状态码，is2xx 据此可靠识别失败；extractError 给出明确语义
                    "HTTP ${resp.code}:${resp.body?.string().orEmpty().take(160)}"
                } else {
                    resp.body?.string()
                }
            }
        }.getOrNull()
    }

    /**
     * 判断响应是否真的是成功 JSON：
     * - 非 2xx（"HTTP <code>:" 前缀）→ 失败
     * - 非 JSON（HTML 页面等，常见于服务器地址填错打到网页首页）→ 失败
     * - JSON 且 code 为空或 2xx → 成功
     */
    private fun is2xx(body: String): Boolean {
        if (body.startsWith("HTTP ")) return false
        return runCatching {
            val el = json.parseToJsonElement(body)
            if (el is JsonObject) {
                val code = el.get("code")?.jsonPrimitive?.content?.toIntOrNull()
                code == null || code in 200..299
            } else true
        }.getOrDefault(false)
    }

    private fun extractError(body: String): String {
        // 非 2xx（HTTP <code>:<body> 前缀）：按状态码给出明确语义，不无脑报成功/笼统报错
        if (body.startsWith("HTTP ")) {
            val status = body.substring(0, body.indexOf(':'))
            val raw = body.substringAfter(':').trim().take(160)
            val serverMsg = runCatching {
                val el = json.parseToJsonElement(raw)
                (el as? JsonObject)?.get("message")?.jsonPrimitive?.content
                    ?: (el as? JsonObject)?.get("error")?.jsonPrimitive?.content
            }.getOrNull()
            return when (status) {
                "HTTP 401" -> "同步失败：API Key 无效或未授权（请检查密钥；云端按账号隔离，密钥与账号一对一）"
                "HTTP 403" -> "同步失败：云端该功能未开启或无权访问（请在网页「API 接入」页开启对应同步开关）"
                "HTTP 404" -> "同步失败：云端接口不存在（${serverMsg ?: "请联系云端补齐接口"}）"
                else -> "同步失败（HTTP ${status.removePrefix("HTTP ")}）：${serverMsg?.takeIf { it.isNotBlank() } ?: raw.ifBlank { "服务端错误" }}"
            }
        }
        // 2xx 但业务返回错误 JSON
        val msg = runCatching {
            val el = json.parseToJsonElement(body)
            (el as? JsonObject)?.get("message")?.jsonPrimitive?.content
                ?: (el as? JsonObject)?.get("error")?.jsonPrimitive?.content
        }.getOrNull()
        return msg?.takeIf { it.isNotBlank() } ?: "服务端返回错误：${body.take(120)}"
    }
}
