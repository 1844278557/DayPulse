package com.example.daypulse

import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** No live microphone, cloud key, or network is required for the UI smoke tests. */
@RunWith(AndroidJUnit4::class)
class DayPulseSmokeTest {
    // The app asks for notifications in its first LaunchedEffect. Grant this *before*
    // ActivityScenarioRule launches DayPulseActivity, otherwise the Android permission
    // dialog can stop the activity before Compose exposes its semantics tree.
    private val notificationPermission =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val compose = createAndroidComposeRule<DayPulseActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(notificationPermission).around(compose)

    @Test
    fun homeShowsVoiceEntryWithoutStartingMicrophone() {
        compose.onNodeWithContentDescription("开始 AI 语音").assertExists()
        compose.onNodeWithText("首页").assertExists()
    }

    @Test
    fun canNavigateToAlarmPageAndBackWithoutCloudKey() {
        compose.onNodeWithContentDescription("闹钟").performClick()
        compose.onNodeWithContentDescription("开始 AI 语音").assertExists()
        compose.onNodeWithContentDescription("首页").performClick()
        compose.onNodeWithContentDescription("开始 AI 语音").assertExists()
    }
}
