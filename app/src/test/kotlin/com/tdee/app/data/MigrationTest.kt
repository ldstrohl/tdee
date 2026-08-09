package com.tdee.app.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Migration tests for [MIGRATION_4_5] and [MIGRATION_5_6].
 *
 * Uses [FrameworkSQLiteOpenHelperFactory] directly (not [MigrationTestHelper]) so no schema-export
 * asset path needs to be added to the build config. Tests that:
 *   - The migration SQL executes without error.
 *   - Rows inserted at the prior version survive the migration.
 *   - The new column exists with the expected value for pre-migration rows.
 *
 * Note: [MigrationTestHelper] was attempted but requires schema JSON files on the Robolectric
 * asset path (src/test/assets/), which needs a build.gradle sourceSets change not included in
 * this task's scope. The direct [FrameworkSQLiteOpenHelperFactory] approach gives equivalent
 * coverage without that constraint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val context = RuntimeEnvironment.getApplication()
    private val dbName = "migration-4-5-test.db"
    private val dbName56 = "migration-5-6-test.db"

    @Before
    fun setup() {
        context.deleteDatabase(dbName)
        context.deleteDatabase(dbName56)
    }

    @Test
    fun `MIGRATION_4_5 adds mealName column and preserves existing rows`() {
        val factory = FrameworkSQLiteOpenHelperFactory()

        // ── Step 1: create a v4 database and insert one food_entry row ──────────
        val helperV4 = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `food_entry` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `userId` TEXT NOT NULL,
                                `timestamp` INTEGER NOT NULL,
                                `rawText` TEXT NOT NULL,
                                `name` TEXT NOT NULL,
                                `brand` TEXT,
                                `quantity` REAL NOT NULL,
                                `unit` TEXT NOT NULL,
                                `grams` REAL NOT NULL,
                                `kcal` REAL NOT NULL,
                                `proteinG` REAL NOT NULL,
                                `fatG` REAL NOT NULL,
                                `carbG` REAL NOT NULL,
                                `fdcId` TEXT,
                                `sourceDb` TEXT NOT NULL,
                                `mealId` TEXT,
                                `createdAt` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL,
                                `deletedAt` INTEGER
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helperV4.writableDatabase.use { db ->
            db.execSQL(
                """
                INSERT INTO food_entry
                    (userId, timestamp, rawText, name, quantity, unit, grams, kcal, proteinG, fatG, carbG, sourceDb, createdAt, updatedAt)
                VALUES
                    ('user1', 1000000000, 'pasta', 'Pasta', 1.0, 'serving', 200.0, 350.0, 12.0, 3.0, 68.0, 'MANUAL', 1000000000, 1000000000)
                """.trimIndent()
            )
        }
        helperV4.close()

        // ── Step 2: re-open at v5, running MIGRATION_4_5 ─────────────────────────
        val helperV5 = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        if (oldVersion < 5) MIGRATION_4_5.migrate(db)
                    }
                })
                .build()
        )

        // ── Step 3: assert row survived and mealName is NULL ──────────────────────
        helperV5.writableDatabase.use { db ->
            val cursor = db.query("SELECT id, name, mealName FROM food_entry WHERE userId = 'user1'")
            assertEquals("Pre-migration row must survive", 1, cursor.count)
            cursor.moveToFirst()
            assertEquals("Pasta", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            val mealNameIdx = cursor.getColumnIndexOrThrow("mealName")
            assertTrue("mealName must be NULL for pre-migration rows", cursor.isNull(mealNameIdx))
            cursor.close()
        }
        helperV5.close()
    }

    @Test
    fun `MIGRATION_5_6 adds scaleFactor column defaulting to 1point0 and preserves existing rows`() {
        val factory = FrameworkSQLiteOpenHelperFactory()

        // ── Step 1: create a v5 database and insert one food_entry row ──────────
        val helperV5 = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName56)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `food_entry` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `userId` TEXT NOT NULL,
                                `timestamp` INTEGER NOT NULL,
                                `rawText` TEXT NOT NULL,
                                `name` TEXT NOT NULL,
                                `brand` TEXT,
                                `quantity` REAL NOT NULL,
                                `unit` TEXT NOT NULL,
                                `grams` REAL NOT NULL,
                                `kcal` REAL NOT NULL,
                                `proteinG` REAL NOT NULL,
                                `fatG` REAL NOT NULL,
                                `carbG` REAL NOT NULL,
                                `fdcId` TEXT,
                                `sourceDb` TEXT NOT NULL,
                                `mealId` TEXT,
                                `mealName` TEXT,
                                `createdAt` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL,
                                `deletedAt` INTEGER
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helperV5.writableDatabase.use { db ->
            db.execSQL(
                """
                INSERT INTO food_entry
                    (userId, timestamp, rawText, name, quantity, unit, grams, kcal, proteinG, fatG, carbG, sourceDb, createdAt, updatedAt)
                VALUES
                    ('user1', 1000000000, 'pasta', 'Pasta', 1.0, 'serving', 200.0, 350.0, 12.0, 3.0, 68.0, 'MANUAL', 1000000000, 1000000000)
                """.trimIndent()
            )
        }
        helperV5.close()

        // ── Step 2: re-open at v6, running MIGRATION_5_6 ─────────────────────────
        val helperV6 = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName56)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        if (oldVersion < 6) MIGRATION_5_6.migrate(db)
                    }
                })
                .build()
        )

        // ── Step 3: assert row survived and scaleFactor defaults to 1.0 ──────────
        helperV6.writableDatabase.use { db ->
            val cursor = db.query("SELECT id, name, scaleFactor FROM food_entry WHERE userId = 'user1'")
            assertEquals("Pre-migration row must survive", 1, cursor.count)
            cursor.moveToFirst()
            assertEquals("Pasta", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            val scaleFactorIdx = cursor.getColumnIndexOrThrow("scaleFactor")
            assertEquals(1.0, cursor.getDouble(scaleFactorIdx), 0.001)
            cursor.close()
        }
        helperV6.close()
    }
}
