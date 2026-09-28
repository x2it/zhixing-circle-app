package com.realtor.geeksales.data.importexport

import android.content.Context
import android.net.Uri
import com.realtor.geeksales.data.db.Customer
import com.realtor.geeksales.data.db.IntentLevel
import com.realtor.geeksales.data.repo.CustomerRepository
import com.realtor.geeksales.data.schema.FieldDef
import com.realtor.geeksales.data.schema.SchemaStore
import com.realtor.geeksales.util.Formatter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExcelManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val repo: CustomerRepository,
    private val schemaStore: SchemaStore
) {
    companion object {
        val HEADERS = CsvManager.HEADERS
        const val BUILTIN_COLS = CsvManager.BUILTIN_COLS
    }

    private fun extDefs(): List<FieldDef> = schemaStore.current().filter { !it.builtin }.sortedBy { it.order }

    private fun extHeader(def: FieldDef): String = "${def.label}(${def.key})"

    private fun headerToKey(h: String): String {
        val m = Regex(".*\\(([^()]+)\\)$").find(h.trim())
        return m?.groupValues?.get(1)?.trim() ?: h.trim()
    }

    suspend fun importFrom(uri: Uri, onProgress: (Float) -> Unit = {}): ImportReport = withContext(Dispatchers.IO) {
        var total = 0; var ok = 0; var dup = 0; var invalid = 0
        // Copy to a temp file to allow POI to random-access
        val tmp = File(ctx.cacheDir, "import_${System.currentTimeMillis()}.xlsx")
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                tmp.outputStream().use { os -> ins.copyTo(os) }
            }
            onProgress(0.08f)
            FileInputStream(tmp).use { fis ->
                val wb = XSSFWorkbook(fis)
                val sheet = wb.getSheetAt(0) ?: return@use
                val totalRows = sheet.lastRowNum.toFloat()
                val rows = sheet.iterator()
                val headerRow = if (rows.hasNext()) rows.next() else null
                // 扩展列 key（表头 BUILTIN_COLS 之后，label(key) 格式）
                val extKeys = if (headerRow == null) emptyList() else
                    (BUILTIN_COLS until headerRow.lastCellNum.toInt()).map { i ->
                        headerRow.getCell(i)?.str()?.trim().orEmpty()
                    }.map { headerToKey(it) }
                        .filter { it.isNotBlank() && it !in com.realtor.geeksales.data.schema.BuiltinKeys.ALL }
                val parsed = mutableListOf<Pair<Customer, Map<String, String>>>()
                val seenInFile = HashSet<String>()
                while (rows.hasNext()) {
                    val row = rows.next()
                    total++
                    if (totalRows > 0 && total % 500 == 0) onProgress(0.08f + 0.4f * total / totalRows)
                    val name = row.getCell(0)?.str()?.trim().orEmpty()
                    val phone = row.getCell(1)?.str()?.trim().orEmpty()
                    if (name.isBlank() || !Formatter.isValidCnPhone(phone)) { invalid++; continue }
                    val norm = Formatter.normalizePhone(phone)
                    if (!seenInFile.add(norm)) { dup++; continue }
                    val level = when (row.getCell(12)?.str()?.trim()?.uppercase()) {
                        "S" -> IntentLevel.S; "A" -> IntentLevel.A; "B" -> IntentLevel.B; "C" -> IntentLevel.C
                        "D" -> IntentLevel.D; "V" -> IntentLevel.V; else -> IntentLevel.U
                    }
                    val ext = HashMap<String, String>()
                    extKeys.forEachIndexed { i, k ->
                        val v = row.getCell(BUILTIN_COLS + i)?.str()?.trim().orEmpty()
                        if (v.isNotEmpty()) ext[k] = v
                    }
                    parsed += (Customer(
                        name = name, phone = phone, phoneNormalized = norm,
                        phone2 = row.getCell(2)?.str()?.takeIf { it.isNotBlank() },
                        gender = row.getCell(3)?.str()?.takeIf { it.isNotBlank() },
                        age = row.getCell(4)?.numInt(),
                        wechat = row.getCell(5)?.str()?.takeIf { it.isNotBlank() },
                        source = row.getCell(6)?.str()?.takeIf { it.isNotBlank() },
                        areaPref = row.getCell(7)?.str()?.takeIf { it.isNotBlank() },
                        budgetMinWan = row.getCell(8)?.numInt(),
                        budgetMaxWan = row.getCell(9)?.numInt(),
                        houseType = row.getCell(10)?.str()?.takeIf { it.isNotBlank() },
                        targetProject = row.getCell(11)?.str()?.takeIf { it.isNotBlank() },
                        intentLevel = level,
                        note = row.getCell(13)?.str()?.takeIf { it.isNotBlank() },
                        nextFollowAt = row.getCell(14)?.date()?.time,
                        email = row.getCell(15)?.str()?.takeIf { it.isNotBlank() },
                        company = row.getCell(16)?.str()?.takeIf { it.isNotBlank() },
                        jobTitle = row.getCell(17)?.str()?.takeIf { it.isNotBlank() },
                        address = row.getCell(18)?.str()?.takeIf { it.isNotBlank() },
                        nickname = row.getCell(19)?.str()?.takeIf { it.isNotBlank() },
                        website = row.getCell(20)?.str()?.takeIf { it.isNotBlank() },
                        birthday = row.getCell(21)?.str()?.takeIf { it.isNotBlank() },
                        im = row.getCell(22)?.str()?.takeIf { it.isNotBlank() }
                    ) to ext)
                    if (parsed.size % 500 == 0) Thread.yield()
                }
                runCatching { wb.close() }
                // 批量查重：分批查已存在号码
                onProgress(0.52f)
                val existingPhones = HashSet<String>()
                parsed.chunked(500).forEach { chunk ->
                    chunk.forEach { (c, _) ->
                        if (repo.getByPhoneNormalized(c.phoneNormalized) != null) {
                            existingPhones.add(c.phoneNormalized)
                            dup++
                        }
                    }
                    Thread.yield()
                }
                onProgress(0.6f)
                val toInsert = parsed.filter { (c, _) -> c.phoneNormalized !in existingPhones }
                val insertChunks = toInsert.chunked(500)
                insertChunks.forEachIndexed { bi, batch ->
                    onProgress(0.6f + 0.4f * bi / insertChunks.size.coerceAtLeast(1))
                    batch.forEach { (c, ext) ->
                        val id = repo.upsert(c)
                        if (id > 0) {
                            ok++
                            if (ext.isNotEmpty()) repo.putExtFields(id, ext)
                        }
                    }
                    Thread.yield()
                }
                onProgress(1f)
            }
        }.getOrElse { t ->
            runCatching { tmp.delete() }
            return@withContext ImportReport(error = t.message ?: t.javaClass.simpleName)
        }
        runCatching { tmp.delete() }
        ImportReport(ok, dup, invalid, total)
    }

    suspend fun exportTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val all = repo.getAll()
        val rows = buildRowsWithExt(all)
        val os = ctx.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("无法写入文件，请检查存储权限")
        os.use { XlsxWriter.write(it, "客户", rows) }
        all.size
    }

    /** 流式全量导出（知行同步前自动备份用） */
    suspend fun exportToStream(customers: List<Customer>, os: java.io.OutputStream): Int =
        withContext(Dispatchers.IO) {
            val rows = buildRowsWithExt(customers)
            os.use { XlsxWriter.write(it, "客户", rows) }
            customers.size
        }

    /** 客户全字段行（时光机快照共用；快照扩展字段由 SnapshotManager 单独处理） */
    fun rowsForSnapshot(all: List<Customer>): List<List<String>> = buildRows(all)

    /** 内置列行（快照/旧逻辑用） */
    private fun buildRows(all: List<Customer>): List<List<String>> {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val rows = ArrayList<List<String>>(all.size + 1)
        rows.add(HEADERS.toList())
        all.forEach { c ->
            rows.add(
                listOf(
                    c.name, c.phone, c.phone2.orEmpty(), c.gender.orEmpty(),
                    c.age?.toString() ?: "", c.wechat.orEmpty(), c.source.orEmpty(),
                    c.areaPref.orEmpty(), c.budgetMinWan?.toString() ?: "", c.budgetMaxWan?.toString() ?: "",
                    c.houseType.orEmpty(), c.targetProject.orEmpty(), c.intentLevel.name,
                    c.note.orEmpty(), if (c.nextFollowAt == null) "" else sdf.format(java.util.Date(c.nextFollowAt)),
                    c.email.orEmpty(), c.company.orEmpty(), c.jobTitle.orEmpty(),
                    c.address.orEmpty(), c.nickname.orEmpty(), c.website.orEmpty(),
                    c.birthday.orEmpty(), c.im.orEmpty()
                )
            )
        }
        return rows
    }

    /** 内置 + 扩展列行（导出用，表头 = 内置 + label(key)） */
    private suspend fun buildRowsWithExt(all: List<Customer>): List<List<String>> {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val defs = extDefs()
        val extByCustomer = HashMap<Long, Map<String, String>>()
        all.chunked(200).forEach { chunk ->
            chunk.forEach { c -> extByCustomer[c.id] = repo.extFieldsOf(c.id) }
            Thread.yield()
        }
        val rows = ArrayList<List<String>>(all.size + 1)
        rows.add(HEADERS.toList() + defs.map { extHeader(it) })
        all.forEach { c ->
            val ext = extByCustomer[c.id].orEmpty()
            rows.add(
                listOf(
                    c.name, c.phone, c.phone2.orEmpty(), c.gender.orEmpty(),
                    c.age?.toString() ?: "", c.wechat.orEmpty(), c.source.orEmpty(),
                    c.areaPref.orEmpty(), c.budgetMinWan?.toString() ?: "", c.budgetMaxWan?.toString() ?: "",
                    c.houseType.orEmpty(), c.targetProject.orEmpty(), c.intentLevel.name,
                    c.note.orEmpty(), if (c.nextFollowAt == null) "" else sdf.format(java.util.Date(c.nextFollowAt)),
                    c.email.orEmpty(), c.company.orEmpty(), c.jobTitle.orEmpty(),
                    c.address.orEmpty(), c.nickname.orEmpty(), c.website.orEmpty(),
                    c.birthday.orEmpty(), c.im.orEmpty()
                ) + defs.map { ext[it.key].orEmpty() }
            )
        }
        return rows
    }

    suspend fun templateTo(uri: Uri): Unit = withContext(Dispatchers.IO) {
        val defs = extDefs()
        val rows = mutableListOf<List<String>>()
        rows.add(HEADERS.toList() + defs.map { extHeader(it) })
        rows.add(
            listOf("张三", "13800138000", "010-12345678", "男", "30", "zhangsan_wx", "端口-安居客", "朝阳国贸", "400", "600", "三居", "国贸·天誉", "A", "想 8 月看房", "2025-08-30", "zhangsan@example.com", "链家地产", "资深顾问", "北京朝阳区建国路 88 号", "三哥", "https://example.com/zhangsan", "1990-01-01", "zhangsan_wx") + defs.map { "示例：${it.label}" }
        )
        rows.add(
            listOf("李四", "13900139000", "", "女", "45", "", "朋友转介绍", "通州副中心", "", "900", "叠拼", "运河铭著", "B", "周末可能有时间", "", "", "", "", "", "", "", "", "") + defs.map { "" }
        )
        val os = ctx.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("无法写入文件，请检查存储权限")
        os.use { XlsxWriter.write(it, "客户模板", rows) }
    }

    private fun org.apache.poi.ss.usermodel.Cell?.str(): String = when {
        this == null -> ""
        cellType == CellType.STRING -> stringCellValue
        cellType == CellType.NUMERIC -> {
            val v = numericCellValue
            if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        }
        cellType == CellType.FORMULA -> runCatching { stringCellValue }.getOrElse { numericCellValue.toString() }
        else -> ""
    }

    private fun org.apache.poi.ss.usermodel.Cell?.numInt(): Int? = when {
        this == null -> null
        cellType == CellType.NUMERIC -> numericCellValue.toInt()
        cellType == CellType.STRING -> stringCellValue.trim().toIntOrNull()
        else -> null
    }

    private fun org.apache.poi.ss.usermodel.Cell?.date(): java.util.Date? = runCatching {
        when {
            this == null -> null
            cellType == CellType.NUMERIC && org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(this) -> dateCellValue
            else -> {
                val s = str().trim()
                if (s.isBlank()) return@runCatching null
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).parse(s)
            }
        }
    }.getOrNull()
}
