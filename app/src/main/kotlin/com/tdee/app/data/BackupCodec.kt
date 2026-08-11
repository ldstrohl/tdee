package com.tdee.app.data

import com.tdee.app.ui.theme.ThemePreference
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Thrown by [BackupCodec.decode] for any backup file it cannot safely read. */
class BackupFormatException(message: String) : Exception(message)

/** In-memory shape of a Drive backup, one row per table (already scoped to a single user). */
data class BackupData(
    val originUserId: String,
    val createdAt: Long,
    val themePreference: String?,
    val profile: UserProfileEntity?,
    val weights: List<WeightEntryEntity>,
    val foods: List<FoodEntryEntity>,
    val targets: List<TargetPeriodEntity>,
    val savedMeals: List<SavedMealEntity>,
)

/**
 * JSON codec for [BackupData], used for Google Drive backup/restore.
 *
 * This is a pure column mirror: every value that already has a canonical Room mapping (Instant,
 * LocalDate, enums, [SavedMealEntity.items]) goes through [Converters] rather than a second,
 * possibly-drifting serialization path.
 */
object BackupCodec {

    const val FORMAT_VERSION = 1

    /**
     * Mirrors [AppDatabase]'s `@Database(version = ...)`. Bump this alongside that annotation —
     * the annotation value isn't readable at runtime without reflection.
     */
    const val DB_VERSION = 6

    private val converters = Converters()

    fun encode(data: BackupData, appVersionCode: Int): String {
        val root = JSONObject()
        root.put("format", FORMAT_VERSION)
        root.put("dbVersion", DB_VERSION)
        root.put("appVersionCode", appVersionCode)
        root.put("createdAt", data.createdAt)
        root.put("originUserId", data.originUserId)

        val prefs = JSONObject()
        prefs.put("theme_preference", data.themePreference ?: JSONObject.NULL)
        root.put("prefs", prefs)

        val tables = JSONObject()
        tables.put(
            "user_profile",
            JSONArray().also { arr -> data.profile?.let { arr.put(profileToJson(it)) } },
        )
        tables.put("weight_entry", JSONArray().also { arr -> data.weights.forEach { arr.put(weightToJson(it)) } })
        tables.put("food_entry", JSONArray().also { arr -> data.foods.forEach { arr.put(foodToJson(it)) } })
        tables.put("target_period", JSONArray().also { arr -> data.targets.forEach { arr.put(targetToJson(it)) } })
        tables.put("saved_meal", JSONArray().also { arr -> data.savedMeals.forEach { arr.put(savedMealToJson(it)) } })
        root.put("tables", tables)

        return root.toString()
    }

    fun decode(json: String): BackupData {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw BackupFormatException("This backup file is corrupted and can't be read.")
        }

        try {
            if (!root.has("format") || root.isNull("format")) {
                throw BackupFormatException(NEWER_APP_MESSAGE)
            }
            if (root.getInt("format") > FORMAT_VERSION) {
                throw BackupFormatException(NEWER_APP_MESSAGE)
            }
            if (root.optInt("dbVersion", 0) > DB_VERSION) {
                throw BackupFormatException(NEWER_APP_MESSAGE)
            }

            val originUserId = root.getString("originUserId")
            val createdAt = root.getLong("createdAt")

            val themePreference = root.optJSONObject("prefs")
                ?.optString("theme_preference", "")
                ?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { ThemePreference.valueOf(it) }.getOrNull() }
                ?.name

            val tables = root.optJSONObject("tables") ?: JSONObject()

            val profileArr = tables.optJSONArray("user_profile")
            val profile = if (profileArr != null && profileArr.length() > 0) {
                profileFromJson(profileArr.getJSONObject(0))
            } else {
                null
            }

