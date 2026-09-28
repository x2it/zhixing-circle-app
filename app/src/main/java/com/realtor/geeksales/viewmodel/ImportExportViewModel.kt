package com.realtor.geeksales.viewmodel

import android.content.ContentProviderOperation
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.realtor.geeksales.data.db.Customer
import com.realtor.geeksales.data.db.FollowResult
import com.realtor.geeksales.data.db.IntentLevel
import com.realtor.geeksales.data.importexport.CsvManager
import com.realtor.geeksales.data.importexport.ExcelManager
import com.realtor.geeksales.data.importexport.ImportReport
import com.realtor.geeksales.data.importexport.SmsExporter
import com.realtor.geeksales.data.importexport.SnapshotManager
import com.realtor.geeksales.data.remote.ApiKeyStore
import com.realtor.geeksales.data.remote.WbContact
import com.realtor.geeksales.data.remote.WbResult
import com.realtor.geeksales.data.remote.WorkbuddyApi
import com.realtor.geeksales.data.remote.WbMessage
import com.realtor.geeksales.data.remote.WbRemoteField
import com.realtor.geeksales.data.repo.CustomerRepository
import com.realtor.geeksales.data.schema.FieldDef
import com.realtor.geeksales.data.schema.SchemaStore
import com.realtor.geeksales.data.schema.TemplateMeta
import com.realtor.geeksales.util.Formatter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class IOStatus(
    val running: Boolean = false,
    val message: String = "",
    val report: ImportReport? = null,
    val exportCount: Int? = null,
    /** 最近一次同步的明细（知行朋友圈） */
    val syncSummary: String? = null
)

/** 知行同步结果汇总 */
data class WbSyncReport(
    val imported: Int = 0,
    val skippedDup: Int = 0,
    val created: Int = 0,
    val updated: Int = 0,
    val failed: Int = 0,
    val backupFile: String? = null
)

/** 同步冲突策略（用户可选） */
enum class SyncMode(val label: String, val desc: String) {
    SMART("智能合并", "两端改动都保留，同一字段冲突时以本地最新为准"),
    CLOUD_FIRST("云端优先", "冲突以线上数据为准，覆盖本地"),
    LOCAL_FIRST("本地优先", "冲突以本地数据为准，覆盖线上")
}

