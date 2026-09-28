package com.realtor.geeksales.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 跟进结果枚举 */
enum class FollowResult {
    CONNECTED,      // 接通，有效沟通
    NOT_INTERESTED, // 明确拒绝
    NOT_REACHED,    // 未接通/无人接
    WRONG_NUMBER,   // 错号/空号
    SHUTDOWN,       // 关机/停机
    APPOINTMENT,    // 约谈/线下面谈
    PENDING         // 待跟进（未置可否）
}

@Entity(
    tableName = "follow_ups",
    foreignKeys = [
        ForeignKey(
            entity = Customer::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["customerId"]), Index(value = ["createdAt"])]
)
data class FollowUp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerId: Long,
    val result: FollowResult,
    /** 秒 */
    val durationSec: Int = 0,
    val note: String? = null,
    /** 通话录音文件路径占位，后续可扩展 */
    val recordingPath: String? = null,
    /** 是否从通话结束自动弹出登记 */
    val fromPostCall: Boolean = false,
    /** 下次跟进提醒时间 (EpochMillis) */
    val remindAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
