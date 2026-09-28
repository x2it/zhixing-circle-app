package com.realtor.geeksales.data.importexport

import android.content.Context
import android.provider.CallLog
import com.realtor.geeksales.data.db.CallRecord
import com.realtor.geeksales.data.repo.CustomerRepository
import com.realtor.geeksales.util.Formatter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class CallLogReport(
    val total: Int = 0,
    val imported: Int = 0,
    val matched: Int = 0,
    val error: String? = null
)

/**
 * 通话记录读取：读取系统通话记录（需 READ_CALL_LOG 权限），增量镜像到本地 call_records 表。
 * 用途：①本地通话备份（不依赖云端）②作为推送云端的待上传队列 ③客户详情「互动档案」。
 * 隐私说明：通话记录仅用于用户自己的备份与同步，不离开用户指定的云端账号。
 */
@Singleton
class CallLogReader @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val repo: CustomerRepository
) {
    companion object {
        private const val PREFS = "tma_prefs"
        private const val KEY_LAST_DATE = "call_log_last_date"
        /** 单次最多镜像最近 2000 条，避免首次全量卡顿 */
        private const val MAX_SCAN = 2000
    }

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 增量镜像系统通话记录到本地；无新记录返回 imported=0 */
    suspend fun mirrorLocalCallLog(): CallLogReport = withContext(Dispatchers.IO) {
        var total = 0
        var imported = 0
        var matched = 0
        runCatching {
            val projection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )
            val last = prefs.getLong(KEY_LAST_DATE, 0L)
            val selection = if (last > 0) "${CallLog.Calls.DATE} > ?" else null
            val args = if (last > 0) arrayOf(last.toString()) else null
            // 时间倒序取最近 MAX_SCAN 条，避免首次全量卡顿
            val rows = mutableListOf<Array<Any?>>()
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, selection, args,
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                val nCol = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val tCol = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dCol = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durCol = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                while (c.moveToNext() && rows.size < MAX_SCAN) {
                    rows.add(
                        arrayOf(
                            c.getString(nCol),
                            c.getInt(tCol),
                            c.getLong(dCol),
                            c.getLong(durCol)
                        )
                    )
                    total++
                }
            }
            if (rows.isEmpty()) return@withContext CallLogReport(total = total)
            // 客户号码映射（归一化）
            val customerByPhone = HashMap<String, Long>()
            repo.getAll().forEach { c ->
                if (c.phoneNormalized.isNotBlank()) customerByPhone[c.phoneNormalized] = c.id
                if (!c.phone2.isNullOrBlank()) customerByPhone[Formatter.normalizePhone(c.phone2)] = c.id
            }
            // 已存在记录去重键：phone|date|duration|direction（本地最近 5000 条）
            val existingKeys = HashSet<String>()
            repo.recentCalls(5000).forEach {
                existingKeys.add("${Formatter.normalizePhone(it.phone)}|${it.callDate}|${it.duration}|${it.direction}")
            }
            val toInsert = mutableListOf<CallRecord>()
            rows.forEach { r ->
                val phoneRaw = (r[0] as? String).orEmpty()
                if (phoneRaw.isBlank()) return@forEach
                val type = r[1] as? Int ?: 0
                val date = r[2] as? Long ?: 0L
                val duration = (r[3] as? Long) ?: 0L
                if (date <= 0L) return@forEach
                val direction = when (type) {
                    CallLog.Calls.INCOMING_TYPE -> "in"
                    CallLog.Calls.OUTGOING_TYPE -> "out"
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    else -> "missed"
                }
                val norm = Formatter.normalizePhone(phoneRaw)
                val key = "$norm|$date|$duration|$direction"
                if (key in existingKeys) return@forEach
                existingKeys.add(key)
                toInsert.add(
                    CallRecord(
                        customerId = customerByPhone[norm] ?: 0L,
                        phone = phoneRaw,
                        direction = direction,
                        duration = duration,
                        callDate = date,
                        wbCallId = null
                    )
                )
                imported++
                if (imported % 500 == 0) kotlinx.coroutines.yield()
            }
            if (toInsert.isNotEmpty()) {
                repo.insertCalls(toInsert)
                matched = toInsert.count { it.customerId > 0L }
            }
            val maxDate = rows.maxOfOrNull { it[2] as? Long ?: 0L } ?: last
            if (maxDate > last) prefs.edit().putLong(KEY_LAST_DATE, maxDate).apply()
        }.getOrElse { t ->
            return@withContext CallLogReport(error = t.message ?: t.javaClass.simpleName)
        }
        CallLogReport(total = total, imported = imported, matched = matched)
    }
}
