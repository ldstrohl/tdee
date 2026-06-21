package com.tdee.app.data

import androidx.room.TypeConverter
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex
import java.time.Instant
import java.time.LocalDate

class Converters {

    // --- Instant ---

    @TypeConverter
    fun fromInstant(value: Long?): Instant? = value?.let { Instant.ofEpochMilli(it) }

    @TypeConverter
    fun toInstant(instant: Instant?): Long? = instant?.toEpochMilli()

    // --- LocalDate ---

    @TypeConverter
    fun fromLocalDate(value: Long?): LocalDate? = value?.let { LocalDate.ofEpochDay(it) }

    @TypeConverter
    fun toLocalDate(date: LocalDate?): Long? = date?.toEpochDay()

    // --- Domain enums (stored as String name) ---

    @TypeConverter
    fun fromSex(value: String?): Sex? = value?.let { Sex.valueOf(it) }

    @TypeConverter
    fun toSex(sex: Sex?): String? = sex?.name

    @TypeConverter
    fun fromActivityLevel(value: String?): ActivityLevel? = value?.let { ActivityLevel.valueOf(it) }

    @TypeConverter
    fun toActivityLevel(level: ActivityLevel?): String? = level?.name

    // --- App-local enums ---

    @TypeConverter
    fun fromWeightSource(value: String?): WeightSource? = value?.let { WeightSource.valueOf(it) }

    @TypeConverter
    fun toWeightSource(source: WeightSource?): String? = source?.name

    @TypeConverter
    fun fromFoodSourceDb(value: String?): FoodSourceDb? = value?.let { FoodSourceDb.valueOf(it) }

    @TypeConverter
    fun toFoodSourceDb(source: FoodSourceDb?): String? = source?.name

    @TypeConverter
    fun fromTdeeMethod(value: String?): TdeeMethodDb? = value?.let { TdeeMethodDb.valueOf(it) }

    @TypeConverter
    fun toTdeeMethod(method: TdeeMethodDb?): String? = method?.name
}

enum class WeightSource { HEALTH_CONNECT, MANUAL }
enum class FoodSourceDb { USDA, MANUAL }
enum class TdeeMethodDb { FORMULA, BLEND, EMPIRICAL }
