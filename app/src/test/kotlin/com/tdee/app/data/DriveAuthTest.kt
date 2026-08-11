package com.tdee.app.data

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveAuthTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun prefs() = context.getSharedPreferences("com.tdee.app.settings", Context.MODE_PRIVATE)

    @Test
    fun `needsReconnect persists across instances`() {
        DriveAuth(context, prefs()).needsReconnect = true
        assertTrue(DriveAuth(context, prefs()).needsReconnect)
    }

    @Test
    fun `needsReconnect defaults to false`() {
        assertFalse(DriveAuth(context, prefs()).needsReconnect)
    }
}
