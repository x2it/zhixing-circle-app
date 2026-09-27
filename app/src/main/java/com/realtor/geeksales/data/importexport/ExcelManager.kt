package com.realtor.geeksales.data.importexport

import android.content.Context
import android.net.Uri
import com.realtor.geeksales.data.db.Customer
import com.realtor.geeksales.data.db.IntentLevel
import com.realtor.geeksales.data.repo.CustomerRepository
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
    private val repo: CustomerRepository
) {
    companion object {
        val HEADERS = CsvManager.HEADERS
    }

    suspend fun importFrom(uri: Uri): ImportReport = withContext(Dispatchers.IO) {
        var total = 0; var ok = 0; var dup = 0; var invalid = 0
        // Copy to a temp file to allow POI to random-access
        val tmp = File(ctx.cacheDir, "import_${System.currentTimeMillis()}.xlsx")
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                tmp.outputStream().use { os -> ins.copyTo(os) }
            }
            FileInputStream(tmp).use { fis ->
                val wb = XSSFWorkbook(fis)
                val sheet = wb.getSheetAt(0) ?: return@use
                val rows = sheet.iterator()
                if (rows.hasNext()) rows.next() // header
                val parsed = mutableListOf<Customer>()
                val seenInFile = HashSet<String>()
                while (rows.hasNext()) {
                    val row = rows.next()
                    total++
                    val name = row.getCell(0)?.str()?.trim().orEmpty()
                    val phone = row.getCell(1)?.str()?.trim().orEmpty()
                    if (name.isBlank() || !Formatter.isValidCnPhone(phone)) { invalid++; continue }
                    val norm = Formatter.normalizePhone(phone)
                    if (!seenInFile.add(norm)) { dup++; continue }
                    val level = when (row.getCell(12)?.str()?.trim()?.uppercase()) {
                        "A" -> IntentLevel.A; "B" -> IntentLevel.B; "C" -> IntentLevel.C
                        "D" -> IntentLevel.D; else -> IntentLevel.U
                    }
                    parsed += Customer(
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
                    )
                    if (parsed.size % 500 == 0) kotlinx.coroutines.yield()
                }
                runCatching { wb.close() }
                // 批量查重：分批查已存在号码
                val existingPhones = HashSet<String>()
                parsed.chunked(500).forEach { chunk ->
                    chunk.forEach { c ->
                        if (repo.getByPhoneNormalized(c.phoneNormalized) != null) {
                            existingPhones.add(c.phoneNormalized)
                            dup++
                        }
                    }
                    kotlinx.coroutines.yield()
                }
                val toInsert = parsed.filter { it.phoneNormalized !in existingPhones }
                toInsert.chunked(500).forEach { batch ->
                    ok += repo.upsertAll(batch).count { it > 0 }
                    kotlinx.coroutines.yield()
                }
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
        val rows = rowsForSnapshot(all)
        val os = ctx.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("无法写入文件，请检查存储权限")
        os.use { XlsxWriter.write(it, "客户", rows) }
        all.size
    }

    /** 流式全量导出（知行同步前自动备份用） */
    suspend fun exportToStream(customers: List<Customer>, os: java.io.OutputStream): Int =
        withContext(Dispatchers.IO) {
            val rows = rowsForSnapshot(customers)
            os.use { XlsxWriter.write(it, "客户", rows) }
            customers.size
        }

    /** 客户全字段行（时光机快照与导出共用） */
    fun rowsForSnapshot(all: List<Customer>): List<List<String>> = buildRows(all)

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

    suspend fun templateTo(uri: Uri): Unit = withContext(Dispatchers.IO) {
        val rows = listOf(
            HEADERS.toList(),
            listOf("张三", "13800138000", "010-12345678", "男", "30", "zhangsan_wx", "端口-安居客", "朝阳国贸", "400", "600", "三居", "国贸·天誉", "A", "想 8 月看房", "2025-08-30", "zhangsan@example.com", "链家地产", "资深顾问", "北京朝阳区建国路 88 号", "三哥", "https://example.com/zhangsan", "1990-01-01", "zhangsan_wx"),
            listOf("李四", "13900139000", "", "女", "45", "", "朋友转介绍", "通州副中心", "", "900", "叠拼", "运河铭著", "B", "周末可能有时间", "", "", "", "", "", "", "", "", "")
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
