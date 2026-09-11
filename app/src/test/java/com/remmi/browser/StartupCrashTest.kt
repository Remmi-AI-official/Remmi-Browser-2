package com.remmi.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remmi.browser.util.CrashHandlerHelper
import com.remmi.browser.util.StartupPhase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class StartupCrashTest {

  @Test
  fun testAppStartupInitialization() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertNotNull("Application context must not be null", context)
    
    // Verify startup phase tracking and crash handling initialization
    CrashHandlerHelper.onProcessStart(context)
    CrashHandlerHelper.updateStartupPhase(context, StartupPhase.APPLICATION_CREATED)
    assertTrue("Startup phase must progress", CrashHandlerHelper.currentPhase.ordinal >= StartupPhase.APPLICATION_CREATED.ordinal)
  }
}
