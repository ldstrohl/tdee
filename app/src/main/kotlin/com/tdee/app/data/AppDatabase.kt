package com.tdee.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        UserProfileEntity::class,
        WeightEntryEntity::class,
        FoodEntryEntity::class,
        TargetPeriodEntity::class,
        WeightTrendCacheEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userProfileDao(): UserProfileDao
    abstract fun weightEntryDao(): WeightEntryDao
    abstract fun foodEntryDao(): FoodEntryDao
    abstract fun targetPeriodDao(): TargetPeriodDao
    abstract fun weightTrendCacheDao(): WeightTrendCacheDao
}
