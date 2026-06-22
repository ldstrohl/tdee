package com.tdee.app

import android.app.Application
import com.tdee.app.di.AppContainer

class TdeeApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
