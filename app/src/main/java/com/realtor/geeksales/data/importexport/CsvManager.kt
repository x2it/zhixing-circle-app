package com.realtor.geeksales.data.importexport

import android.content.Context
import android.net.Uri
import com.realtor.geeksales.data.db.Customer
import com.realtor.geeksales.data.db.IntentLevel
import com.realtor.geeksales.data.repo.CustomerRepository
import com.realtor.geeksales.util.Formatter
import com.opencsv.CSVReaderBuilder
import com.opencsv.CSVWriterBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Singleton

data class ImportReport(
    val success: Int = 0,
    val duplicated: Int = 0,
    val invalid: Int = 0,
    val total: Int = 0,
    /** 非空表示导入失败（文件无法读取/解析），success 等字段无意义 */
    val error: String? = null
)

@Singleton
class CsvManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val repo: CustomerRepository
) {
    companion object {
        val HEADERS = arrayOf(
            "姓名", "手机号", "备用电话", "性别", "年龄",
            "微信", "来源", "意向区域", "预算(万)下限", "预算(万)上限",
            "房型", "意向楼盘", "意向等级(A/B/C/D/U)", "备注", "下次跟进(YYYY-MM-DD)",
            // v1.7：通讯录对齐扩展列（可选，旧模板可继续使用）
            "邮箱", "公司", "职位", "地址", "昵称", "网站", "生日(YYYY-MM-DD)", "即时消息"
        )
    }

    suspend fun importFrom(uri: Uri): ImportReport = withContext(Dispatchers.IO) {
        var total = 0
        var ok = 0
        var dup = 0
        var invalid = 0
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val reader = CSVReaderBuilder(InputStreamReader(ins, Charsets.UTF_8))
                    .withSkipLines(1)
                    .build()
                // 先读完文件做归一化与文件内去重，避免逐行查库造成假死
                val parsed = mutableListOf<Customer>()
                val seenInFile = HashSet<String>()
                var row: Array<String>?
                while (reader.readNext().also { row = it } != null) {
                    val cells = row!!
                    total++
                    val name = cells.getOrNull(0).orEmpty().trim()
                    val phone = cells.getOrNull(1).orEmpty().trim()
                    if (name.isBlank() || !Formatter.isValidCnPhone(phone)) {
                        invalid++
                        continue
                    }
                    val norm = Formatter.normalizePhone(phone)
                    if (!seenInFile.add(norm)) { dup++; continue }
                    val phone2 = cells.getOrNull(2)?.trim().takeIf { !it.isNullOrBlank() }
                    val gender = cells.getOrNull(3)?.trim().takeIf { !it.isNullOrBlank() }
                    val age = cells.getOrNull(4)?.trim()?.toIntOrNull()
                    val wechat = cells.getOrNull(5)?.trim().takeIf { !it.isNullOrBlank() }
                    val source = cells.getOrNull(6)?.trim().takeIf { !it.isNullOrBlank() }
                    val areaPref = cells.getOrNull(7)?.trim().takeIf { !it.isNullOrBlank() }
                    val budgetMin = cells.getOrNull(8)?.trim()?.toIntOrNull()
                    val budgetMax = cells.getOrNull(9)?.trim()?.toIntOrNull()
                    val houseType = cells.getOrNull(10)?.trim().takeIf { !it.isNullOrBlank() }
                    val targetProject = cells.getOrNull(11)?.trim().takeIf { !it.isNullOrBlank() }
                    val level = when (cells.getOrNull(12)?.trim()?.uppercase()) {
                        "A" -> IntentLevel.A
                        "B" -> IntentLevel.B
                        "C" -> IntentLevel.C
                        "D" -> IntentLevel.D
                        else -> IntentLevel.U
                    }
                    val note = cells.getOrNull(13)?.trim().takeIf { !it.isNullOrBlank() }
                    val nextAt = cells.getOrNull(14)?.trim()?.let { s ->
                        runCatching {
                            val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).parse(s)
                            d?.time
                        }.getOrNull()
                    }
                    val email = cells.getOrNull(15)?.trim().takeIf { !it.isNullOrBlank() }
                    val company = cells.getOrNull(16)?.trim().takeIf { !it.isNullOrBlank() }
                    val jobTitle = cells.getOrNull(17)?.trim().takeIf { !it.isNullOrBlank() }
                    val address = cells.getOrNull(18)?.trim().takeIf { !it.isNullOrBlank() }
                    val nickname = cells.getOrNull(19)?.trim().takeIf { !it.isNullOrBlank() }
                    val website = cells.getOrNull(20)?.trim().takeIf { !it.isNullOrBlank() }
                    val birthday = cells.getOrNull(21)?.trim().takeIf { !it.isNullOrBlank() }
                    val im = cells.getOrNull(22)?.trim().takeIf { !it.isNullOrBlank() }
                    parsed += Customer(
                        name = name, phone = phone, phoneNormalized = norm,
                        phone2 = phone2, gender = gender, age = age, wechat = wechat,
                        source = source, areaPref = areaPref,
                        budgetMinWan = budgetMin, budgetMaxWan = budgetMax,
                        houseType = houseType, targetProject = targetProject,
                        intentLevel = level, note = note, nextFollowAt = nextAt,
                        email = email, company = company, jobTitle = jobTitle,
                        address = address, nickname = nickname, website = website,
                        birthday = birthday, im = im
                    )
                    if (parsed.size % 500 == 0) kotlinx.coroutines.yield()
                }
                // 批量查重：分批查已存在号码，500条yield一次
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
            return@withContext ImportReport(error = t.message ?: t.javaClass.simpleName)
        }
        ImportReport(ok, dup, invalid, total)
    }

    suspend fun exportTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val all = repo.getAll()
        val os = ctx.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("无法写入文件，请检查存储权限")
        os.use {
            os.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // UTF-8 BOM for Excel
            val writer = CSVWriterBuilder(OutputStreamWriter(os, Charsets.UTF_8)).build()
            writer.writeNext(HEADERS)
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            all.forEach { c ->
                writer.writeNext(arrayOf(
                    c.name, c.phone, c.phone2.orEmpty(),
                    c.gender.orEmpty(), c.age?.toString().orEmpty(),
                    c.wechat.orEmpty(), c.source.orEmpty(), c.areaPref.orEmpty(),
                    c.budgetMinWan?.toString().orEmpty(), c.budgetMaxWan?.toString().orEmpty(),
                    c.houseType.orEmpty(), c.targetProject.orEmpty(),
                    c.intentLevel.name, c.note.orEmpty(),
                    if (c.nextFollowAt == null) "" else sdf.format(java.util.Date(c.nextFollowAt)),
                    c.email.orEmpty(), c.company.orEmpty(), c.jobTitle.orEmpty(),
                    c.address.orEmpty(), c.nickname.orEmpty(), c.website.orEmpty(),
                    c.birthday.orEmpty(), c.im.orEmpty()
                ))
            }
            writer.flushQuietly()
        }
        all.size
    }
}
