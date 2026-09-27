package com.realtor.geeksales.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun intentLevelToStr(v: IntentLevel): String = v.name
    @TypeConverter fun strToIntentLevel(s: String): IntentLevel = runCatching { IntentLevel.valueOf(s) }.getOrDefault(IntentLevel.U)

    @TypeConverter fun followResultToStr(v: FollowResult): String = v.name
    @TypeConverter fun strToFollowResult(s: String): FollowResult = runCatching { FollowResult.valueOf(s) }.getOrDefault(FollowResult.PENDING)
}

@Database(
    entities = [
        Customer::class,
        FollowUp::class,
        Tag::class,
        CustomerTagMap::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customerDao(): CustomerDao
    abstract fun followUpDao(): FollowUpDao
    abstract fun tagDao(): TagDao

    companion object {
        const val NAME = "geek_sales.db"

        val MIGRATIONS = arrayOf<androidx.room.migration.Migration>(
            // v1 → v2：新增通讯录对齐字段（email/company/jobTitle/address/nickname/website/birthday/im）
            object : androidx.room.migration.Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE customers ADD COLUMN email TEXT")
                    db.execSQL("ALTER TABLE customers ADD COLUMN company TEXT")
                    db.execSQL("ALTER TABLE customers ADD COLUMN jobTitle TEXT")
                    db.execSQL("ALTER TABLE customers ADD COLUMN address TEXT")
                    db.execSQL("ALTER TABLE customers ADD COLUMN nickname TEXT")
                    db.execSQL("ALTER TABLE customers ADD COLUMN website TEXT")
                    db.execSQL("ALTER TABLE customers ADD COLUMN birthday TEXT")
                    db.execSQL("ALTER TABLE customers ADD COLUMN im TEXT")
                }
            }
        )
    }
}
