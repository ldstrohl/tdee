package com.tdee.app.di

import android.content.Context
import androidx.room.Room
import com.tdee.app.addfood.FoodParser
import com.tdee.app.addfood.LlmFoodParser
import com.tdee.app.data.AppDatabase
import com.tdee.app.data.LlmSettingsStore
import com.tdee.app.data.MIGRATION_2_3
import com.tdee.app.data.MIGRATION_3_4
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
import java.time.Clock

/**
 * Manual DI container. Holds lazily-initialized app-scoped singletons.
 * Obtain via [com.tdee.app.TdeeApplication.container].
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "tdee.db")
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
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

    /**
     * Natural-language [FoodParser]: client-direct, bring-your-own-key ([LlmFoodParser]). Reads the
     * selected provider/model/key from [llmSettingsStore] at parse time; with no key it returns a
     * NO_KEY failure the UI surfaces (manual entry still works).
     */
    val foodParser: FoodParser by lazy {
        LlmFoodParser(llmSettingsStore, OkHttpClient())
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
}
