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
import com.realtor.geeksales.data.importexport.CallLogReader
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
import kotlinx.coroutines.async
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
    val syncSummary: String? = null,
    /** 是否同步失败/部分失败（UI 显示为警示色，不再误报纯成功） */
    val isError: Boolean = false,
    /** 进度 0..1；null 表示不确定进度（转圈） */
    val progress: Float? = null
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
    private val callLogReader: CallLogReader,
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
                    // 模板元数据（tiers/分层语义/标签）始终落库，驱动筛选与标签选择器
                    val oldMeta = schemaStore.meta()
                    schemaStore.saveMeta(
                        TemplateMeta(
                            tiers = bundle.tiers.ifEmpty { oldMeta.tiers },
                            identityTags = bundle.identityTags.ifEmpty { oldMeta.identityTags },
                            attributeTags = bundle.attributeTags.ifEmpty { oldMeta.attributeTags },
                            tierLabels = bundle.tierLabels.ifEmpty { oldMeta.tierLabels }
                        )
                    )
                    if (bundle.fields.isEmpty()) {
                        _status.value = IOStatus(message = "线上模板已同步（分层 ${bundle.tiers.joinToString("/") { if (it == "U") "/" else it }}；标签 ${bundle.identityTags.size + bundle.attributeTags.size} 个）")
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
                    _status.value = IOStatus(message = "模板已同步：${merged.size} 个字段 · 分层 ${bundle.tiers.joinToString("/") { if (it == "U") "/" else it }} · 标签 ${bundle.identityTags.size + bundle.attributeTags.size} 个")
                }
            }
        }
    }

    /** 当前生效模板 */
    fun currentSchema(): List<FieldDef> = schemaStore.current()
    fun meta(): com.realtor.geeksales.data.schema.TemplateMeta = schemaStore.meta()

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
                is WbResult.Error -> _status.value = IOStatus(
                    message = "自定义字段「$label」已添加本地（详情/编辑页可用）；线上字段接口暂未开放，推送失败：${r.message}",
                    isError = true
                )
            }
        }
    }

    /** 移除本地自定义字段（仅本地；已同步线上的需在线上删除） */
    fun removeCustomField(key: String) {
        schemaStore.removeLocalField(key)
        _status.value = IOStatus(message = "已移除本地自定义字段（如线上仍有，请在网页删除）")
    }

    fun importCsv(uri: Uri) = doRun("CSV 导入中…") {
        val r = csv.importFrom(uri) { p -> progress(0.05f + 0.9f * p, "CSV 导入中…（解析 → 查重 → 写入）") }
        _status.value = if (r.error != null) {
            IOStatus(message = "CSV 导入失败：${r.error}", report = r)
        } else {
            IOStatus(message = "CSV 导入完成", report = r)
        }
    }
    fun importXlsx(uri: Uri) = doRun("Excel 导入中…") {
        val r = excel.importFrom(uri) { p -> progress(0.05f + 0.9f * p, "Excel 导入中…（解析 → 查重 → 写入）") }
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
     * 把本地客户标签落地为手机通讯录分组（云端 v2.0「通讯录分组落地方案」P1）。
     * 增量安全模式：只新建缺失分组、只把客户加入对应分组；绝不删除任何既有分组/成员。
     * 幂等：已存在的「分组-成员」关系跳过，重复执行不会产生重复成员。
     */
    fun syncGroupsFromTags() = doRun("标签同步到通讯录分组中…") {
        withContext(Dispatchers.IO) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.WRITE_CONTACTS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                _status.value = IOStatus(message = "需要通讯录写入权限，请先在系统设置中允许后重试")
                return@withContext
            }
            val tags = repo.allTags().map { it.name }.filter { it.isNotBlank() }
            if (tags.isEmpty()) {
                _status.value = IOStatus(message = "本地还没有标签，请先在客户详情页添加标签（线上模板标签同步后也可用）")
                return@withContext
            }
            val resolver = ctx.contentResolver
            // 1) 标签 → 客户号码（一个客户多标签 → 加入多个分组，Android 原生支持）
            val tagToPhones = HashMap<String, MutableSet<String>>()
            repo.getAll().forEach { c ->
                val t = repo.tagsOf(c.id)
                if (t.isNotEmpty() && c.phoneNormalized.isNotBlank()) {
                    t.forEach { tag -> tagToPhones.getOrPut(tag) { HashSet() }.add(c.phoneNormalized) }
                }
            }
            // 2) 已有分组 id（DELETED=0）
            val groupIdByName = HashMap<String, Long>()
            resolver.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE),
                "${ContactsContract.Groups.DELETED}=0", null, null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(ContactsContract.Groups._ID)
                val titleCol = c.getColumnIndexOrThrow(ContactsContract.Groups.TITLE)
                while (c.moveToNext()) groupIdByName[c.getString(titleCol).orEmpty()] = c.getLong(idCol)
            }
            // 3) 号码 → raw_contact_id：Phone(NUMBER, CONTACT_ID) → RawContacts(CONTACT_ID→_ID)
            val phoneToContactId = HashMap<String, Long>()
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID
                ), null, null, null
            )?.use { c ->
                val nCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val idCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                while (c.moveToNext()) {
                    val n = Formatter.normalizePhone(c.getString(nCol).orEmpty())
                    if (n.isNotBlank()) phoneToContactId.putIfAbsent(n, c.getLong(idCol))
                }
            }
            val contactIdToRaw = HashMap<Long, Long>()
            resolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.CONTACT_ID),
                null, null, null
            )?.use { c ->
                val rawCol = c.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
                val cidCol = c.getColumnIndexOrThrow(ContactsContract.RawContacts.CONTACT_ID)
                while (c.moveToNext()) contactIdToRaw.putIfAbsent(c.getLong(cidCol), c.getLong(rawCol))
            }
            // 3.5) 已存在的「分组-成员」关系（幂等去重）
            val existingMembership = HashSet<String>()
            resolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data.RAW_CONTACT_ID,
                    ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID
                ),
                "${ContactsContract.Data.MIMETYPE}='${ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE}'",
                null, null
            )?.use { c ->
                val rawCol = c.getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID)
                val gCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID)
                while (c.moveToNext()) {
                    existingMembership.add("${c.getLong(gCol)}:${c.getLong(rawCol)}")
                }
            }
            // 4) 建分组 + 归组（新分组用 backref 引用其插入结果 id）
            val ops = ArrayList<ContentProviderOperation>()
            var createdGroups = 0
            var memberAdded = 0
            var memberSkipped = 0
            tagToPhones.forEach { (tag, phones) ->
                var gid = groupIdByName[tag]
                var groupOpIdx = -1
                if (gid == null) {
                    groupOpIdx = ops.size
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Groups.CONTENT_URI)
                            .withValue(ContactsContract.Groups.ACCOUNT_TYPE, null)
                            .withValue(ContactsContract.Groups.ACCOUNT_NAME, null)
                            .withValue(ContactsContract.Groups.TITLE, tag)
                            .withValue(ContactsContract.Groups.GROUP_VISIBLE, 1)
                            .build()
                    )
                    gid = -1L // 占位：后续用 backref
                    createdGroups++
                }
                phones.forEach { phone ->
                    val contactId = phoneToContactId[phone] ?: return@forEach
                    val rawId = contactIdToRaw[contactId] ?: return@forEach
                    if (gid >= 0) {
                        val key = "$gid:$rawId"
                        if (key in existingMembership) { memberSkipped++; return@forEach }
                    }
                    val insert = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                    if (gid >= 0) {
                        insert.withValue(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, gid)
                    } else {
                        insert.withValueBackReference(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupOpIdx)
                    }
                    ops.add(insert.build())
                    memberAdded++
                }
            }
            if (ops.isNotEmpty()) {
                runCatching { resolver.applyBatch(ContactsContract.AUTHORITY, ops) }
                    .onFailure { t ->
                        _status.value = IOStatus(message = "分组同步失败：${t.message ?: t.javaClass.simpleName}")
                        return@withContext
                    }
            }
            _status.value = IOStatus(
                message = "标签已同步到通讯录分组",
                syncSummary = "新建分组 $createdGroups 个，加入成员 $memberAdded 条（跳过已存在 $memberSkipped 条）。增量模式，不删除任何既有数据；可在系统通讯录的对应分组中圈选群发。"
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

    /** 一键检测 API Key：轻量探测线上接口，立即区分“密钥无效/撤销”与“网络问题”，不用点同步才知道 */
    fun verifyApiKey() {
        viewModelScope.launch {
            _status.value = IOStatus(message = "正在验证 API Key…", isError = false, syncSummary = "正在请求云端…")
            when (val r = wbApi.verifyKey()) {
                is WbResult.Success -> _status.value = IOStatus(
                    message = "API Key 验证通过",
                    isError = false,
                    syncSummary = "密钥有效，可正常同步"
                )
                is WbResult.Error -> _status.value = IOStatus(
                    message = "API Key 验证失败",
                    isError = true,
                    syncSummary = r.message
                )
            }
        }
    }

    // ---- 同步模式（prefs 持久化）----
    private val prefs = ctx.getSharedPreferences("tma_prefs", Context.MODE_PRIVATE)

    fun syncMode(): SyncMode = runCatching {
        SyncMode.valueOf(prefs.getString("wb_sync_mode", SyncMode.SMART.name) ?: SyncMode.SMART.name)
    }.getOrDefault(SyncMode.SMART)

    fun setSyncMode(mode: SyncMode) = prefs.edit().putString("wb_sync_mode", mode.name).apply()

    // ---- 服务器地址（平台迁移时切换，不写死）----
    fun baseUrl(): String = wbApi.baseUrl()
    fun setBaseUrl(url: String) = wbApi.setBaseUrl(url)
    fun resetBaseUrl() = wbApi.resetBaseUrl()

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
            // 分批处理联系人，按批上报进度（用户可见：第 N/M 批、已处理多少）
            val contactChunks = contacts.chunked(200)
            contactChunks.forEachIndexed { ci, chunk ->
                progress(
                    0.15f + 0.48f * ci / contactChunks.size.coerceAtLeast(1),
                    "知行同步：写入联系人 ${(ci * 200 + chunk.size).coerceAtMost(contacts.size)}/${contacts.size}…"
                )
                chunk.forEach { wb ->
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
            }
            // 4) 拉取线上跟进历史并导入（按 contactId → 本地 wbContactId 匹配，去重）
            progress(0.7f, "知行同步：拉取跟进历史…")
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
            // 5) 拉取线上短信并导入
            progress(0.8f, "知行同步：拉取云端短信…")
            val smsImported = importRemoteSms()
            progress(0.95f, "知行同步：完成汇总…")
            val localCount = repo.countOnce()
            _status.value = IOStatus(
                message = if (imported == 0 && localCount > 0) "知行同步完成（线上无数据）" else "知行同步完成",
                syncSummary = "导入 $imported 位联系人 / 覆盖 $overridden / 冲突保留 $conflicts / 跳过 $skipped，跟进记录 $followupsImported 条，短信 $smsImported 条，备份：$backup" +
                    (if (imported == 0 && localCount > 0) "\n线上暂无数据：若要把本地数据上传到线上，请在「数据」页点「备份并导出」" else "")
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
            progress(0.05f, "知行同步：备份完成，拉取线上数据…")
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
            progress(0.12f, "知行同步：拉取线上标签…")
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
            if (createdTags > 0) progress(0.18f, "已自动创建 $createdTags 个本地标签到知行朋友圈…")
            // 3.6) 同步前检查云端最近批次状态（数据可追溯规范）
            //     上次批次被回滚（reverted）说明云端已撤销该批数据，警告用户避免盲推
            var batchWarn = ""
            when (val bs = wbApi.fetchLatestBatchStatus()) {
                is WbResult.Success -> if (bs.data == "reverted") {
                    batchWarn = "⚠ 云端最近一次批次已被回滚，本次同步仍会执行，请留意云端数据"
                }
                is WbResult.Error -> { /* 接口不可用不阻塞同步 */ }
            }
            // 3) 按同步模式导出联系人（新建走批量 /contacts/batch，externalId 幂等；更新走单条 PUT）
            val mode = syncMode()
            var created = 0
            var updated = 0
            var skipped = 0
            var failed = 0
            var noPhone = 0
            var lastErrCode: Int? = null
            var lastErrMsg: String? = null
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
                            // 智能合并：本地有真实改动（updatedAt 新于云端）才推送更新，云端已最新则跳过，
                            // 日常同步只推几十条真实变更，秒级完成，不再 2330 条全量串行 PUT
                            SyncMode.SMART -> {
                                val remoteUp = remote.updatedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                                if (remoteUp != null && (c.updatedAt ?: 0L) <= remoteUp) skipped++
                                else toUpdate.add(Triple(c, wb, remote.id))
                            }
                            // 本地优先：本地为准推送更新
                            SyncMode.LOCAL_FIRST -> toUpdate.add(Triple(c, wb, remote.id))
                        }
                    }
                    else -> toCreate.add(c to wb)
                }
            }
            // 批量新建：≤100/批，externalId 幂等，逐条失败不阻塞
            val createChunks = toCreate.chunked(100)
            createChunks.forEachIndexed { ci, chunk ->
                progress(
                    0.2f + 0.48f * ci / createChunks.size.coerceAtLeast(1),
                    "知行同步：上传联系人 ${(ci * 500 + chunk.size).coerceAtMost(toCreate.size)}/${toCreate.size}…"
                )
                suspend fun applyCreated(rr: WbResult<com.realtor.geeksales.data.remote.WbBatchResponse>, ch: List<Pair<Customer, com.realtor.geeksales.data.remote.WbContact>>) {
                    if (rr is WbResult.Success) {
                        rr.data.items.forEachIndexed { i, item ->
                            val local = ch.getOrNull(i)?.first ?: return@forEachIndexed
                            if (item.id.isNotBlank()) {
                                created++
                                if (local.wbContactId != item.id) repo.updateWbContactId(local.id, item.id)
                            }
                        }
                        if (rr.data.errors.isNotEmpty()) {
                            // 记录真实拒绝原因（201 + errors 表示逐条被服务端拒绝，原因见 message）
                            lastErrMsg = rr.data.errors.first().message.take(200)
                            failed += rr.data.errors.count { it.index in ch.indices }
                        }
                    } else if (rr is WbResult.Error) {
                        // 整批失败：记录状态码与服务端原文
                        val code = rr.message.substringBefore(':').trim().toIntOrNull()
                        if (code != null) lastErrCode = code
                        lastErrMsg = rr.message.take(200)
                        failed += ch.size
                    }
                }
                var r: WbResult<com.realtor.geeksales.data.remote.WbBatchResponse> = withBackoff { wbApi.createContactsBatch(chunk.map { it.second }, batchMeta()) }
                if (r is WbResult.Error && chunk.size > 100) {
                    // 大包失败 → 拆 100 重发（服务端上限 100/500 自适应）
                    chunk.chunked(100).forEach { c100 ->
                        applyCreated(withBackoff { wbApi.createContactsBatch(c100.map { it.second }, batchMeta()) }, c100)
                        kotlinx.coroutines.yield()
                    }
                } else {
                    applyCreated(r, chunk)
                }
                kotlinx.coroutines.yield()
                kotlinx.coroutines.delay(250)
            }
            // 更新已有联系人（单条 PUT；6 并发 + 真实进度，不再串行假死）
            progress(0.7f, "知行同步：更新已有联系人 ${toUpdate.size} 条…")
            if (toUpdate.isNotEmpty()) {
                // 6 并发单条 PUT（线上无批量更新接口）：ExecutorService 并发 + 真实进度，不再串行假死
                val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
                try {
                    val futures = toUpdate.map { (c, wb, remoteId) ->
                        pool.submit(java.util.concurrent.Callable<Boolean> {
                            kotlinx.coroutines.runBlocking {
                                try { wbApi.updateContact(remoteId, wb) is WbResult.Success }
                                catch (t: Exception) { false }
                            }
                        })
                    }
                    var done = 0
                    futures.forEach { fut ->
                        val ok = try { fut.get() } catch (t: Exception) { false }
                        if (ok) {
                            updated++
                            val backfill = toUpdate[done]
                            try { kotlinx.coroutines.runBlocking { repo.updateWbContactId(backfill.first.id, backfill.third) } }
                            catch (t: Exception) { /* 回填失败不影响同步结果 */ }
                        } else failed++
                        done++
                        progress(0.7f + 0.22f * done / toUpdate.size.coerceAtLeast(1), "知行同步：更新已有联系人 $done/${toUpdate.size}…")
                    }
                } finally { pool.shutdown() }
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
            fuToPush.chunked(100).forEachIndexed { fi, chunk ->
                progress(
                    0.72f + 0.16f * fi / fuToPush.chunked(100).size.coerceAtLeast(1),
                    "知行同步：推送跟进记录 ${(fi * 500 + chunk.size).coerceAtMost(fuToPush.size)}/${fuToPush.size}…"
                )
                suspend fun applyFu(rr: WbResult<com.realtor.geeksales.data.remote.WbBatchResponse>, ch: List<com.realtor.geeksales.data.remote.WbFollowup>) {
                    if (rr is WbResult.Success) {
                        pushed += ch.size - rr.data.errors.count { it.index in ch.indices }
                        fuFailed += rr.data.errors.count { it.index in ch.indices }
                    } else {
                        fuFailed += ch.size
                    }
                }
                var r: WbResult<com.realtor.geeksales.data.remote.WbBatchResponse> = withBackoff { wbApi.createFollowupsBatch(chunk, batchMeta()) }
                if (r is WbResult.Error && chunk.size > 100) {
                    // 大包失败 → 拆 100 重发（服务端上限 100/500 自适应）
                    chunk.chunked(100).forEach { c100 ->
                        applyFu(withBackoff { wbApi.createFollowupsBatch(c100, batchMeta()) }, c100)
                        kotlinx.coroutines.yield()
                    }
                } else {
                    applyFu(r, chunk)
                }
                kotlinx.coroutines.yield()
                kotlinx.coroutines.delay(250)
            }
            progress(0.92f, "知行同步：完成汇总…")
            val hasFail = failed > 0 || fuFailed > 0 || failedTags > 0
            _status.value = IOStatus(
                message = if (hasFail) "知行同步完成（${failed + fuFailed + failedTags} 条失败，详见明细）" else "知行同步完成",
                isError = hasFail,
                syncSummary = listOfNotNull(
                    batchWarn.takeIf { it.isNotEmpty() },
                    "新建 $created / 更新 $updated / 线上保留 $skipped / 失败 $failed（无号码跳过 $noPhone）",
                    "跟进推送 $pushed 条，标签自动创建 $createdTags 个${if (failedTags > 0) "（$failedTags 个失败）" else ""}",
                    "备份：$backup",
                    if (hasFail) {
                        when {
                            lastErrCode == 401 -> "API Key 无效或已撤销：请在「数据」页重新生成并保存密钥"
                            lastErrCode == 403 -> "线上接口未授权（403）：请让 Web 端检查对应同步开关"
                            lastErrMsg != null -> "服务端返回：${lastErrMsg.orEmpty().take(160)}"
                            else -> "失败原因多为：服务器地址不正确（应以 /api 结尾）或线上接口未开放；请检查「数据」页服务器地址后重试"
                        }
                    } else null
                ).joinToString("\n")
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
            val err = snapshot.restoreFrom(uri) { p ->
                progress(0.1f + 0.85f * p, "时光机恢复中…（解析 → 写入客户 → 跟进/标签/短信/扩展字段）")
            }
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
            // 权限预检查：无 READ_SMS 权限直接明确报错引导，不进入"读取 0 条假成功"
            if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_SMS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                _status.value = IOStatus(
                    message = "未授予「短信」权限，无法读取短信",
                    isError = true,
                    syncSummary = "请到 系统设置 → 应用 → TMA → 权限 → 短信 开启后重试"
                )
                return@withContext
            }
            // 检查线上短信同步开关；关闭时自动一键开启（PUT /api/settings/sms-sync），
            // 用户点击同步按钮即视为授权开启；开启失败才提示去网页操作
            when (val sw = wbApi.smsSyncEnabled()) {
                is WbResult.Error -> {
                    _status.value = IOStatus(message = "无法读取短信开关：${sw.message}")
                    return@withContext
                }
                is WbResult.Success -> if (!sw.data) {
                    progress(0.02f, "正在为你开启云端短信同步开关…")
                    when (val on = wbApi.setSmsSync(true)) {
                        is WbResult.Success -> progress(0.05f, "云端短信同步已开启，继续…")
                        is WbResult.Error -> {
                            _status.value = IOStatus(message = "云端短信同步开关未开启，且自动开启失败：${on.message}。请在网页「API 接入」页打开短信同步后再试")
                            return@withContext
                        }
                    }
                }
            }
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
            // 增量 + 全量兜底：date 游标只在“整批成功”时推进；失败批下次仍在游标之后，必被重扫补推
            // （不会出现 v2.x 时代“失败一次后永远没有新短信”；首次/无游标 = 全量对账，补齐历史缺失）
            val since = prefs.getLong("sms_sync_upto_date", 0L)
            val selection = if (since > 0) "date > ?" else null
            val args = if (since > 0) arrayOf(since.toString()) else null
            var pushed = 0
            var failed = 0
            var lastErrCode: Int? = null
            var lastErrMsg: String? = null
            val toPush = mutableListOf<Pair<Long, WbMessage>>()
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
                        if (phone.isBlank() || body.isBlank()) continue
                        val dateLabel = runCatching { sdfMin.format(Date(dateMs)) }.getOrNull()
                        if (dateLabel == null) continue
                        val key = "$phone|$body|$dateLabel"
                        if (key in remoteKeys) continue
                        toPush.add(
                            id to WbMessage(
                                contactId = wbIdByPhone[Formatter.normalizePhone(phone)],
                                phone = phone,
                                body = body,
                                direction = if (type == 1) "in" else "out",
                                messageDate = dateLabel
                            )
                        )
                        remoteKeys.add(key)
                    }
                }
            }.onFailure { t ->
                _status.value = IOStatus(
                    message = "读取短信失败：${t.message ?: t.javaClass.simpleName}",
                    isError = true,
                    syncSummary = "请确认已授予「短信」权限：系统设置 → 应用 → TMA → 权限 → 短信"
                )
                return@withContext
            }
            // 批量推送：500/批（Web 端实测不再限流）+ 429/5xx 指数退避重试 + 400 降级 100（契约兼容）
            // 服务端按 号码+时间+内容 幂等去重，重试不会产生重复数据
            if (toPush.isNotEmpty()) {
                var maxOkDate = since
                val chunks = toPush.chunked(100)
                chunks.forEachIndexed { ci, chunk ->
                    progress(
                        0.1f + 0.8f * ci / chunks.size.coerceAtLeast(1),
                        "短信同步：上传 ${(ci * 500 + chunk.size).coerceAtMost(toPush.size)}/${toPush.size}…"
                    )
                    var r: WbResult<com.realtor.geeksales.data.remote.WbBatchResponse> = withBackoff { wbApi.createMessagesBatch(chunk.map { it.second }, batchMeta()) }
                    if (r is WbResult.Error) {
                        val code = r.message.substringBefore(':').trim().toIntOrNull()
                        lastErrCode = code ?: lastErrCode
                        lastErrMsg = r.message.take(200)
                        // 大包失败（5xx 服务端繁忙/上限、400/413/415 契约上限）→ 自动拆 100 重发，
                        // 服务端上限是 100 还是 500 都自适应；100 仍失败才计失败
                        if (chunk.size > 100) {
                            chunk.chunked(100).forEach { c100 ->
                                when (val r2 = withBackoff { wbApi.createMessagesBatch(c100.map { it.second }, batchMeta()) }) {
                                    is WbResult.Success -> {
                                        pushed += r2.data.items.count { it.id.isNotBlank() }
                                        failed += r2.data.errors.size
                                        if (r2.data.errors.isEmpty()) maxOkDate = maxOf(maxOkDate, c100.maxOf { it.first })
                                    }
                                    is WbResult.Error -> {
                                        val c2 = r2.message.substringBefore(':').trim().toIntOrNull()
                                        if (c2 != null) lastErrCode = c2
                                        lastErrMsg = r2.message.take(200)
                                        failed += c100.size
                                    }
                                }
                                kotlinx.coroutines.yield()
                            }
                        } else {
                            failed += chunk.size
                        }
                    } else if (r is WbResult.Success) {
                        pushed += r.data.items.count { it.id.isNotBlank() }
                        failed += r.data.errors.size
                        // 整批成功才推进游标（失败批下次仍在游标之后，必被重扫补推）
                        if (r.data.errors.isEmpty()) maxOkDate = maxOf(maxOkDate, chunk.maxOf { it.first })
                    }
                    // 批间 250ms，降低瞬时并发（Web 端建议）
                    kotlinx.coroutines.delay(250)
                }
                if (maxOkDate > since) prefs.edit().putLong("sms_sync_upto_date", maxOkDate).apply()
            }
            // 0 条也要说清楚：区分"已全部同步过"与"权限/数据问题"，绝不假成功
            val msg = if (pushed == 0 && failed == 0) {
                "未发现可同步的新短信（本机无新短信，或已全部同步过）"
            } else {
                "短信云端同步完成：推送 $pushed 条（失败 $failed）"
            }
            val errHint = when (lastErrCode) {
                401 -> "API Key 无效或已失效：请到数据页检查密钥"
                403 -> "云端同步开关未开启：请先开启短信同步开关再试"
                429 -> "线上限流：已自动重试仍失败，本地数据未受影响，稍后再试"
                in 400..499 -> "请求被云端拒绝（HTTP $lastErrCode）：多为字段格式问题，已保留本地数据"
                in 500..599 -> "线上服务繁忙或批次过大被拒（HTTP $lastErrCode）：已自动拆小批重试，本地数据未受影响"
                else -> null
            }
            _status.value = IOStatus(
                message = msg,
                isError = failed > 0,
                syncSummary = if (failed > 0) {
                    buildString {
                        append("失败 ${failed} 条；")
                        if (lastErrCode != null) {
                            append(errHint ?: "本地数据未受影响，稍后重试即可")
                            append("\n服务端返回：${lastErrMsg.orEmpty().take(160)}")
                        } else {
                            append("原因：${lastErrMsg.orEmpty().take(160)}")
                        }
                    }
                } else "云端已按批次归档，可时光机回溯"
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
            // 检查线上短信同步开关；关闭时自动一键开启（PUT /api/settings/sms-sync），
            // 用户点击同步按钮即视为授权开启；开启失败才提示去网页操作
            when (val sw = wbApi.smsSyncEnabled()) {
                is WbResult.Error -> {
                    _status.value = IOStatus(message = "无法读取短信开关：${sw.message}")
                    return@withContext
                }
                is WbResult.Success -> if (!sw.data) {
                    progress(0.02f, "正在为你开启云端短信同步开关…")
                    when (val on = wbApi.setSmsSync(true)) {
                        is WbResult.Success -> progress(0.05f, "云端短信同步已开启，继续…")
                        is WbResult.Error -> {
                            _status.value = IOStatus(message = "云端短信同步开关未开启，且自动开启失败：${on.message}。请在网页「API 接入」页打开短信同步后再试")
                            return@withContext
                        }
                    }
                }
            }
            val imported = importRemoteSms()
            _status.value = IOStatus(
                message = "云端短信拉取完成：$imported 条",
                syncSummary = "已存入本地短信表，可在客户详情页查看"
            )
        }
    }

    /** 拉取线上短信并写入本地，返回导入条数；失败/为空返回 0（供主同步复用，不中断主流程） */
    private suspend fun importRemoteSms(): Int = withContext(Dispatchers.IO) {
        val messages = when (val r = wbApi.fetchAllMessages()) {
            is WbResult.Error -> return@withContext 0
            is WbResult.Success -> r.data
        }
        if (messages.isEmpty()) return@withContext 0
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
        imported
    }

    // ---- 通话记录：本地镜像 + 云端备份/同步（线上 /api/calls）----

    /**
     * 通话记录完整同步：①一键开启云端通话开关 ②镜像本地系统通话到本地表
     * ③增量推送未上传通话 ④拉取云端通话去重入库。
     * 需 READ_CALL_LOG 权限（UI 层在点击时请求）。
     */
    fun syncCallsToWorkbuddy() = doRun("通话记录同步中…") {
        withContext(Dispatchers.IO) {
            if (apiKeyStore.load().isNullOrBlank()) {
                _status.value = IOStatus(message = "请先配置知行朋友圈 API Key")
                return@withContext
            }
            // 0) 权限预检查：无 READ_CALL_LOG 直接明确报错引导，不进入"读取 0 条假成功"
            if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CALL_LOG)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                _status.value = IOStatus(
                    message = "未授予「通话记录」权限，无法读取本机通话记录",
                    isError = true,
                    syncSummary = "请到 系统设置 → 应用 → TMA → 权限 → 电话/通话记录 开启后重试"
                )
                return@withContext
            }
            // 1) 云端开关：关闭时自动一键开启（用户点击同步即授权）
            when (val sw = wbApi.callSyncEnabled()) {
                is WbResult.Error -> {
                    _status.value = IOStatus(message = "无法读取通话同步开关：${sw.message}")
                    return@withContext
                }
                is WbResult.Success -> if (!sw.data) {
                    progress(0.02f, "正在为你开启云端通话同步开关…")
                    when (val on = wbApi.setCallSync(true)) {
                        is WbResult.Success -> progress(0.05f, "云端通话同步已开启，继续…")
                        is WbResult.Error -> {
                            _status.value = IOStatus(message = "云端通话同步开关未开启，且自动开启失败：${on.message}。请在网页「API 接入」页打开通话同步后再试")
                            return@withContext
                        }
                    }
                }
            }
            // 2) 镜像本地系统通话记录（增量，本地备份的第一道保险）
            progress(0.08f, "读取本机通话记录…")
            val mirror = callLogReader.mirrorLocalCallLog()
            if (mirror.error != null) {
                _status.value = IOStatus(
                    message = "读取本机通话记录失败：${mirror.error}",
                    isError = true,
                    syncSummary = "请确认已授予「通话记录」权限：系统设置 → 应用 → TMA → 权限 → 电话/通话记录；授予后重试"
                )
                return@withContext
            }
            // 3) 推送未上传通话（增量，≤100/批；direction/callDate 按线上契约）
            // 单次全量推送（≤5000）：历史通话一次同步到位，不再被 500 条上限截断
            val toPush = repo.pendingCallUploads(5000)
            var pushed = 0
            var pushFailed = 0
            if (toPush.isNotEmpty()) {
                val chunks = toPush.chunked(100)
                chunks.forEachIndexed { ci, chunk ->
                    progress(
                        0.12f + 0.4f * ci / chunks.size.coerceAtLeast(1),
                        "通话记录：上传 ${(ci * 500 + chunk.size).coerceAtMost(toPush.size)}/${toPush.size}…"
                    )
                    suspend fun applyCalls(rr: WbResult<com.realtor.geeksales.data.remote.WbBatchResponse>, ch: List<com.realtor.geeksales.data.db.CallRecord>) {
                        if (rr is WbResult.Success) {
                            // 响应 items 与请求顺序一致，回填线上 id
                            rr.data.items.forEachIndexed { i, item ->
                                val local = ch.getOrNull(i) ?: return@forEachIndexed
                                if (item.id.isNotBlank()) {
                                    pushed++
                                    if (local.wbCallId != item.id) repo.updateWbCallId(local.id, item.id)
                                }
                            }
                            pushFailed += rr.data.errors.count { it.index in ch.indices }
                        } else {
                            pushFailed += ch.size
                        }
                    }
                    val wbCalls = chunk.map { c ->
                        com.realtor.geeksales.data.remote.WbCall(
                            phone = c.phone,
                            direction = c.direction,
                            duration = c.duration,
                            callDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(c.callDate)),
                            note = c.note
                        )
                    }
                    var r: WbResult<com.realtor.geeksales.data.remote.WbBatchResponse> = withBackoff { wbApi.createCallsBatch(wbCalls, batchMeta()) }
                    if (r is WbResult.Error && chunk.size > 100) {
                        // 大包失败 → 拆 100 重发（服务端上限 100/500 自适应）
                        chunk.chunked(100).forEach { c100 ->
                            val wbC100 = c100.map { c ->
                                com.realtor.geeksales.data.remote.WbCall(
                                    phone = c.phone,
                                    direction = c.direction,
                                    duration = c.duration,
                                    callDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(c.callDate)),
                                    note = c.note
                                )
                            }
                            applyCalls(withBackoff { wbApi.createCallsBatch(wbC100, batchMeta()) }, c100)
                            kotlinx.coroutines.yield()
                        }
                    } else {
                        applyCalls(r, chunk)
                    }
                    kotlinx.coroutines.yield()
                    kotlinx.coroutines.delay(250)
                }
            }
            // 4) 拉取云端通话去重入库（互动档案完整性）
            progress(0.6f, "拉取云端通话记录…")
            var pulled = 0
            when (val r = wbApi.fetchAllCalls()) {
                is WbResult.Success -> {
                    val customerByWbId = HashMap<String, Long>()
                    val customerByPhone = HashMap<String, Long>()
                    repo.getAll().forEach { c ->
                        if (!c.wbContactId.isNullOrBlank()) customerByWbId[c.wbContactId] = c.id
                        if (c.phoneNormalized.isNotBlank()) customerByPhone[c.phoneNormalized] = c.id
                    }
                    val toInsert = mutableListOf<com.realtor.geeksales.data.db.CallRecord>()
                    r.data.forEach { wb ->
                        if (wb.id.isBlank()) return@forEach
                        if (repo.callByWbId(wb.id) != null) return@forEach
                        val localId = wb.contactId?.let { customerByWbId[it] }
                            ?: customerByPhone[Formatter.normalizePhone(wb.phone)]
                            ?: 0L
                        toInsert.add(
                            com.realtor.geeksales.data.db.CallRecord(
                                customerId = localId,
                                phone = wb.phone,
                                direction = wb.direction,
                                duration = wb.duration,
                                callDate = Formatter.dayToEpoch(wb.callDate?.take(10))
                                    ?: System.currentTimeMillis(),
                                note = wb.note,
                                wbCallId = wb.id
                            )
                        )
                        pulled++
                        if (pulled % 500 == 0) kotlinx.coroutines.yield()
                    }
                    if (toInsert.isNotEmpty()) repo.insertCalls(toInsert)
                }
                is WbResult.Error -> { /* 拉取失败不阻塞，推送结果已汇报 */ }
            }
            progress(0.95f, "通话记录同步：完成汇总…")
            val noNew = mirror.imported == 0 && pushed == 0 && pulled == 0
            _status.value = IOStatus(
                message = if (noNew) "未发现新通话记录（本机无新增，或已全部同步过）" else "通话记录同步完成",
                isError = pushFailed > 0,
                syncSummary = if (noNew) {
                    "本机最近无新增通话；可稍后再试，或确认「通话记录」权限已开启"
                } else {
                    "本机新增镜像 $mirror.imported 条（关联客户 $mirror.matched 条），推送云端 $pushed 条${if (pushFailed > 0) "（失败 $pushFailed 条）" else ""}，从云端拉回 $pulled 条；通话为增量同步，重复同步会自动补全历史"
                }
            )
        }
    }

    /** 只拉取云端通话记录到本地（详情页互动档案用） */
    fun pullCallsFromWorkbuddy() = doRun("拉取云端通话记录中…") {
        withContext(Dispatchers.IO) {
            if (apiKeyStore.load().isNullOrBlank()) {
                _status.value = IOStatus(message = "请先配置知行朋友圈 API Key")
                return@withContext
            }
            when (val sw = wbApi.callSyncEnabled()) {
                is WbResult.Error -> {
                    _status.value = IOStatus(message = "无法读取通话同步开关：${sw.message}")
                    return@withContext
                }
                is WbResult.Success -> if (!sw.data) {
                    _status.value = IOStatus(message = "云端通话同步开关未开启，请在网页「API 接入」页打开通话同步后再试")
                    return@withContext
                }
            }
            var pulled = 0
            when (val r = wbApi.fetchAllCalls()) {
                is WbResult.Error -> _status.value = IOStatus(message = "拉取失败：${r.message}")
                is WbResult.Success -> {
                    val customerByWbId = HashMap<String, Long>()
                    val customerByPhone = HashMap<String, Long>()
                    repo.getAll().forEach { c ->
                        if (!c.wbContactId.isNullOrBlank()) customerByWbId[c.wbContactId] = c.id
                        if (c.phoneNormalized.isNotBlank()) customerByPhone[c.phoneNormalized] = c.id
                    }
                    val toInsert = mutableListOf<com.realtor.geeksales.data.db.CallRecord>()
                    r.data.forEach { wb ->
                        if (wb.id.isBlank()) return@forEach
                        if (repo.callByWbId(wb.id) != null) return@forEach
                        val localId = wb.contactId?.let { customerByWbId[it] }
                            ?: customerByPhone[Formatter.normalizePhone(wb.phone)]
                            ?: 0L
                        toInsert.add(
                            com.realtor.geeksales.data.db.CallRecord(
                                customerId = localId,
                                phone = wb.phone,
                                direction = wb.direction,
                                duration = wb.duration,
                                callDate = Formatter.dayToEpoch(wb.callDate?.take(10))
                                    ?: System.currentTimeMillis(),
                                note = wb.note,
                                wbCallId = wb.id
                            )
                        )
                        pulled++
                        if (pulled % 500 == 0) kotlinx.coroutines.yield()
                    }
                    if (toInsert.isNotEmpty()) repo.insertCalls(toInsert)
                    _status.value = IOStatus(message = "云端通话拉取完成：$pulled 条（已存本地，可在客户详情页查看）")
                }
            }
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

    private var runningJob: kotlinx.coroutines.Job? = null

    private fun doRun(progressMsg: String, block: suspend () -> Unit) {
        // 防重入：上一次同步/导入导出未结束，忽略本次点击，避免并发重复推送
        if (runningJob?.isActive == true) return
        _status.value = IOStatus(running = true, message = progressMsg)
        runningJob = viewModelScope.launch {
            try {
                block()
            } catch (t: kotlinx.coroutines.CancellationException) {
                // 用户主动取消：显示"已取消"并继续传播取消，不得误报失败
                _status.value = IOStatus(message = "操作已取消（已完成部分已保留，未上传数据不会丢失）", isError = true)
                throw t
            } catch (t: Throwable) {
                // 异常路径必须标 isError，UI 才按失败样式提示，不能误报成功
                _status.value = IOStatus(message = "失败：${t.message ?: t.javaClass.simpleName}", isError = true)
            }
        }
    }

    /** 取消进行中的同步/导入/导出：立即停止后续批次，已上传/已写入的保留 */
    fun cancelRunning() {
        runningJob?.cancel()
    }

    /** 上报确定性进度（0..1），UI 显示进度条 + 百分比 */
    private fun progress(p: Float, msg: String) {
        _status.value = IOStatus(running = true, message = msg, progress = p.coerceIn(0f, 1f))
    }

    /** 429 / 5xx 指数退避重试（1s→2s→4s，共 3 次）；4xx 不重试（重试无用） */
    private suspend fun <T> withBackoff(send: suspend () -> WbResult<T>): WbResult<T> {
        var r = send()
        if (r is WbResult.Error) {
            val code = r.message.substringBefore(':').trim().toIntOrNull()
            if (code == 429 || (code ?: 0) >= 500) {
                repeat(3) { attempt ->
                    kotlinx.coroutines.delay(1000L shl attempt)
                    r = send()
                    if (r is WbResult.Success) return@repeat
                }
            }
        }
        return r
    }

    /** 云端「数据可追溯·批次」元信息：TMA同步 <日期时间> / source=tma / 设备与版本 */
    private fun batchMeta(): com.realtor.geeksales.data.remote.WbBatchMeta {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        return com.realtor.geeksales.data.remote.WbBatchMeta(
            batchName = "TMA同步 $now",
            source = "tma",
            deviceInfo = "TMA ${com.realtor.geeksales.BuildConfig.VERSION_NAME} / Android ${android.os.Build.VERSION.RELEASE}"
        )
    }
}