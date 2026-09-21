package com.example.daypulse

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** No microphone device, cloud API key or live network is required for these UI smoke tests. */
@RunWith(AndroidJUnit4::class)
class DayPulseSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<DayPulseActivity>()

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
