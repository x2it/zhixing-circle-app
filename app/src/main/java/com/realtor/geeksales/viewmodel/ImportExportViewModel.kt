package com.realtor.geeksales.viewmodel

import android.content.ContentProviderOperation
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.realtor.geeksales.data.db.Customer
import com.realtor.geeksales.data.db.IntentLevel
import com.realtor.geeksales.data.importexport.CsvManager
import com.realtor.geeksales.data.importexport.ExcelManager
import com.realtor.geeksales.data.importexport.ImportReport
import com.realtor.geeksales.data.repo.CustomerRepository
import com.realtor.geeksales.util.Formatter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class IOStatus(
    val running: Boolean = false,
    val message: String = "",
    val report: ImportReport? = null,
    val exportCount: Int? = null
)

@HiltViewModel
class ImportExportViewModel @Inject constructor(
    private val csv: CsvManager,
    private val excel: ExcelManager,
    private val repo: CustomerRepository,
    @ApplicationContext private val ctx: Context
) : ViewModel() {

    private val _status = MutableStateFlow(IOStatus(message = "idle"))
    val status: StateFlow<IOStatus> = _status

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