            return BackupData(
                originUserId = originUserId,
                createdAt = createdAt,
                themePreference = themePreference,
                profile = profile,
                weights = tables.optJSONArray("weight_entry").toEntityList(::weightFromJson),
                foods = tables.optJSONArray("food_entry").toEntityList(::foodFromJson),
                targets = tables.optJSONArray("target_period").toEntityList(::targetFromJson),
                savedMeals = tables.optJSONArray("saved_meal").toEntityList(::savedMealFromJson),
            )
        } catch (e: BackupFormatException) {
            throw e
        } catch (e: JSONException) {
            throw BackupFormatException("This backup file is corrupted and can't be read.")
        }
    }

    private fun <T> JSONArray?.toEntityList(fromJson: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).map { fromJson(getJSONObject(it)) }
    }

    private const val NEWER_APP_MESSAGE = "This backup was made by a newer version of TDEE. Update the app first."

    // --- user_profile ---

    private fun profileToJson(e: UserProfileEntity): JSONObject = JSONObject().apply {
        put("userId", e.userId)
        put("sex", e.sex.name)
        put("birthYear", e.birthYear)
        put("heightCm", e.heightCm)
        put("activityLevel", e.activityLevel.name)
        put("goalRateKgPerWeek", e.goalRateKgPerWeek)
        put("goalWeightKg", e.goalWeightKg ?: JSONObject.NULL)
        put("proteinGPerKg", e.proteinGPerKg)
        put("fatPctOfCalories", e.fatPctOfCalories)
        put("dayStartHour", e.dayStartHour)
        put("smoothingWindowDays", e.smoothingWindowDays)
        put("tdeeWindowDays", e.tdeeWindowDays)
        put("createdAt", converters.toInstant(e.createdAt))
        put("updatedAt", converters.toInstant(e.updatedAt))
    }

    private fun profileFromJson(o: JSONObject): UserProfileEntity = UserProfileEntity(
        userId = o.getString("userId"),
        sex = Sex.valueOf(o.getString("sex")),
        birthYear = o.getInt("birthYear"),
        heightCm = o.getDouble("heightCm"),
        activityLevel = ActivityLevel.valueOf(o.getString("activityLevel")),
        goalRateKgPerWeek = o.getDouble("goalRateKgPerWeek"),
        goalWeightKg = if (o.isNull("goalWeightKg")) null else o.getDouble("goalWeightKg"),
        proteinGPerKg = o.optDouble("proteinGPerKg", 2.0),
        fatPctOfCalories = o.optDouble("fatPctOfCalories", 0.25),
        dayStartHour = o.optInt("dayStartHour", 0),
        smoothingWindowDays = o.optInt("smoothingWindowDays", 14),
        tdeeWindowDays = o.optInt("tdeeWindowDays", 14),
        createdAt = converters.fromInstant(o.getLong("createdAt"))!!,
        updatedAt = converters.fromInstant(o.getLong("updatedAt"))!!,
    )

    // --- weight_entry ---

    private fun weightToJson(e: WeightEntryEntity): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("userId", e.userId)
        put("timestamp", converters.toInstant(e.timestamp))
        put("weightKg", e.weightKg)
        put("bodyFatPct", e.bodyFatPct ?: JSONObject.NULL)
        put("source", e.source.name)
        put("healthConnectUid", e.healthConnectUid ?: JSONObject.NULL)
        put("createdAt", converters.toInstant(e.createdAt))
    }

    private fun weightFromJson(o: JSONObject): WeightEntryEntity = WeightEntryEntity(
        id = o.optLong("id", 0),
        userId = o.getString("userId"),
        timestamp = converters.fromInstant(o.getLong("timestamp"))!!,
        weightKg = o.getDouble("weightKg"),
        bodyFatPct = if (o.isNull("bodyFatPct")) null else o.getDouble("bodyFatPct"),
        source = WeightSource.valueOf(o.getString("source")),
        healthConnectUid = if (o.isNull("healthConnectUid")) null else o.getString("healthConnectUid"),
        createdAt = converters.fromInstant(o.getLong("createdAt"))!!,
    )

    // --- food_entry ---

    private fun foodToJson(e: FoodEntryEntity): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("userId", e.userId)
        put("timestamp", converters.toInstant(e.timestamp))
        put("rawText", e.rawText)
        put("name", e.name)
        put("brand", e.brand ?: JSONObject.NULL)
        put("quantity", e.quantity)
        put("unit", e.unit)
        put("grams", e.grams)
        put("kcal", e.kcal)
        put("proteinG", e.proteinG)
        put("fatG", e.fatG)
        put("carbG", e.carbG)
        put("fdcId", e.fdcId ?: JSONObject.NULL)
        put("sourceDb", e.sourceDb.name)
        put("mealId", e.mealId ?: JSONObject.NULL)
        put("mealName", e.mealName ?: JSONObject.NULL)
        put("scaleFactor", e.scaleFactor)
        put("createdAt", converters.toInstant(e.createdAt))
        put("updatedAt", converters.toInstant(e.updatedAt))
        put("deletedAt", e.deletedAt?.let { converters.toInstant(it) } ?: JSONObject.NULL)
    }

    private fun foodFromJson(o: JSONObject): FoodEntryEntity = FoodEntryEntity(
        id = o.optLong("id", 0),
        userId = o.getString("userId"),
        timestamp = converters.fromInstant(o.getLong("timestamp"))!!,
        rawText = o.getString("rawText"),
        name = o.getString("name"),
        brand = if (o.isNull("brand")) null else o.getString("brand"),
        quantity = o.getDouble("quantity"),
        unit = o.getString("unit"),
        grams = o.getDouble("grams"),
        kcal = o.getDouble("kcal"),
        proteinG = o.getDouble("proteinG"),
        fatG = o.getDouble("fatG"),
        carbG = o.getDouble("carbG"),
        fdcId = if (o.isNull("fdcId")) null else o.getString("fdcId"),
        sourceDb = FoodSourceDb.valueOf(o.getString("sourceDb")),
        mealId = if (o.isNull("mealId")) null else o.getString("mealId"),
        mealName = if (o.isNull("mealName")) null else o.getString("mealName"),
        scaleFactor = o.optDouble("scaleFactor", 1.0),
        createdAt = converters.fromInstant(o.getLong("createdAt"))!!,
        updatedAt = converters.fromInstant(o.getLong("updatedAt"))!!,
        deletedAt = if (o.isNull("deletedAt")) null else converters.fromInstant(o.getLong("deletedAt")),
    )

    // --- target_period ---

    private fun targetToJson(e: TargetPeriodEntity): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("userId", e.userId)
        put("startDate", converters.toLocalDate(e.startDate))
        put("endDate", converters.toLocalDate(e.endDate))
        put("tdeeAtCheckin", e.tdeeAtCheckin)
        put("calorieTarget", e.calorieTarget)
        put("proteinTargetG", e.proteinTargetG)
        put("fatTargetG", e.fatTargetG)
        put("carbTargetG", e.carbTargetG)
        put("acceptedAt", converters.toInstant(e.acceptedAt))
    }

    private fun targetFromJson(o: JSONObject): TargetPeriodEntity = TargetPeriodEntity(
        id = o.optLong("id", 0),
        userId = o.getString("userId"),
        startDate = converters.fromLocalDate(o.getLong("startDate"))!!,
        endDate = converters.fromLocalDate(o.getLong("endDate"))!!,
        tdeeAtCheckin = o.getDouble("tdeeAtCheckin"),
        calorieTarget = o.getDouble("calorieTarget"),
        proteinTargetG = o.getDouble("proteinTargetG"),
        fatTargetG = o.getDouble("fatTargetG"),
        carbTargetG = o.getDouble("carbTargetG"),
        acceptedAt = converters.fromInstant(o.getLong("acceptedAt"))!!,
    )

    // --- saved_meal ---

    private fun savedMealToJson(e: SavedMealEntity): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("userId", e.userId)
        put("name", e.name)
        put("items", converters.fromSavedMealItems(e.items))
        put("createdAt", converters.toInstant(e.createdAt))
    }

    private fun savedMealFromJson(o: JSONObject): SavedMealEntity = SavedMealEntity(
        id = o.optLong("id", 0),
        userId = o.getString("userId"),
        name = o.getString("name"),
        items = converters.toSavedMealItems(o.getString("items")),
        createdAt = converters.fromInstant(o.getLong("createdAt"))!!,
    )
}
