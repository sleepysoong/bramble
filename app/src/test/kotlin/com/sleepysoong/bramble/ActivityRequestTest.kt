package com.sleepysoong.bramble

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ActivityRequestTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun prepare() {
        compose.activity.getSharedPreferences("connection", Context.MODE_PRIVATE).edit().clear().commit()
        compose.runOnIdle {
            RelayState.enabled = false
            RelayState.running = false
            RelayState.busy = false
            RelayState.pending = PhoneRequest("original", "file", "첫 번째 요청", System.currentTimeMillis() + 120_000)
        }
    }

    @After fun cleanup() {
        compose.runOnIdle { RelayState.pending = null; RelayState.busy = false; RelayState.enabled = false }
        compose.activity.getSharedPreferences("connection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun fileSelectedForExpiredRequestDoesNotAnswerNextRequest() {
        compose.onNodeWithText("파일 선택").performClick()
        val picker = shadowOf(compose.activity).nextStartedActivityForResult
        compose.runOnIdle {
            RelayState.pending = PhoneRequest("next", "file", "새 요청", System.currentTimeMillis() + 120_000)
            compose.activity.activityResultRegistry.dispatchResult(picker.requestCode, Uri.parse("content://phone/private-file"))
        }
        assertNull("old file must never start a response for the new request", shadowOf(compose.activity).nextStartedService)
        assertEquals("next", RelayState.pending?.id)
    }

    @Test fun fileSelectedForCurrentRequestCarriesItsOriginalId() {
        compose.onNodeWithText("파일 선택").performClick()
        val picker = shadowOf(compose.activity).nextStartedActivityForResult
        val uri = Uri.parse("content://phone/chosen-file")
        compose.runOnIdle { compose.activity.activityResultRegistry.dispatchResult(picker.requestCode, uri) }
        val response = shadowOf(compose.activity).nextStartedService
        assertEquals(RelayService.ACTION_FILE, response.action)
        assertEquals("original", response.getStringExtra("request_id"))
        assertEquals(uri, response.data)
    }

    @Test fun pickerRequestIdSurvivesActivityRecreation() {
        compose.onNodeWithText("파일 선택").performClick()
        val picker = shadowOf(compose.activity).nextStartedActivityForResult
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        val uri = Uri.parse("content://phone/after-rotation")
        compose.runOnIdle { compose.activity.activityResultRegistry.dispatchResult(picker.requestCode, uri) }
        val response = shadowOf(compose.activity).nextStartedService
        assertEquals(RelayService.ACTION_FILE, response.action)
        assertEquals("original", response.getStringExtra("request_id"))
        assertEquals(uri, response.data)
    }
}
