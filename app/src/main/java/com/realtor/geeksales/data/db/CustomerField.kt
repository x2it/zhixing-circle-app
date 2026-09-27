package com.realtor.geeksales.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 客户扩展字段（EAV 存储）。
 * 用于承载线上模板（schema）中非内置的任意自定义字段，实现"万物可插"：
 * 线上模板加什么字段，App 表单/详情/导入导出就跟随显示什么，无需改代码。
 */
@Entity(
    tableName = "customer_fields",
    indices = [Index(value = ["customerId", "fieldKey"], unique = true)]
)
data class CustomerField(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerId: Long,
    /** 字段 key（与 schema FieldDef.key 一致，线上 key 或内置 key） */
    val fieldKey: String,
    /** 字段值（文本存储；数字/日期均转字符串） */
    val fieldValue: String = ""
)
