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
    val memo: String? = null
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
        val body = buildString {
            append("{")
            append("\"contactId\":\"${esc(f.contactId)}\",")
            append("\"content\":\"${esc(f.content)}\",")
            if (!f.followupType.isNullOrBlank()) append("\"followupType\":\"${esc(f.followupType)}\",")
            if (!f.followupDate.isNullOrBlank()) append("\"followupDate\":\"${esc(f.followupDate)}\",")
            if (!f.nextFollowupDate.isNullOrBlank()) append("\"nextFollowupDate\":\"${esc(f.nextFollowupDate)}\"")
            append("}")
        }
        val resp = post("/followups", body)
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
        if (c.tagIds.isNotEmpty()) {
            parts.add("\"tagIds\":[${c.tagIds.joinToString(",") { "\"${esc(it)}\"" }}]")
        }
        return "{" + parts.joinToString(",") + "}"
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

    private fun request(method: String, path: String, body: String?): String? {
        val key = apiKeyStore.load() ?: return null
        val b = Request.Builder()
            .url(baseUrl() + path)
            .header("X-API-Key", key)
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
