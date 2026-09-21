package com.example.daypulse.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/** Records microphone PCM directly, without SpeechRecognizer, into a temporary WAV file. */
class AudioRecorderController(private val context: Context) {
    private val recording = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var outputFile: File? = null
    @Volatile private var capturedBytes = 0L
    @Volatile private var captureFailure: Throwable? = null

    fun start() {
        check(!recording.get()) { "已经开始录音" }
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "需要麦克风权限"
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "麦克风不支持 16kHz PCM 录音" }
        val audio = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * 2, 4096)
        )
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            error("麦克风初始化失败，请检查是否被其他应用占用")
        }
        val file = File.createTempFile("daypulse_voice_", ".wav", context.cacheDir)
        try {
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风没有开始录音" }
            recorder = audio
            outputFile = file
            capturedBytes = 0
            captureFailure = null
            recording.set(true)
            worker = Thread({
                try {
                    FileOutputStream(file).use { out ->
                        out.write(ByteArray(WAVE_HEADER_BYTES))
                        val buffer = ByteArray(4096)
                        while (recording.get()) {
                            val read = audio.read(buffer, 0, buffer.size)
                            if (read > 0) {
                                out.write(buffer, 0, read)
                                capturedBytes += read
                            } else if (read < 0 && recording.get()) {
                                error("录音读取失败：$read")
                            }
                        }
                    }
                } catch (error: Throwable) {
                    captureFailure = error
                    recording.set(false)
                }
            }, "DayPulse-WavRecorder").apply { start() }
        } catch (error: Throwable) {
            recording.set(false)
            runCatching { audio.stop() }
            audio.release()
            recorder = null
            outputFile = null
            file.delete()
            throw error
        }
    }

    /** Stops recording, writes the WAV header, and returns a file owned by the caller. */
    fun stop(): File {
        val file = outputFile ?: error("录音尚未开始")
        recording.set(false)
        releaseAudio()
        val thread = worker
        thread?.join(3_000)
        if (thread?.isAlive == true) {
            file.delete()
            outputFile = null
            error("录音结束超时，请重试")
        }
        worker = null
        outputFile = null
        captureFailure?.let {
            file.delete()
            throw IllegalStateException("录音失败：${it.message ?: "无法读取音频"}", it)
        }
        val bytes = capturedBytes
        if (bytes < SAMPLE_RATE * BYTES_PER_SAMPLE / 3) {
            file.delete()
            error("录音过短，请按住说完一句话后再结束")
        }
        writeWavHeader(file, bytes)
        return file
    }

    fun cancel() {
        recording.set(false)
        releaseAudio()
        runCatching { worker?.join(3_000) }
        worker = null
        outputFile?.delete()
        outputFile = null
    }

    private fun releaseAudio() {
        val audio = recorder ?: return
        recorder = null
        runCatching { audio.stop() }
        runCatching { audio.release() }
    }

    private fun writeWavHeader(file: File, pcmBytes: Long) {
        check(pcmBytes <= Int.MAX_VALUE - WAVE_HEADER_BYTES) { "录音文件过大" }
        RandomAccessFile(file, "rw").use { wav ->
            wav.seek(0)
            wav.writeBytes("RIFF")
            wav.writeInt(Integer.reverseBytes((pcmBytes + 36).toInt()))
            wav.writeBytes("WAVEfmt ")
            wav.writeInt(Integer.reverseBytes(16))
            wav.writeShort(1)
            // For mono PCM both values are 1, so endian conversion is explicit for clarity.
            wav.seek(20)
            writeShortLittleEndian(wav, 1)
            writeShortLittleEndian(wav, 1)
            wav.writeInt(Integer.reverseBytes(SAMPLE_RATE))
            wav.writeInt(Integer.reverseBytes(SAMPLE_RATE * BYTES_PER_SAMPLE))
            writeShortLittleEndian(wav, BYTES_PER_SAMPLE)
            writeShortLittleEndian(wav, 16)
            wav.writeBytes("data")
            wav.writeInt(Integer.reverseBytes(pcmBytes.toInt()))
        }
    }

    private fun writeShortLittleEndian(file: RandomAccessFile, value: Int) {
        file.write(value and 0xff)
        file.write((value shr 8) and 0xff)
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val WAVE_HEADER_BYTES = 44
    }
}
