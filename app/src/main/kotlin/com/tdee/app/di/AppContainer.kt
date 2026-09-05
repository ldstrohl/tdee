package com.tdee.app.di

import android.content.Context
import androidx.room.Room
import com.tdee.app.BuildConfig
import com.tdee.app.addfood.FoodParser
import com.tdee.app.addfood.LlmFoodParser
import com.tdee.app.data.AppDatabase
import com.tdee.app.data.BackupManager
import com.tdee.app.data.DriveAuth
import com.tdee.app.data.DriveClient
import com.tdee.app.data.LlmSettingsStore
import com.tdee.app.data.MIGRATION_2_3
import com.tdee.app.data.MIGRATION_3_4
import com.tdee.app.data.MIGRATION_4_5
import com.tdee.app.data.MIGRATION_5_6
import com.tdee.app.data.OpenFoodFactsClient
import com.tdee.app.data.ProductLookupService
import com.tdee.app.data.SavedMealDao
import com.tdee.app.data.FoodEntryDao
import com.tdee.app.data.HealthConnectSyncManager
import com.tdee.app.data.RealHealthConnectSource
import com.tdee.app.data.SharedPreferencesCurrentUser
import com.tdee.app.data.TdeeRepository
import com.tdee.app.data.TargetPeriodDao
import com.tdee.app.data.UserProfileDao
import com.tdee.app.data.WeightEntryDao
import com.tdee.app.data.WeightTrendCacheDao
import com.tdee.app.ui.theme.ThemeStore
import okhttp3.OkHttpClient
import java.io.File
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * Manual DI container. Holds lazily-initialized app-scoped singletons.
 * Obtain via [com.tdee.app.TdeeApplication.container].
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "tdee.db")
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()
    }

    val profileDao: UserProfileDao by lazy { database.userProfileDao() }
    val weightDao: WeightEntryDao by lazy { database.weightEntryDao() }
    val foodDao: FoodEntryDao by lazy { database.foodEntryDao() }
    val targetPeriodDao: TargetPeriodDao by lazy { database.targetPeriodDao() }
    val trendCacheDao: WeightTrendCacheDao by lazy { database.weightTrendCacheDao() }
    val savedMealDao: SavedMealDao by lazy { database.savedMealDao() }

    val currentUser: SharedPreferencesCurrentUser by lazy {
        SharedPreferencesCurrentUser(appContext)
    }

    val themeStore: ThemeStore by lazy { ThemeStore(appContext) }

    val llmSettingsStore: LlmSettingsStore by lazy { LlmSettingsStore(appContext) }

    val driveAuth: DriveAuth by lazy {
        DriveAuth(appContext, appContext.getSharedPreferences("com.tdee.app.settings", Context.MODE_PRIVATE))
    }

    /**
     * Shared HTTP client. `callTimeout` is the real bound: it covers the whole call, including
     * redirects and retries, which the per-phase timeouts do not.
     *
     * Read and write are raised to match it because OkHttp defaults them to 10 seconds, and a
     * vision request routinely takes longer than that. A nutrition-label photo went out as a
     * 185 kB body and the model answered well after the default cut the socket, so every label
     * parse failed as a timeout. Raising them does not widen the worst case; `callTimeout` still
     * caps every call at 60 seconds.
     */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Natural-language [FoodParser]: client-direct, bring-your-own-key ([LlmFoodParser]). Reads the
     * selected provider/model/key from [llmSettingsStore] at parse time; with no key it returns a
     * NO_KEY failure the UI surfaces (manual entry still works).
     */
    val foodParser: FoodParser by lazy {
        LlmFoodParser(llmSettingsStore, httpClient)
    }

    val driveClient: DriveClient by lazy {
        DriveClient(client = httpClient, token = { driveAuth.token() }, onUnauthorized = { driveAuth.invalidate() })
    }

    /** Barcode → product lookup against Open Food Facts. */
    val openFoodFactsClient: OpenFoodFactsClient by lazy {
        OpenFoodFactsClient(httpClient)
    }

    /** [openFoodFactsClient] plus LLM gap-filling for macros OFF didn't report. */
    val productLookupService: ProductLookupService by lazy {
        ProductLookupService(openFoodFactsClient, foodParser)
    }

    val repository: TdeeRepository by lazy {
        TdeeRepository(
            profileDao = profileDao,
            weightDao = weightDao,
            foodDao = foodDao,
            targetDao = targetPeriodDao,
            trendCacheDao = trendCacheDao,
            savedMealDao = savedMealDao,
            currentUser = currentUser,
        )
    }

    val healthConnectSource: RealHealthConnectSource by lazy {
        RealHealthConnectSource(appContext)
    }

    val healthConnectSyncManager: HealthConnectSyncManager by lazy {
        HealthConnectSyncManager(
            source = healthConnectSource,
            weightDao = weightDao,
            currentUser = currentUser,
            clock = Clock.systemUTC(),
        )
    }

    val backupManager: BackupManager by lazy {
        BackupManager(
            db = database,
            profileDao = profileDao,
            weightDao = weightDao,
            foodDao = foodDao,
            targetDao = targetPeriodDao,
            savedMealDao = savedMealDao,
            trendCacheDao = trendCacheDao,
            currentUser = currentUser,
            themeStore = themeStore,
            snapshotDir = File(appContext.filesDir, "backup"),
            appVersionCode = BuildConfig.VERSION_CODE,
        )
    }
}
