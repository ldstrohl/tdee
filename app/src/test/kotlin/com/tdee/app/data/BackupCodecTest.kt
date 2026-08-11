package com.tdee.app.data

import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupCodecTest {

    private val testUserId = "test-user-id"

    // -----------------------------------------------------------------------
    // Fixtures — one "every nullable field non-null" instance, one "every
    // nullable field null" instance, per entity.
    // -----------------------------------------------------------------------

    private fun profileFull() = UserProfileEntity(
        userId = testUserId,
        sex = Sex.FEMALE,
        birthYear = 1990,
        heightCm = 165.0,
        activityLevel = ActivityLevel.MODERATE,
        goalRateKgPerWeek = -0.25,
        goalWeightKg = 60.0,
        proteinGPerKg = 2.2,
        fatPctOfCalories = 0.3,
        dayStartHour = 4,
        smoothingWindowDays = 21,
        tdeeWindowDays = 90,
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2024-02-01T00:00:00Z"),
    )

    // UserProfileEntity's only nullable field is goalWeightKg.
    private fun profileNulls() = profileFull().copy(goalWeightKg = null)

    private fun weightFull() = WeightEntryEntity(
        id = 7,
        userId = testUserId,
        timestamp = Instant.parse("2024-03-01T08:00:00Z"),
        weightKg = 80.15,
        bodyFatPct = 18.5,
        source = WeightSource.HEALTH_CONNECT,
        healthConnectUid = "hc-uid-1",
        createdAt = Instant.parse("2024-03-01T08:00:01Z"),
    )

    private fun weightNulls() = weightFull().copy(bodyFatPct = null, healthConnectUid = null)

    private fun foodFull() = FoodEntryEntity(
        id = 9,
        userId = testUserId,
        timestamp = Instant.parse("2024-04-01T12:00:00Z"),
        rawText = "2 eggs",
        name = "Eggs",
        brand = "Acme",
        quantity = 2.0,
        unit = "each",
        grams = 100.0,
        kcal = 150.0,
        proteinG = 12.0,
        fatG = 10.0,
        carbG = 1.0,
        fdcId = "fdc-1",
        sourceDb = FoodSourceDb.USDA,
        mealId = "meal-1",
        mealName = "Breakfast",
        scaleFactor = 1.5,
        createdAt = Instant.parse("2024-04-01T12:00:01Z"),
        updatedAt = Instant.parse("2024-04-01T12:00:02Z"),
        deletedAt = Instant.parse("2024-04-02T00:00:00Z"),
    )

    private fun foodNulls() = foodFull().copy(
        brand = null,
        fdcId = null,
        mealId = null,
        mealName = null,
        deletedAt = null,
    )

    private fun targetFull() = TargetPeriodEntity(
        id = 3,
        userId = testUserId,
        startDate = LocalDate.of(2024, 5, 1),
        endDate = LocalDate.of(2024, 5, 8),
        tdeeAtCheckin = 2200.0,
        calorieTarget = 2000.0,
        proteinTargetG = 150.0,
        fatTargetG = 60.0,
        carbTargetG = 220.0,
        acceptedAt = Instant.parse("2024-05-01T00:00:00Z"),
    )

    // TargetPeriodEntity has no nullable fields; keep it identical.
    private fun targetNulls() = targetFull()

    private fun savedMealFull() = SavedMealEntity(
        id = 5,
        userId = testUserId,
        name = "Protein shake",
        items = listOf(
            SavedMealItem(name = "Whey", kcal = 120.0, proteinG = 24.0, fatG = 1.0, carbG = 2.0, grams = 30.0, factor = 1.0),
            SavedMealItem(name = "Milk", kcal = 100.0, proteinG = 8.0, fatG = 2.5, carbG = 12.0, grams = null, factor = 2.0),
        ),
        createdAt = Instant.parse("2024-06-01T00:00:00Z"),
    )

    // SavedMealEntity has no top-level nullable fields; the item-level nullable (grams) is
    // already covered by the second item above.
    private fun savedMealNulls() = savedMealFull()

    // -----------------------------------------------------------------------
    // 1. Round-trip, per entity, full + null variants
    // -----------------------------------------------------------------------

    @Test
    fun `user_profile round-trips full and null variants`() {
        for (profile in listOf(profileFull(), profileNulls())) {
            val data = emptyBackup().copy(profile = profile)
            val decoded = BackupCodec.decode(BackupCodec.encode(data, 1))
            assertEquals(profile, decoded.profile)
        }
    }

    @Test
    fun `weight_entry round-trips full and null variants`() {
        for (weight in listOf(weightFull(), weightNulls())) {
            val data = emptyBackup().copy(weights = listOf(weight))
            val decoded = BackupCodec.decode(BackupCodec.encode(data, 1))
            assertEquals(listOf(weight), decoded.weights)
        }
    }

    @Test
    fun `food_entry round-trips full and null variants`() {
        for (food in listOf(foodFull(), foodNulls())) {
            val data = emptyBackup().copy(foods = listOf(food))
            val decoded = BackupCodec.decode(BackupCodec.encode(data, 1))
            assertEquals(listOf(food), decoded.foods)
        }
    }

    @Test
    fun `target_period round-trips full and null variants`() {
        for (target in listOf(targetFull(), targetNulls())) {
            val data = emptyBackup().copy(targets = listOf(target))
            val decoded = BackupCodec.decode(BackupCodec.encode(data, 1))
            assertEquals(listOf(target), decoded.targets)
        }
    }

    @Test
    fun `saved_meal round-trips full and null variants including item list`() {
        for (meal in listOf(savedMealFull(), savedMealNulls())) {
            val data = emptyBackup().copy(savedMeals = listOf(meal))
            val decoded = BackupCodec.decode(BackupCodec.encode(data, 1))
            assertEquals(listOf(meal), decoded.savedMeals)
            assertEquals(meal.items, decoded.savedMeals.single().items)
        }
    }

    @Test
    fun `aggregate round-trip with all five tables populated`() {
        val data = BackupData(
            originUserId = testUserId,
            createdAt = 1754750400000L,
            themePreference = "DARK",
            profile = profileFull(),
            weights = listOf(weightFull(), weightNulls()),
            foods = listOf(foodFull(), foodNulls()),
            targets = listOf(targetFull()),
            savedMeals = listOf(savedMealFull()),
        )
        val decoded = BackupCodec.decode(BackupCodec.encode(data, 1))
        assertEquals(data, decoded)
    }

    // -----------------------------------------------------------------------
    // 2. Anti-drift column-coverage guard.
    //
    // Derives the expected key set from the entity itself via Java reflection
    // (`declaredFields` — a Kotlin data class's backing fields are exactly its
    // properties), so no kotlin-reflect dependency is needed. This is the point
    // of the test: adding a column to an entity for a future DB version without
    // teaching the codec about it fails HERE, loudly, instead of silently
    // dropping that column out of every backup file the user ever writes.
    // A hand-maintained expected-key list would not catch that — it would have
    // to be updated by the same person who forgot the codec.
    // -----------------------------------------------------------------------

    /**
     * A data class's properties are exactly its non-static, non-synthetic backing fields.
     * Statics are excluded because the Compose compiler plugin adds a `$stable` field to
     * every class it processes, which is not a column.
     */
    private fun entityColumns(klass: Class<*>): Set<String> =
        klass.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

    private fun emittedKeys(table: String, data: BackupData): Set<String> =
        JSONObject(BackupCodec.encode(data, 1))
            .getJSONObject("tables").getJSONArray(table).getJSONObject(0).keySetCompat()

    @Test
    fun `codec emits exactly the declared columns of every entity`() {
        val cases = listOf(
            Triple("user_profile", UserProfileEntity::class.java, emptyBackup().copy(profile = profileFull())),
            Triple("weight_entry", WeightEntryEntity::class.java, emptyBackup().copy(weights = listOf(weightFull()))),
            Triple("food_entry", FoodEntryEntity::class.java, emptyBackup().copy(foods = listOf(foodFull()))),
            Triple("target_period", TargetPeriodEntity::class.java, emptyBackup().copy(targets = listOf(targetFull()))),
            Triple("saved_meal", SavedMealEntity::class.java, emptyBackup().copy(savedMeals = listOf(savedMealFull()))),
        )
        for ((table, klass, data) in cases) {
            assertEquals(
                "$table: codec keys must match ${klass.simpleName}'s declared columns",
                entityColumns(klass),
                emittedKeys(table, data),
            )
        }
    }

    // -----------------------------------------------------------------------
    // 3-4. Version gate
    // -----------------------------------------------------------------------

    @Test
    fun `dbVersion greater than DB_VERSION throws`() {
        val json = envelope(format = 1, dbVersion = BackupCodec.DB_VERSION + 1)
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode(json) }
    }

    @Test
    fun `format greater than FORMAT_VERSION throws`() {
        val json = envelope(format = BackupCodec.FORMAT_VERSION + 1, dbVersion = BackupCodec.DB_VERSION)
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode(json) }
    }

    @Test
    fun `format absent throws`() {
        val root = JSONObject()
        root.put("dbVersion", BackupCodec.DB_VERSION)
        root.put("originUserId", testUserId)
        root.put("createdAt", 1L)
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode(root.toString()) }
    }

    // -----------------------------------------------------------------------
    // 5. Missing-column fallback to entity defaults (dbVersion < DB_VERSION)
    // -----------------------------------------------------------------------

    @Test
    fun `food_entry row missing scaleFactor decodes with default 1_0`() {
        val row = JSONObject().apply {
            put("id", 1L)
            put("userId", testUserId)
            put("timestamp", 1000L)
            put("rawText", "x")
            put("name", "x")
            put("brand", JSONObject.NULL)
            put("quantity", 1.0)
            put("unit", "each")
            put("grams", 10.0)
            put("kcal", 10.0)
            put("proteinG", 1.0)
            put("fatG", 1.0)
            put("carbG", 1.0)
            put("fdcId", JSONObject.NULL)
            put("sourceDb", "MANUAL")
            put("mealId", JSONObject.NULL)
            put("mealName", JSONObject.NULL)
            // scaleFactor intentionally omitted
            put("createdAt", 1000L)
            put("updatedAt", 1000L)
            // deletedAt intentionally omitted
        }
        val json = envelope(format = 1, dbVersion = 5, tables = mapOf("food_entry" to listOf(row)))
        val decoded = BackupCodec.decode(json)
        assertEquals(1.0, decoded.foods.single().scaleFactor, 0.0)
        assertNull(decoded.foods.single().deletedAt)
    }

    // -----------------------------------------------------------------------
    // 6. Unknown table / unknown column keys are ignored
    // -----------------------------------------------------------------------

    @Test
    fun `unknown table key and unknown column key are ignored`() {
        val data = emptyBackup().copy(weights = listOf(weightFull()))
        val json = JSONObject(BackupCodec.encode(data, 1))
        json.getJSONObject("tables").put("some_future_table", org.json.JSONArray())
        json.getJSONObject("tables").getJSONArray("weight_entry").getJSONObject(0)
            .put("someFutureColumn", "unexpected")

        val decoded = BackupCodec.decode(json.toString())
        assertEquals(listOf(weightFull()), decoded.weights)
    }

    // -----------------------------------------------------------------------
    // 7. Malformed / truncated JSON
    // -----------------------------------------------------------------------

    @Test
    fun `malformed JSON throws BackupFormatException not JSONException`() {
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode("{not json") }
    }

    @Test
    fun `truncated JSON throws BackupFormatException not JSONException`() {
        val data = emptyBackup().copy(weights = listOf(weightFull()))
        val full = BackupCodec.encode(data, 1)
        val truncated = full.substring(0, full.length / 2)
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode(truncated) }
    }

    // -----------------------------------------------------------------------
    // 8. Double fidelity
    // -----------------------------------------------------------------------

    @Test
    fun `double fidelity survives round-trip exactly`() {
        val weight = weightFull().copy(weightKg = 80.15)
        val data = emptyBackup().copy(weights = listOf(weight))
        val decoded = BackupCodec.decode(BackupCodec.encode(data, 1))
        assertEquals(80.15, decoded.weights.single().weightKg, 0.0)

        // org.json stringifies -0.0 as the integer-looking token "-0" (no decimal point), which
        // its own tokenizer then parses back as a Long 0 rather than a Double -0.0, losing the
        // sign bit. That's a limitation of the mandated org.json format, not a codec bug — JSON
        // itself has no signed-zero concept, so value equality (guaranteed by IEEE -0.0 == 0.0)
        // is the fidelity bar the codec can actually meet here.
        val profile = profileFull().copy(goalRateKgPerWeek = -0.0)
        val data2 = emptyBackup().copy(profile = profile)
        val decoded2 = BackupCodec.decode(BackupCodec.encode(data2, 1))
        assertEquals(-0.0, decoded2.profile!!.goalRateKgPerWeek, 0.0)
    }

    // -----------------------------------------------------------------------
    // 9. Absent table keys -> empty lists / null profile, no throw
    // -----------------------------------------------------------------------

    @Test
    fun `absent table keys decode to empty lists and null profile`() {
        val json = envelope(format = 1, dbVersion = BackupCodec.DB_VERSION, tables = emptyMap())
        val decoded = BackupCodec.decode(json)
        assertNull(decoded.profile)
        assertTrue(decoded.weights.isEmpty())
        assertTrue(decoded.foods.isEmpty())
        assertTrue(decoded.targets.isEmpty())
        assertTrue(decoded.savedMeals.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun JSONObject.keySetCompat(): Set<String> {
        val out = mutableSetOf<String>()
        val it = keys()
        while (it.hasNext()) out.add(it.next())
        return out
    }

    private fun emptyBackup() = BackupData(
        originUserId = testUserId,
        createdAt = 1754750400000L,
        themePreference = null,
        profile = null,
        weights = emptyList(),
        foods = emptyList(),
        targets = emptyList(),
        savedMeals = emptyList(),
    )

    /** Hand-built envelope for gate/edge-case tests that shouldn't go through [BackupCodec.encode]. */
    private fun envelope(
        format: Int?,
        dbVersion: Int,
        tables: Map<String, List<JSONObject>> = emptyMap(),
    ): String {
        val root = JSONObject()
        if (format != null) root.put("format", format)
        root.put("dbVersion", dbVersion)
        root.put("originUserId", testUserId)
        root.put("createdAt", 1754750400000L)
        val tablesJson = JSONObject()
        for ((key, rows) in tables) {
            val arr = org.json.JSONArray()
            rows.forEach { arr.put(it) }
            tablesJson.put(key, arr)
        }
        root.put("tables", tablesJson)
        return root.toString()
    }
}
