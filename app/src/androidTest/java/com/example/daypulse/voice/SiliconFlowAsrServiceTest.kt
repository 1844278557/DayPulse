package com.example.daypulse.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** A deterministic ASR test: fake network only; never sends a key or microphone audio online. */
@RunWith(AndroidJUnit4::class)
class SiliconFlowAsrServiceTest {
    @Test
    fun sendsWavMultipartAndParsesTranscribedText() = runBlocking {
        val input = File.createTempFile("daypulse_test_", ".wav")
        try {
            input.writeBytes(ByteArray(128))
            val fake = FakeConnection(URL("https://example.invalid/v1/audio/transcriptions"))
            val service = SiliconFlowAsrService(fake.url) { fake }
            val answer = service.transcribe(input, "test-only-key")

            assertEquals("明天七点提醒我", answer)
            assertEquals("POST", fake.requestMethod)
            assertEquals("Bearer test-only-key", fake.getRequestProperty("Authorization"))
            assertTrue(fake.getRequestProperty("Content-Type")!!.startsWith("multipart/form-data; boundary="))
            val uploaded = fake.upload.toString("ISO-8859-1")
            assertTrue(uploaded.contains("name=\"model\""))
            assertTrue(uploaded.contains("FunAudioLLM/SenseVoiceSmall"))
            assertTrue(uploaded.contains("name=\"file\"; filename=\"speech.wav\""))
            assertTrue(uploaded.contains("Content-Type: audio/wav"))
            assertTrue(uploaded.contains("--DayPulse-"))
        } finally {
            input.delete()
        }
    }

    private class FakeConnection(url: URL) : HttpURLConnection(url) {
        val upload = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getOutputStream(): ByteArrayOutputStream = upload
        override fun getResponseCode(): Int = 200
        override fun getInputStream(): InputStream = ByteArrayInputStream(
            "{\"text\":\"明天七点提醒我\"}".toByteArray(Charsets.UTF_8)
        )
    }
}