@HiltViewModel
class ImportExportViewModel @Inject constructor(
    private val csv: CsvManager,
    private val excel: ExcelManager,
    private val smsExporter: SmsExporter,
    private val snapshot: SnapshotManager,
    private val repo: CustomerRepository,
    private val wbApi: WorkbuddyApi,
    private val apiKeyStore: ApiKeyStore,
    private val schemaStore: SchemaStore,
    @ApplicationContext private val ctx: Context
) : ViewModel() {

    private val _status = MutableStateFlow(IOStatus(message = "idle"))
    val status: StateFlow<IOStatus> = _status

    // ---- 线上模板（schema）：拉取 / 自定义字段 ----

    /** 拉取线上模板（字段定义 + 分层 tiers + 身份/属性标签）并合并进本地（自动适配：线上加字段→App 跟随） */
    fun pullSchema() = doRun("拉取线上模板中…") {
        withContext(Dispatchers.IO) {
            if (apiKeyStore.load().isNullOrBlank()) {
                _status.value = IOStatus(message = "请先配置知行朋友圈 API Key")
                return@withContext
            }
            when (val r = wbApi.fetchSchema()) {
                is WbResult.Error -> _status.value = IOStatus(message = "拉取模板失败：${r.message}（线上需已开放 /api/schema 接口）")
                is WbResult.Success -> {
                    val bundle = r.data
                    // 模板元数据（tiers/标签）始终落库，驱动筛选与标签选择器
                    val oldMeta = schemaStore.meta()
                    schemaStore.saveMeta(
                        TemplateMeta(
                            tiers = bundle.tiers.ifEmpty { oldMeta.tiers },
                            identityTags = bundle.identityTags.ifEmpty { oldMeta.identityTags },
                            attributeTags = bundle.attributeTags.ifEmpty { oldMeta.attributeTags }
                        )
                    )
                    if (bundle.fields.isEmpty()) {
                        _status.value = IOStatus(message = "线上模板已同步（分层 ${bundle.tiers.joinToString("/")}；标签 ${bundle.identityTags.size + bundle.attributeTags.size} 个）")
                        return@withContext
                    }
                    val remote = bundle.fields.map {
                        FieldDef(
                            key = it.key,
                            label = it.label.ifBlank { it.key },
                            type = it.type.ifBlank { "text" },
                            group = it.group?.ifBlank { null } ?: "其他",
                            required = it.required,
                            options = it.options,
                            order = it.order,
                            builtin = false
                        )
                    }
                    val merged = schemaStore.mergeRemote(remote)
                    _status.value = IOStatus(message = "模板已同步：${merged.size} 个字段 · 分层 ${bundle.tiers.joinToString("/")} · 标签 ${bundle.identityTags.size + bundle.attributeTags.size} 个")
                }
            }
        }
    }

    /** 当前生效模板 */
    fun currentSchema(): List<FieldDef> = schemaStore.current()

    /** 添加本地自定义字段并推送线上（线上失败仅提示，本地仍生效——离线可用） */
    fun addCustomField(key: String, label: String, type: String, options: List<String>) = doRun("添加自定义字段中…") {
        withContext(Dispatchers.IO) {
            val k = key.trim().lowercase(Locale.ROOT)
            if (k.isBlank() || label.isBlank()) {
                _status.value = IOStatus(message = "字段 key 与显示名不能为空")
                return@withContext
            }
            val cur = schemaStore.current()
            if (cur.any { it.key == k }) {
                _status.value = IOStatus(message = "字段 key「$k」已存在，请换一个（避免与现有字段冲突）")
                return@withContext
            }
            val def = FieldDef(
                key = k, label = label.trim(), type = type.trim().ifBlank { "text" },
                group = "自定义", options = options.filter { it.isNotBlank() }.distinct(),
                order = (cur.maxOfOrNull { it.order } ?: 0) + 10, builtin = false
            )
            schemaStore.addLocalField(def)
            // 推送到线上模板（可选成功：本地离线也能用）
            if (apiKeyStore.load().isNullOrBlank()) {
                _status.value = IOStatus(message = "自定义字段「$label」已添加（未配置 API Key，未推送线上）")
                return@withContext
            }
            when (val r = wbApi.pushFieldDef(
                WbRemoteField(
                    key = def.key, label = def.label, type = def.type,
                    group = def.group, required = def.required, options = def.options, order = def.order
                )
            )) {
                is WbResult.Success -> _status.value = IOStatus(message = "自定义字段「$label」已添加并同步到线上模板")
                is WbResult.Error -> _status.value = IOStatus(message = "自定义字段「$label」已添加本地，推送线上失败：${r.message}")
            }
        }
    }

    /** 移除本地自定义字段（仅本地；已同步线上的需在线上删除） */
    fun removeCustomField(key: String) {
        schemaStore.removeLocalField(key)
        _status.value = IOStatus(message = "已移除本地自定义字段（如线上仍有，请在网页删除）")
    }

    fun importCsv(uri: Uri) = doRun("CSV 导入中…") {
        val r = csv.importFrom(uri)
        _status.value = if (r.error != null) {
            IOStatus(message = "CSV 导入失败：${r.error}", report = r)
        } else {
            IOStatus(message = "CSV 导入完成", report = r)
        }
    }
    fun importXlsx(uri: Uri) = doRun("Excel 导入中…") {
        val r = excel.importFrom(uri)
        _status.value = if (r.error != null) {
            IOStatus(message = "Excel 导入失败：${r.error}", report = r)
        } else {
            IOStatus(message = "Excel 导入完成", report = r)
        }
    }
    fun exportCsv(uri: Uri) = doRun("CSV 导出中…") {
        val n = csv.exportTo(uri)
        _status.value = IOStatus(message = "CSV 导出完成", exportCount = n)
    }
    fun exportXlsx(uri: Uri) = doRun("Excel 导出中…") {
        val n = excel.exportTo(uri)
        _status.value = IOStatus(message = "Excel 导出完成", exportCount = n)
    }
    fun templateXlsx(uri: Uri) = doRun("生成模板中…") {
        excel.templateTo(uri)
        _status.value = IOStatus(message = "模板已生成")
    }

    fun importContacts() = doRun("通讯录导入中…") {
        withContext(Dispatchers.IO) {
            val loaded = loadContactsFromSystem()
            if (loaded.isEmpty()) {
                _status.value = IOStatus(message = "通讯录无有效联系人")
                return@withContext
            }
            val contacts = loaded.map { it.first }
            var dup = 0
            val newOnes = contacts.filter { c ->
                val existing = repo.getByPhoneNormalized(c.phoneNormalized)
                if (existing != null) { dup++; false } else true
            }
            if (newOnes.isNotEmpty()) repo.upsertAll(newOnes)
            // 群组映射为标签（仅对新导入客户，避免重复挂标）
            newOnes.forEach { c ->
                val groups = loaded.firstOrNull { it.first.phoneNormalized == c.phoneNormalized }?.second
                if (!groups.isNullOrEmpty()) repo.applyTags(c.id, groups)
            }
            _status.value = IOStatus(
                message = "通讯录导入完成",
                report = ImportReport(
                    total = contacts.size,
                    success = newOnes.size,
                    duplicated = dup,
                    invalid = contacts.size - newOnes.size - dup
                )
            )
        }
    }

    fun exportContacts() = doRun("导出到通讯录中…") {
        withContext(Dispatchers.IO) {
            val all = repo.getAll()
            if (all.isEmpty()) {
                _status.value = IOStatus(message = "无客户可导出")
                return@withContext
            }
            // 通讯录已存在的号码集合，避免重复创建联系人
            val existingPhones = HashSet<String>()
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    existingPhones.add(Formatter.normalizePhone(c.getString(0).orEmpty()))
                }
            }
            val toExport = all.filter { Formatter.normalizePhone(it.phone) !in existingPhones }
            if (toExport.isEmpty()) {
                _status.value = IOStatus(message = "通讯录已有全部客户，无需重复导出", exportCount = 0)
                return@withContext
            }
            val ops = ArrayList<ContentProviderOperation>()
            toExport.forEach { c ->
                val rowId = ops.size
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                        .withValue(ContactsContract.RawContacts.STARRED, 0)
                        .build()
                )
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rowId)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, c.name)
                        .build()
                )
                // 手机（主）+ 备用电话
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rowId)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, c.phone)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                        .build()
                )
                if (!c.phone2.isNullOrBlank()) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rowId)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, c.phone2)
                            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_HOME)
                            .build()
                    )
                }
                // 邮箱
                if (!c.email.isNullOrBlank()) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rowId)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, c.email)
                            .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                            .build()
                    )
                }
                // 公司 + 职位
                if (!c.company.isNullOrBlank() || !c.jobTitle.isNullOrBlank()) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rowId)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, c.company.orEmpty())
                            .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, c.jobTitle.orEmpty())
                            .build()
                    )
                }
                // 地址
                if (!c.address.isNullOrBlank()) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rowId)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, c.address)
                            .withValue(ContactsContract.CommonDataKinds.StructuredPostal.TYPE, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME)
                            .build()
                    )
                }
                // 昵称
                if (!c.nickname.isNullOrBlank()) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rowId)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Nickname.NAME, c.nickname)
                            .build()
                    )
                }
                // 网站
                if (!c.website.isNullOrBlank()) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rowId)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Website.URL, c.website)
                            .build()
                    )
                }
                // 生日（Event.TYPE_BIRTHDAY）
                if (!c.birthday.isNullOrBlank()) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rowId)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Event.START_DATE, c.birthday)
                            .withValue(ContactsContract.CommonDataKinds.Event.TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)
                            .build()
                    )
                }
                // 即时消息（微信，自定义协议）
                if (!c.im.isNullOrBlank()) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rowId)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Im.DATA1, c.im)
                            .withValue(ContactsContract.CommonDataKinds.Im.PROTOCOL, ContactsContract.CommonDataKinds.Im.PROTOCOL_CUSTOM)
                            .withValue(ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL, "微信")
                            .build()
                    )
                }
            }
            ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            _status.value = IOStatus(
                message = "导出到通讯录完成（跳过已有号码 ${all.size - toExport.size} 条）",
                exportCount = toExport.size
            )
        }
    }

    /**
     * 从系统通讯录读取联系人，映射到 Customer 全字段（含群组）。
     * 单次查询 Data 表聚合所有属性，避免逐联系人多次查询。
     */
    private fun loadContactsFromSystem(): List<Pair<Customer, List<String>>> {
        val resolver = ctx.contentResolver

        // 群组名映射：GROUP_ROW_ID -> 群名
        val groupNames = HashMap<Long, String>()
        resolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE),
            "${ContactsContract.Groups.DELETED} = 0",
            null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val title = c.getString(1)?.trim().orEmpty()
                if (title.isNotBlank()) groupNames[id] = title
            }
        }

        // 按联系人聚合：CONTACT_ID -> 累积的字段
        data class Acc(
            var name: String = "",
            val phones: MutableList<Pair<String, Int>> = mutableListOf(),
            var email: String? = null,
            var company: String? = null,
            var jobTitle: String? = null,
            var address: String? = null,
            var nickname: String? = null,
            var website: String? = null,
            var birthday: String? = null,
            var im: String? = null,
            val groups: MutableList<String> = mutableListOf()
        )
        val accMap = HashMap<Long, Acc>()

        val dataProjection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4
        )
        resolver.query(ContactsContract.Data.CONTENT_URI, dataProjection, null, null, null)?.use { c ->
            val ci = c.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val mi = c.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            val d1 = c.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
            val d2 = c.getColumnIndexOrThrow(ContactsContract.Data.DATA2)
            val d4 = c.getColumnIndexOrThrow(ContactsContract.Data.DATA4)
            while (c.moveToNext()) {
                val contactId = c.getLong(ci)
                val mime = c.getString(mi) ?: continue
                val a = accMap.getOrPut(contactId) { Acc() }
                when (mime) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                        val num = c.getString(d1)?.trim().orEmpty()
                        if (num.isNotBlank()) a.phones.add(num to c.getInt(d2))
                    }
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        if (a.name.isBlank()) a.name = c.getString(d1)?.trim().orEmpty()
                    }
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                        if (a.email.isNullOrBlank()) a.email = c.getString(d1)?.trim()
                    }
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        if (a.company.isNullOrBlank()) a.company = c.getString(d1)?.trim()
                        if (a.jobTitle.isNullOrBlank()) a.jobTitle = c.getString(d4)?.trim()
                    }
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                        if (a.address.isNullOrBlank()) a.address = c.getString(d1)?.trim()
                    }
                    ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE -> {
                        if (a.nickname.isNullOrBlank()) a.nickname = c.getString(d1)?.trim()
                    }
                    ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE -> {
                        if (a.website.isNullOrBlank()) a.website = c.getString(d1)?.trim()
                    }
                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> {
                        // 仅取生日（TYPE_BIRTHDAY=3）
                        if (c.getInt(d2) == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY && a.birthday.isNullOrBlank()) {
                            a.birthday = c.getString(d1)?.trim()
                        }
                    }
                    ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE -> {
                        if (a.im.isNullOrBlank()) a.im = c.getString(d1)?.trim()
                    }
                    ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE -> {
                        val gid = c.getLong(d1)
                        groupNames[gid]?.let { g -> if (!a.groups.contains(g)) a.groups.add(g) }
                    }
                }
            }
        }

        val result = mutableListOf<Pair<Customer, List<String>>>()
        val seenPhones = HashSet<String>()
        accMap.forEach { (_, a) ->
            if (a.name.isBlank()) return@forEach
            // 主号码：优先 TYPE_MOBILE，否则第一个号码
            val primary = a.phones.firstOrNull { it.second == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE }
                ?: a.phones.firstOrNull()
            val rawPhone = primary?.first ?: return@forEach
            val normalized = Formatter.normalizePhone(rawPhone)
            if (!Formatter.isValidCnPhone(normalized)) return@forEach
            if (!seenPhones.add(normalized)) return@forEach
            // 备用电话：第二个号码
            val phone2 = a.phones.asSequence()
                .filter { Formatter.normalizePhone(it.first) != normalized }
                .map { it.first }
                .firstOrNull()
            result.add(
                Customer(
                    name = a.name,
                    phone = rawPhone,
                    phoneNormalized = normalized,
                    phone2 = phone2,
                    email = a.email,
                    company = a.company,
                    jobTitle = a.jobTitle,
                    address = a.address,
                    nickname = a.nickname,
                    website = a.website,
                    birthday = a.birthday,
                    im = a.im,
                    source = "通讯录导入",
                    intentLevel = IntentLevel.U
                ) to a.groups.toList()
            )
        }
        return result
    }

    fun clearAll() = doRun("清空数据中…") {
        withContext(Dispatchers.IO) {
            val n = repo.countAll()
            repo.deleteAll()
            _status.value = IOStatus(message = "已清空 $n 条客户数据")
        }
    }

    // ================= 知行朋友圈（第三方通讯录）双向同步 =================
    // 设计原则：
    //  1) 每次同步前自动全量备份本地客户到「下载/TMA备份」目录（XLSX），防误覆盖；
    //  2) 导入按号码查重，默认跳过已存在客户，绝不覆盖本地；
    //  3) 导出按号码匹配线上联系人：存在则更新、不存在则新建，绝不删除；
    //  4) 云端 = 异地备份：换机后配好 API Key 即可一键拉回全部客户与跟进历史。

    /** API Key 配置 */
    fun hasApiKey(): Boolean = !apiKeyStore.load().isNullOrBlank()
    fun saveApiKey(key: String): Boolean = apiKeyStore.save(key.trim())
    fun clearApiKey() = apiKeyStore.clear()

    // ---- 同步模式（prefs 持久化）----
    private val prefs = ctx.getSharedPreferences("tma_prefs", Context.MODE_PRIVATE)

    fun syncMode(): SyncMode = runCatching {
        SyncMode.valueOf(prefs.getString("wb_sync_mode", SyncMode.SMART.name) ?: SyncMode.SMART.name)
    }.getOrDefault(SyncMode.SMART)

    fun setSyncMode(mode: SyncMode) = prefs.edit().putString("wb_sync_mode", mode.name).apply()

    // ---- 服务器地址（平台迁移时切换，不写死）----
    fun baseUrl(): String = wbApi.baseUrl()
    fun setBaseUrl(url: String) = wbApi.setBaseUrl(url)

    /** 自动备份：全字段 XLSX 写入系统下载目录（MediaStore），返回文件名 */
    suspend fun backupToDownloads(): String? = withContext(Dispatchers.IO) {
        val all = repo.getAll()
        if (all.isEmpty()) return@withContext null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        val display = "TMA备份-${stamp}.xlsx"
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, display)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TMA备份")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext null
        runCatching {
            resolver.openOutputStream(uri)?.use { excel.exportToStream(all, it) }
        }.onFailure {
            resolver.delete(uri, null, null)
            return@withContext null
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        display
    }

    /** 从知行朋友圈导入：联系人 + 标签 + 跟进历史（默认跳过重复，不覆盖本地） */
    fun importFromWorkbuddy() = doRun("知行同步：备份中…") {
        withContext(Dispatchers.IO) {
            if (apiKeyStore.load().isNullOrBlank()) {
                _status.value = IOStatus(message = "请先在「数据」页配置知行朋友圈 API Key")
                return@withContext
            }
            // 1) 先备份本地
            val backup = backupToDownloads()
            _status.value = IOStatus(running = true, message = "知行同步：拉取联系人…")
            // 2) 拉取线上联系人
            val contacts = when (val r = wbApi.fetchAllContacts()) {
                is WbResult.Error -> {
                    _status.value = IOStatus(message = "知行同步失败：${r.message}")
                    return@withContext
                }
                is WbResult.Success -> r.data
            }
            // 3) 按同步模式查重处理（externalId 幂等优先：本地记录 tma-<id> 与线上对应，改号也不重复创建）
            val mode = syncMode()
            var imported = 0
            var skipped = 0
            var conflicts = 0
            var overridden = 0
            val newIds = mutableListOf<Long>()
            val localByExtId = HashMap<String, Long>()
            repo.getAll().forEach { c -> if (c.id > 0L) localByExtId["tma-${c.id}"] = c.id }
            contacts.forEach { wb ->
                val phoneN = Formatter.normalizePhone(wb.phone)
                if (phoneN.isBlank() || !Formatter.isValidCnPhone(phoneN)) return@forEach
                val extLocalId = wb.externalId?.let { localByExtId[it] }
                val existing = extLocalId?.let { repo.getById(it) } ?: repo.getByPhoneNormalized(phoneN)
                if (existing != null) {
                    when (mode) {
                        // 本地优先：本地为准，跳过
                        SyncMode.LOCAL_FIRST -> skipped++
                        // 云端优先：线上覆盖本地（字段 + 标签 + 扩展字段 + 线上 id）
                        SyncMode.CLOUD_FIRST -> {
                            overridden++
                            // 防串场：线上号码若已被本地其他客户占用（改号场景），保留本地号码，其余字段仍以线上为准
                            val phoneOwner = repo.getByPhoneNormalized(phoneN)
                            val safePhone = phoneOwner != null && phoneOwner.id != existing.id
                            val merged = wbToCustomer(wb, if (safePhone) existing.phoneNormalized else phoneN).copy(
                                id = existing.id, createdAt = existing.createdAt,
                                phone = if (safePhone) existing.phone else wb.phone,
                                phoneNormalized = if (safePhone) existing.phoneNormalized else phoneN
                            )
                            repo.upsertAndGetId(merged)
                            if (wb.id.isNotBlank()) repo.updateWbContactId(existing.id, wb.id)
                            if (wb.customFields.isNotEmpty()) repo.putExtFields(existing.id, wb.customFields)
                            val tagNames = wb.tags.mapNotNull { it.name.takeIf { n -> n.isNotBlank() } }
                            if (tagNames.isNotEmpty()) {
                                repo.tagsOf(existing.id).filter { it !in tagNames }
                                    .forEach { stale -> repo.removeTag(existing.id, stale) }
                                repo.applyTags(existing.id, tagNames)
                            }
                        }
                        // 智能合并：字段级补空，冲突保留本地；扩展字段同样"本地空←线上非空"
                        SyncMode.SMART -> {
                            val merged = mergeWbIntoLocal(existing, wb)
                            if (merged != existing) {
                                repo.upsertAndGetId(merged)
                                if (wb.id.isNotBlank() && existing.wbContactId.isNullOrBlank()) {
                                    repo.updateWbContactId(existing.id, wb.id)
                                }
                            }
                            if (wb.customFields.isNotEmpty()) {
                                val localExt = repo.extFieldsOf(existing.id)
                                val mergedExt = HashMap(localExt)
                                wb.customFields.forEach { (k, v) ->
                                    if (localExt[k].isNullOrBlank() && v.isNotBlank()) mergedExt[k] = v
                                }
                                repo.putExtFields(existing.id, mergedExt)
                            }
                            val tagNames = wb.tags.mapNotNull { it.name.takeIf { n -> n.isNotBlank() } }
                            if (tagNames.isNotEmpty()) repo.applyTags(existing.id, tagNames)
                            if (hasFieldConflict(existing, wb)) conflicts++
                        }
                    }
                    return@forEach
                }
                val c = wbToCustomer(wb, phoneN)
                val id = repo.upsertAndGetId(c)
                if (id > 0) {
                    imported++
                    newIds.add(id)
                    if (wb.id.isNotBlank()) repo.updateWbContactId(id, wb.id)
                    if (wb.customFields.isNotEmpty()) repo.putExtFields(id, wb.customFields)
                    val tagNames = wb.tags.mapNotNull { it.name.takeIf { n -> n.isNotBlank() } }
                    if (tagNames.isNotEmpty()) repo.applyTags(id, tagNames)
                }
            }
            // 4) 拉取线上跟进历史并导入（按 contactId → 本地 wbContactId 匹配，去重）
            var followupsImported = 0
            when (val fr = wbApi.fetchAllFollowups()) {
                is WbResult.Error -> {
                    _status.value = IOStatus(
                        message = "联系人已同步，跟进历史失败：${fr.message}",
                        syncSummary = "导入 $imported 位联系人 / 覆盖 $overridden / 冲突保留 $conflicts / 跳过 $skipped，备份：$backup"
                    )
                    return@withContext
                }
                is WbResult.Success -> {
                    followupsImported = importRemoteFollowups(fr.data)
                }
            }
            _status.value = IOStatus(
                message = "知行同步完成",
                syncSummary = "导入 $imported 位联系人 / 覆盖 $overridden / 冲突保留 $conflicts / 跳过 $skipped，跟进记录 $followupsImported 条，备份：$backup"
            )
        }
    }

    /** 导出到知行朋友圈：联系人 + 标签 + 跟进历史（存在更新、不存在新建，不删远端） */
    fun exportToWorkbuddy() = doRun("知行同步：备份中…") {
        withContext(Dispatchers.IO) {
            if (apiKeyStore.load().isNullOrBlank()) {
                _status.value = IOStatus(message = "请先在「数据」页配置知行朋友圈 API Key")
                return@withContext
            }
            // 1) 先备份本地
            val backup = backupToDownloads()
            _status.value = IOStatus(running = true, message = "知行同步：拉取线上数据…")
            // 2) 拉取线上联系人（phone → id 索引）与标签（name → id 索引）
            val remoteByPhone = HashMap<String, WbContact>()
            when (val r = wbApi.fetchAllContacts()) {
                is WbResult.Error -> {
                    _status.value = IOStatus(message = "知行同步失败：${r.message}")
                    return@withContext
                }
                is WbResult.Success -> r.data.forEach {
                    val n = Formatter.normalizePhone(it.phone)
                    if (n.isNotBlank()) remoteByPhone[n] = it
                }
            }
            val tagIdByName = HashMap<String, String>()
            when (val r = wbApi.fetchAllTags()) {
                is WbResult.Error -> {
                    _status.value = IOStatus(message = "知行同步失败：${r.message}")
                    return@withContext
                }
                is WbResult.Success -> r.data.forEach { t ->
                    if (t.id.isNotBlank() && t.name.isNotBlank()) tagIdByName[t.name] = t.id
                }
            }
            // 3.5) 本地自定义标签自动同步到线上（防止导出丢标签）
            //     线上有而本地没有的标签会在导入时经 applyTags 自动创建（名字一致即对应）；
            //     反向（本地有而线上没有）在这里补建，保证标签双向不丢失
            var createdTags = 0
            var failedTags = 0
            repo.allTags().forEach { t ->
                if (t.name.isNotBlank() && !tagIdByName.containsKey(t.name)) {
                    when (val r = wbApi.createTag(t.name)) {
                        is WbResult.Success -> {
                            tagIdByName[t.name] = r.data.id
                            createdTags++
                        }
                        is WbResult.Error -> failedTags++
                    }
                }
            }
            if (createdTags > 0) _status.value = IOStatus(
                running = true,
                message = "已自动创建 $createdTags 个本地标签到知行朋友圈…"
            )
            // 3) 按同步模式导出联系人（新建走批量 /contacts/batch，externalId 幂等；更新走单条 PUT）
            val mode = syncMode()
            var created = 0
            var updated = 0
            var skipped = 0
            var failed = 0
            var noPhone = 0
            val localAll = repo.getAll()
            val toCreate = mutableListOf<Pair<Customer, com.realtor.geeksales.data.remote.WbContact>>()
            val toUpdate = mutableListOf<Triple<Customer, com.realtor.geeksales.data.remote.WbContact, String>>()
            localAll.forEach { c ->
                val phoneN = c.phoneNormalized
                if (phoneN.isBlank() || !Formatter.isValidCnPhone(phoneN)) {
                    noPhone++
                    return@forEach
                }
                val tagIds = repo.tagsOf(c.id).mapNotNull { tagIdByName[it] }
                val ext = repo.extFieldsOf(c.id)
                val wb = customerToWb(c, tagIds, ext)
                val remote = remoteByPhone[phoneN]
                when {
                    remote != null -> {
                        when (mode) {
                            // 云端优先：线上为准，本地改动不覆盖线上
                            SyncMode.CLOUD_FIRST -> skipped++
                            // 本地优先 / 智能合并：本地为准推送更新
                            else -> toUpdate.add(Triple(c, wb, remote.id))
                        }
                    }
                    else -> toCreate.add(c to wb)
                }
            }
            // 批量新建：≤100/批，externalId 幂等，逐条失败不阻塞
            toCreate.chunked(100).forEach { chunk ->
                when (val r = wbApi.createContactsBatch(chunk.map { it.second })) {
                    is WbResult.Success -> {
                        r.data.items.forEachIndexed { i, item ->
                            val local = chunk.getOrNull(i)?.first ?: return@forEachIndexed
                            if (item.id.isNotBlank()) {
                                created++
                                if (local.wbContactId != item.id) repo.updateWbContactId(local.id, item.id)
                            }
                        }
                        failed += r.data.errors.count { it.index in chunk.indices }
                    }
                    is WbResult.Error -> { failed += chunk.size }
                }
            }
            // 更新已有联系人（单条 PUT，线上无批量更新接口）
            toUpdate.forEach { (c, wb, remoteId) ->
                try {
                    if (wbApi.updateContact(remoteId, wb) is WbResult.Success) {
                        updated++
                        if (c.wbContactId.isNullOrBlank() || c.wbContactId != remoteId) {
                            repo.updateWbContactId(c.id, remoteId)
                        }
                    } else failed++
                } catch (t: Exception) { failed++ }
            }
            // 4) 推送跟进历史（通话登记），批量 /followups/batch，按线上 followups 去重
            var pushed = 0
            var fuFailed = 0
            val remoteFus = when (val fr = wbApi.fetchAllFollowups()) {
                is WbResult.Success -> fr.data
                else -> emptyList()
            }
            val remoteFuKeys = HashSet<String>()
            remoteFus.forEach { f ->
                remoteFuKeys.add("${f.contactId}|${f.content}|${f.followupDate}")
            }
            val customerById = HashMap<Long, Customer>()
            localAll.forEach { c -> customerById[c.id] = c }
            val fuToPush = mutableListOf<com.realtor.geeksales.data.remote.WbFollowup>()
            repo.allFollowUps().forEach { fu ->
                val owner = customerById[fu.customerId]?.wbContactId ?: return@forEach
                val content = followupContent(fu)
                val date = Formatter.epochToDay(fu.createdAt) ?: return@forEach
                val key = "$owner|$content|$date"
                if (key in remoteFuKeys) return@forEach
                fuToPush.add(
                    com.realtor.geeksales.data.remote.WbFollowup(
                        contactId = owner,
                        content = content,
                        followupType = "phone",
                        followupDate = date,
                        nextFollowupDate = Formatter.epochToDay(fu.remindAt)
                    )
                )
            }
            fuToPush.chunked(100).forEach { chunk ->
                when (val r = wbApi.createFollowupsBatch(chunk)) {
                    is WbResult.Success -> {
                        pushed += chunk.size - r.data.errors.count { it.index in chunk.indices }
                        fuFailed += r.data.errors.count { it.index in chunk.indices }
                    }
                    is WbResult.Error -> fuFailed += chunk.size
                }
            }
            _status.value = IOStatus(
                message = "知行同步完成",
                syncSummary = "新建 $created / 更新 $updated / 线上保留 $skipped / 失败 $failed（无号码跳过 $noPhone），跟进推送 $pushed 条，标签自动创建 $createdTags 个${if (failedTags > 0) "（$failedTags 个失败）" else ""}，备份：$backup"
            )
        }
    }

    /** 线上跟进记录 → 本地（按 wbContactId 匹配客户 + 去重） */
    private suspend fun importRemoteFollowups(remote: List<com.realtor.geeksales.data.remote.WbFollowup>): Int {        var count = 0
        // 建 wbContactId → 本地客户映射
        val byWbId = HashMap<String, Customer>()
        repo.getAll().forEach { c -> if (!c.wbContactId.isNullOrBlank()) byWbId[c.wbContactId] = c }
        remote.forEach { f ->
            if (f.contactId.isBlank() || f.content.isBlank()) return@forEach
            val local = byWbId[f.contactId] ?: return@forEach
            val createdAt = Formatter.dayToEpoch(f.followupDate) ?: return@forEach
            // 去重：同客户 + 同备注 + 同时间
            if (repo.findFollowUpDedup(local.id, f.content, createdAt) != null) return@forEach
            repo.insertFollowUps(
                listOf(
                    com.realtor.geeksales.data.db.FollowUp(
                        customerId = local.id,
                        result = FollowResult.PENDING,
                        durationSec = 0,
                        note = f.content,
                        remindAt = Formatter.dayToEpoch(f.nextFollowupDate),
                        fromPostCall = false,
                        createdAt = createdAt
                    )
                )
            )
            count++
        }
        return count
    }

    // ================= 时光机（快照 + 恢复） =================
    // 快照 = 全量多表 XLSX（客户/跟进/标签/短信）→ 下载/TMA备份
    // 恢复 = 覆盖式重建：先自动快照当前状态（双保险），再清空本地并按快照重建

    /** 手动快照 */
    fun snapshotNow() = doRun("时光机快照中…") {
        withContext(Dispatchers.IO) {
            val file = snapshot.snapshotToDownloads()
            _status.value = if (file != null) {
                IOStatus(message = "快照完成：$file（下载/TMA备份）")
            } else {
                IOStatus(message = "没有可快照的数据")
            }
        }
    }

    /** 从备份文件恢复（覆盖模式） */
    fun restoreFrom(uri: Uri) = doRun("时光机恢复中…") {
        withContext(Dispatchers.IO) {
            // 双保险：先快照当前状态
            val safety = snapshot.snapshotToDownloads()
            val err = snapshot.restoreFrom(uri)
            _status.value = if (err == null) {
                IOStatus(
                    message = "恢复完成",
                    syncSummary = "恢复前已自动备份当前状态${if (safety != null) "（$safety）" else "（当前无数据）"}"
                )
            } else {
                IOStatus(message = "恢复失败：$err")
            }
        }
    }

    // ================= 短信备份与同步 =================
    // 本地：增量备份 CSV 到下载目录；云端：双向同步到知行朋友圈 /api/messages。
    // 隐私：短信为最敏感数据，默认本地备份；上云仅在用户主动点击「同步」时执行。

    /** 本地增量备份短信（CSV 到 Downloads/TMA备份），需 READ_SMS 权限 */
    fun backupSms() = doRun("短信备份中…") {
        withContext(Dispatchers.IO) {
            val r = smsExporter.backupToDownloads()
            if (r.error != null) {
                _status.value = IOStatus(message = "短信备份失败：${r.error}")
            } else if (r.exported == 0) {
                _status.value = IOStatus(message = "没有新增短信需要备份")
            } else {
                _status.value = IOStatus(
                    message = "短信备份完成：${r.exported} 条（关联客户 ${r.matched} 位）",
                    exportCount = r.exported
                )
            }
        }
    }

    /** 短信同步到知行朋友圈（本地系统短信增量推送，需 READ_SMS） */
    fun exportSmsToWorkbuddy() = doRun("短信同步到云端中…") {
        withContext(Dispatchers.IO) {
            if (apiKeyStore.load().isNullOrBlank()) {
                _status.value = IOStatus(message = "请先配置知行朋友圈 API Key")
                return@withContext
            }
            // 先检查线上短信同步开关（关闭时 messages 接口 403）
            when (val sw = wbApi.smsSyncEnabled()) {
                is WbResult.Error -> {
                    _status.value = IOStatus(message = "无法读取短信开关：${sw.message}")
                    return@withContext
                }
                is WbResult.Success -> if (!sw.data) {
                    _status.value = IOStatus(message = "知行朋友圈短信同步开关未开启，请在网页「API 接入」页打开后再试")
                    return@withContext
                }
            }
            val prefs = ctx.getSharedPreferences("tma_prefs", Context.MODE_PRIVATE)
            val cursor = prefs.getLong("sms_sync_last_id", 0L)
            // 远端消息 key 集合（phone|body|messageDate），避免重复推送
            val remoteKeys = HashSet<String>()
            when (val r = wbApi.fetchAllMessages()) {
                is WbResult.Success -> r.data.forEach {
                    remoteKeys.add("${it.phone}|${it.body}|${it.messageDate}")
                }
                is WbResult.Error -> {
                    // 线上接口未开放时给出明确指引
                    _status.value = IOStatus(message = "云端短信接口不可用：${r.message}")
                    return@withContext
                }
            }
            // 本地客户映射（号码 → wbContactId）
            val wbIdByPhone = HashMap<String, String>()
            repo.getAll().forEach { c ->
                if (!c.wbContactId.isNullOrBlank()) wbIdByPhone[c.phoneNormalized] = c.wbContactId
            }
            val sdfMin = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val smsUri = android.net.Uri.parse("content://sms")
            val projection = arrayOf("_id", "address", "body", "date", "type")
            val selection = if (cursor > 0) "_id > ?" else null
            val args = if (selection != null) arrayOf(cursor.toString()) else null
            var pushed = 0
            var failed = 0
            var maxId = cursor
            runCatching {
                ctx.contentResolver.query(smsUri, projection, selection, args, "_id ASC")?.use { c ->
                    val idI = c.getColumnIndexOrThrow("_id")
                    val addrI = c.getColumnIndexOrThrow("address")
                    val bodyI = c.getColumnIndexOrThrow("body")
                    val dateI = c.getColumnIndexOrThrow("date")
                    val typeI = c.getColumnIndexOrThrow("type")
                    while (c.moveToNext()) {
                        val id = c.getLong(idI)
                        val phone = c.getString(addrI).orEmpty().trim()
                        val body = c.getString(bodyI).orEmpty()
                        val dateMs = c.getLong(dateI)
                        val type = c.getInt(typeI)
                        maxId = maxOf(maxId, id)
                        if (phone.isBlank() || body.isBlank()) continue
                        val dateLabel = runCatching { sdfMin.format(Date(dateMs)) }.getOrNull()
                        if (dateLabel == null) continue
                        val key = "$phone|$body|$dateLabel"
                        if (key in remoteKeys) continue
                        val m = WbMessage(
                            contactId = wbIdByPhone[Formatter.normalizePhone(phone)],
                            phone = phone,
                            body = body,
                            direction = if (type == 1) "in" else "out",
                            messageDate = dateLabel
                        )
                        when (wbApi.createMessage(m)) {
                            is WbResult.Success -> { pushed++; remoteKeys.add(key) }
                            is WbResult.Error -> failed++
                        }
                        if ((pushed + failed) % 100 == 0) kotlinx.coroutines.yield()
                    }
                }
            }.onFailure { t ->
                _status.value = IOStatus(message = "读取短信失败：${t.message ?: t.javaClass.simpleName}")
                return@withContext
            }
            prefs.edit().putLong("sms_sync_last_id", maxId).apply()
            _status.value = IOStatus(
                message = "短信云端同步完成：推送 ${pushed} 条（失败 $failed）",
                syncSummary = "失败原因多为线上接口未开放或限流；本地数据未受影响"
            )
        }
    }

    /** 从知行朋友圈拉取短信到本地（存 sms_messages 表，供客户时间线展示） */
    fun importSmsFromWorkbuddy() = doRun("拉取云端短信中…") {
        withContext(Dispatchers.IO) {
            if (apiKeyStore.load().isNullOrBlank()) {
                _status.value = IOStatus(message = "请先配置知行朋友圈 API Key")
                return@withContext
            }
            // 先检查线上短信同步开关（关闭时 messages 接口 403）
            when (val sw = wbApi.smsSyncEnabled()) {
                is WbResult.Error -> {
                    _status.value = IOStatus(message = "无法读取短信开关：${sw.message}")
                    return@withContext
                }
                is WbResult.Success -> if (!sw.data) {
                    _status.value = IOStatus(message = "知行朋友圈短信同步开关未开启，请在网页「API 接入」页打开后再试")
                    return@withContext
                }
            }
            val messages = when (val r = wbApi.fetchAllMessages()) {
                is WbResult.Error -> {
                    _status.value = IOStatus(message = "云端短信接口不可用：${r.message}")
                    return@withContext
                }
                is WbResult.Success -> r.data
            }
            if (messages.isEmpty()) {
                _status.value = IOStatus(message = "云端没有短信记录")
                return@withContext
            }
            // 客户映射：优先 wbContactId，其次号码
            val customerByWbId = HashMap<String, Customer>()
            val customerByPhone = HashMap<String, Customer>()
            repo.getAll().forEach { c ->
                if (!c.wbContactId.isNullOrBlank()) customerByWbId[c.wbContactId] = c
                if (c.phoneNormalized.isNotBlank()) customerByPhone[c.phoneNormalized] = c
            }
            var imported = 0
            var skipped = 0
            val toInsert = mutableListOf<com.realtor.geeksales.data.db.SmsMessage>()
            messages.forEach { m ->
                if (m.id.isBlank()) return@forEach
                if (repo.smsByWbId(m.id) != null) { skipped++; return@forEach }
                val local = m.contactId?.let { customerByWbId[it] }
                    ?: customerByPhone[Formatter.normalizePhone(m.phone)]
                toInsert.add(
                    com.realtor.geeksales.data.db.SmsMessage(
                        customerId = local?.id ?: 0L,
                        phone = m.phone,
                        body = m.body,
                        direction = m.direction,
                        messageDate = Formatter.dayToEpoch(m.messageDate?.take(10))
                            ?: System.currentTimeMillis(),
                        wbMessageId = m.id
                    )
                )
                imported++
                if (imported % 500 == 0) kotlinx.coroutines.yield()
            }
            if (toInsert.isNotEmpty()) repo.insertSms(toInsert)
            _status.value = IOStatus(
                message = "云端短信拉取完成：$imported 条（跳过重复 $skipped）",
                syncSummary = "已存入本地短信表，可在客户详情页查看"
            )
        }
    }

    // ---- 字段映射（以线上为准）----
    /** 智能合并：本地空字段 ← 线上非空；冲突保留本地并计数 */
    private fun mergeWbIntoLocal(local: Customer, wb: com.realtor.geeksales.data.remote.WbContact): Customer {
        fun <T : CharSequence> pick(l: T?, r: T?): T? = if (!l.isNullOrBlank()) l else r
        val (parsed, restNote) = parseMemo(wb.memo)
        return local.copy(
            wechat = pick(local.wechat, wb.wechat),
            source = pick(local.source, wb.source),
            note = pick(local.note, restNote),
            nickname = pick(local.nickname, wb.nickname),
            // memo 解析出的购房需求字段：本地空 ← 线上
            targetProject = pick(local.targetProject, parsed.targetProject),
            areaPref = pick(local.areaPref, parsed.areaPref),
            budgetMinWan = local.budgetMinWan ?: parsed.budgetMinWan,
            budgetMaxWan = local.budgetMaxWan ?: parsed.budgetMaxWan,
            houseType = pick(local.houseType, parsed.houseType),
            nextFollowAt = local.nextFollowAt ?: Formatter.dayToEpoch(wb.nextFollowupDate),
            updatedAt = System.currentTimeMillis()
        )
    }

    /** 是否存在字段冲突（智能合并报告中提示） */
    private fun hasFieldConflict(local: Customer, wb: com.realtor.geeksales.data.remote.WbContact): Boolean {
        fun diff(l: String?, r: String?): Boolean =
            !l.isNullOrBlank() && !r.isNullOrBlank() && l != r
        return diff(local.wechat, wb.wechat) || diff(local.note, wb.memo) || diff(local.nickname, wb.nickname)
    }

    /** 线上 tier S/A/B/C/D/V → 本地意向等级（六层语义对齐：B/C/D 是接触深度漏斗） */
    private fun wbTierToLevel(tier: String?): IntentLevel = when (tier?.trim()?.uppercase()) {
        "S" -> IntentLevel.S
        "A" -> IntentLevel.A
        "B" -> IntentLevel.B
        "C" -> IntentLevel.C
        "D" -> IntentLevel.D
        "V" -> IntentLevel.V
        // U 线上已兼容并归为 D；本地保留 U（纯本地状态）
        else -> IntentLevel.U
    }

    private fun levelToWbTier(level: IntentLevel): String? = when (level) {
        IntentLevel.S -> "S"
        IntentLevel.A -> "A"
        IntentLevel.B -> "B"
        IntentLevel.C -> "C"
        IntentLevel.D -> "D"
        IntentLevel.V -> "V"
        // U 为纯本地状态：不同步（线上也兼容 U 自动归 D）
        IntentLevel.U -> null
    }

    /** 线上联系人 → 本地客户（memo 中楼盘/区域/预算/房型自动回填内置字段） */
    private fun wbToCustomer(wb: com.realtor.geeksales.data.remote.WbContact, phoneN: String): Customer {
        val (parsed, restNote) = parseMemo(wb.memo)
        return Customer(
            name = wb.name.ifBlank { "未命名" },
            phone = wb.phone,
            phoneNormalized = phoneN,
            wechat = wb.wechat,
            source = wb.source?.ifBlank { null } ?: "知行朋友圈",
            intentLevel = wbTierToLevel(wb.tier),
            note = restNote,
            nextFollowAt = Formatter.dayToEpoch(wb.nextFollowupDate),
            nickname = wb.nickname,
            targetProject = parsed.targetProject,
            areaPref = parsed.areaPref,
            budgetMinWan = parsed.budgetMinWan,
            budgetMaxWan = parsed.budgetMaxWan,
            houseType = parsed.houseType,
            wbContactId = wb.id,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * 本地客户 → 线上联系人（导出用）。
     * 内置字段 + 扩展字段；memo 并入楼盘/区域/预算/房型（线上无独立字段，统一入备注）；
     * externalId = "tma-<本地ID>"，幂等同步关键。
     */
    private fun customerToWb(c: Customer, tagIds: List<String>, customFields: Map<String, String> = emptyMap()): com.realtor.geeksales.data.remote.WbContact =
        com.realtor.geeksales.data.remote.WbContact(
            name = c.name,
            nickname = c.nickname,
            phone = c.phone,
            wechat = c.wechat,
            tier = levelToWbTier(c.intentLevel),
            source = c.source,
            nextFollowupDate = Formatter.epochToDay(c.nextFollowAt),
            memo = mergeToMemo(c),
            tagIds = tagIds,
            customFields = customFields,
            externalId = "tma-${c.id}"
        )

    /** 楼盘/区域/预算/房型 无独立字段，统一并入线上 memo（分号分隔键值，导入时可解析回填） */
    private fun mergeToMemo(c: Customer): String {
        val items = mutableListOf<String>()
        if (!c.targetProject.isNullOrBlank()) items.add("楼盘：${c.targetProject}")
        if (!c.areaPref.isNullOrBlank()) items.add("区域：${c.areaPref}")
        val budget = buildString {
            if (c.budgetMinWan != null) append("${c.budgetMinWan}")
            if (c.budgetMinWan != null && c.budgetMaxWan != null) append("-")
            if (c.budgetMaxWan != null) append("${c.budgetMaxWan}")
        }
        if (budget.isNotBlank()) items.add("预算：${budget}万")
        if (!c.houseType.isNullOrBlank()) items.add("房型：${c.houseType}")
        val merged = items.joinToString("；")
        val note = c.note?.trim().orEmpty()
        return if (merged.isNotEmpty() && note.isNotEmpty()) "$merged；$note"
        else if (merged.isNotEmpty()) merged else note
    }

    /** 从线上 memo 解析楼盘/区域/预算/房型 回填本地内置字段（键值分号分隔；其余并入备注） */
    private fun parseMemo(memo: String?): Pair<Customer, String> {
        // 返回 (回填的字段增量, 剩余备注)
        val note = memo?.trim().orEmpty()
        if (note.isEmpty()) return Customer(name = "", phone = "", phoneNormalized = "") to note
        var project: String? = null
        var area: String? = null
        var budgetMin: Int? = null
        var budgetMax: Int? = null
        var house: String? = null
        val rest = mutableListOf<String>()
        note.split('；', ';').forEach { seg ->
            val s = seg.trim()
            when {
                s.startsWith("楼盘：") -> project = s.removePrefix("楼盘：").trim()
                s.startsWith("区域：") -> area = s.removePrefix("区域：").trim()
                s.startsWith("预算：") -> {
                    val v = s.removePrefix("预算：").trim().removeSuffix("万").trim()
                    val parts = v.split('-', '—', '~')
                    budgetMin = parts.getOrNull(0)?.toIntOrNull()
                    budgetMax = parts.getOrNull(1)?.toIntOrNull()
                }
                s.startsWith("房型：") -> house = s.removePrefix("房型：").trim()
                else -> if (s.isNotBlank()) rest.add(s)
            }
        }
        return Customer(
            name = "", phone = "", phoneNormalized = "",
            targetProject = project,
            areaPref = area,
            budgetMinWan = budgetMin,
            budgetMaxWan = budgetMax,
            houseType = house
        ) to rest.joinToString("；")
    }

    /** 本地跟进记录 → 线上 content 文本（含结果标签，便于回读） */
    private fun followupContent(fu: com.realtor.geeksales.data.db.FollowUp): String {
        val label = when (fu.result) {
            FollowResult.CONNECTED -> "接通"
            FollowResult.NOT_INTERESTED -> "不感兴趣"
            FollowResult.NOT_REACHED -> "未接通"
            FollowResult.WRONG_NUMBER -> "错号"
            FollowResult.SHUTDOWN -> "停机"
            FollowResult.APPOINTMENT -> "预约"
            FollowResult.PENDING -> "待跟进"
        }
        val note = fu.note?.trim().orEmpty()
        return if (note.isNotEmpty()) "[$label] $note" else "[$label]"
    }

    private fun doRun(progressMsg: String, block: suspend () -> Unit) {
        _status.value = IOStatus(running = true, message = progressMsg)
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { t ->
                    _status.value = IOStatus(message = "失败：${t.message ?: t.javaClass.simpleName}")
                }
        }
    }
}