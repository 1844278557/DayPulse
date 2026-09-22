package com.example.daypulse.voice

/**
 * Voice backend selection for DayPulse.
 *
 * SYSTEM_SPEECH is kept only for compatibility tests.
 * DIRECT_AUDIO is the new path used on HarmonyOS devices.
 */
enum class VoiceInputMode {
    SYSTEM_SPEECH,
    DIRECT_AUDIO
}
