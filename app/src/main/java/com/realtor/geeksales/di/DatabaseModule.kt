package com.realtor.geeksales.di

import android.content.Context
import androidx.room.Room
import com.realtor.geeksales.data.db.AppDatabase
import com.realtor.geeksales.data.db.CustomerDao
import com.realtor.geeksales.data.db.FollowUpDao
import com.realtor.geeksales.data.db.SmsDao
import com.realtor.geeksales.data.db.TagDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDb(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*com.realtor.geeksales.data.db.AppDatabase.MIGRATIONS)
            .build()

    @Provides fun customerDao(db: AppDatabase): CustomerDao = db.customerDao()
    @Provides fun followUpDao(db: AppDatabase): FollowUpDao = db.followUpDao()
    @Provides fun tagDao(db: AppDatabase): TagDao = db.tagDao()
    @Provides fun smsDao(db: AppDatabase): SmsDao = db.smsDao()
}
