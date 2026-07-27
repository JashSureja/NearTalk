package com.neartalk.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

class AudioEngine(private val context: Context) {
    private val sampleRate = 16_000
    private val isCapturing = AtomicBoolean(false)
    private val outputs = ConcurrentHashMap<String, PipedOutputStream>()
    private val playback = ConcurrentHashMap<String, PlaybackSession>()
    private var captureThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var outputMode = AudioOutputMode.SPEAKER

    fun setOutputMode(mode: AudioOutputMode): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val applied = when (mode) {
            AudioOutputMode.MEDIA -> {
                clearCommunicationRoute(audioManager)
                audioManager.mode = AudioManager.MODE_NORMAL
                true
            }
            AudioOutputMode.SPEAKER -> {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                selectCommunicationDevice(audioManager, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            }
            AudioOutputMode.EARPIECE -> {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                selectCommunicationDevice(audioManager, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            }
            AudioOutputMode.BLUETOOTH -> {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                selectBluetoothDevice(audioManager)
            }
        }
        if (applied) outputMode = mode
        return applied
    }

    fun createOutgoingStream(endpointId: String): InputStream {
        outputs.remove(endpointId)?.closeQuietly()
        val input = PipedInputStream(64 * 1024)
        outputs[endpointId] = PipedOutputStream(input)
        return input
    }

    @SuppressLint("MissingPermission")
    fun startCapture(
        voiceActivated: Boolean = false,
        onVoiceStateChanged: (Boolean) -> Unit = {},
    ): Boolean {
        if (!isCapturing.compareAndSet(false, true)) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            isCapturing.set(false)
            return false
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4_096)

        val recorder = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
        }.getOrElse {
            isCapturing.set(false)
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            isCapturing.set(false)
            return false
        }

        audioRecord = recorder
        if (runCatching { recorder.startRecording() }.isFailure) {
            recorder.release()
            audioRecord = null
            isCapturing.set(false)
            return false
        }
        captureThread = Thread(
            { captureLoop(recorder, minBuffer, voiceActivated, onVoiceStateChanged) },
            "NearTalk-capture",
        ).apply { start() }
        return true
    }

    fun stopCapture() {
        if (!isCapturing.compareAndSet(true, false)) return
        val recorder = audioRecord
        val thread = captureThread
        runCatching { recorder?.stop() }
        thread?.interrupt()
        runCatching { thread?.join(200) }
        captureThread = null
        recorder?.release()
        audioRecord = null
        outputs.values.forEach { it.closeQuietly() }
        outputs.clear()
    }

    fun play(endpointId: String, input: InputStream) {
        stopPlayback(endpointId)
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4_096)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(
                        if (outputMode == AudioOutputMode.MEDIA) {
                            AudioAttributes.USAGE_MEDIA
                        } else {
                            AudioAttributes.USAGE_VOICE_COMMUNICATION
                        },
                    )
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        preferredOutputDevice()?.let(track::setPreferredDevice)

        val thread = Thread({
            val buffer = ByteArray(640)
            try {
                track.play()
                while (!Thread.currentThread().isInterrupted) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) track.write(buffer, 0, count, AudioTrack.WRITE_BLOCKING)
                }
            } finally {
                input.closeQuietly()
                runCatching { track.stop() }
                track.release()
                playback.remove(endpointId)
            }
        }, "NearTalk-play-$endpointId")

        playback[endpointId] = PlaybackSession(thread, input, track)
        thread.start()
    }

    fun removeEndpoint(endpointId: String) {
        outputs.remove(endpointId)?.closeQuietly()
        stopPlayback(endpointId)
    }

    fun release() {
        stopCapture()
        playback.keys.toList().forEach(::stopPlayback)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        clearCommunicationRoute(audioManager)
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    @Suppress("DEPRECATION")
    private fun selectCommunicationDevice(audioManager: AudioManager, type: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == type }
                ?: return false
            return audioManager.setCommunicationDevice(device)
        }
        stopLegacyBluetooth(audioManager)
        audioManager.isSpeakerphoneOn = type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        return true
    }

    @Suppress("DEPRECATION")
    private fun selectBluetoothDevice(audioManager: AudioManager): Boolean {
        val bluetoothTypes = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.availableCommunicationDevices.firstOrNull { it.type in bluetoothTypes }
                ?: return false
            return audioManager.setCommunicationDevice(device)
        }
        val connected = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in bluetoothTypes }
        if (!connected) return false
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
        audioManager.isSpeakerphoneOn = false
        return true
    }

    @Suppress("DEPRECATION")
    private fun clearCommunicationRoute(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            stopLegacyBluetooth(audioManager)
            audioManager.isSpeakerphoneOn = false
        }
    }

    @Suppress("DEPRECATION")
    private fun stopLegacyBluetooth(audioManager: AudioManager) {
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
    }

    private fun preferredOutputDevice(): AudioDeviceInfo? {
        if (outputMode == AudioOutputMode.MEDIA) return null
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val preferredTypes = when (outputMode) {
            AudioOutputMode.SPEAKER -> setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            AudioOutputMode.EARPIECE -> setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            AudioOutputMode.BLUETOOTH -> setOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
            )
            AudioOutputMode.MEDIA -> emptySet()
        }
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in preferredTypes }
    }

    private fun captureLoop(
        recorder: AudioRecord,
        bufferSize: Int,
        voiceActivated: Boolean,
        onVoiceStateChanged: (Boolean) -> Unit,
    ) {
        val buffer = ByteArray(bufferSize.coerceAtMost(1_280))
        var noiseFloor = 300.0
        val calibrationLevels = DoubleArray(8)
        var calibrationBuffers = 0
        var speaking = !voiceActivated
        var loudBuffers = 0
        var quietBuffers = 0
        val preRoll = ByteArray(buffer.size)
        var preRollCount = 0
        while (isCapturing.get() && !Thread.currentThread().isInterrupted) {
            val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (count > 0) {
                var justStartedSpeaking = false
                if (voiceActivated) {
                    val level = pcmRms(buffer, count)
                    if (calibrationBuffers < 8) {
                        calibrationLevels[calibrationBuffers] = level
                        calibrationBuffers++
                        if (calibrationBuffers == calibrationLevels.size) {
                            // A lower-quartile sample ignores brief sounds during startup
                            // without being fooled by the recorder's first near-zero frame.
                            noiseFloor = calibrationLevels.sortedArray()[2]
                        }
                        buffer.copyInto(preRoll, endIndex = count)
                        preRollCount = count
                        continue
                    }

                    val startThreshold = max(850.0, noiseFloor * 2.8)
                    val stopThreshold = max(600.0, noiseFloor * 1.8)

                    if (!speaking) {
                        if (level < startThreshold) {
                            noiseFloor = noiseFloor * 0.98 + level * 0.02
                        }
                        loudBuffers = if (level >= startThreshold) loudBuffers + 1 else 0
                        if (loudBuffers >= 2) {
                            speaking = true
                            justStartedSpeaking = true
                            quietBuffers = 0
                            onVoiceStateChanged(true)
                        }
                    } else {
                        quietBuffers = if (level < stopThreshold) quietBuffers + 1 else 0
                        if (quietBuffers >= 12) {
                            speaking = false
                            loudBuffers = 0
                            onVoiceStateChanged(false)
                        }
                    }
                }

                if (speaking) {
                    if (justStartedSpeaking && preRollCount > 0) broadcast(preRoll, preRollCount)
                    broadcast(buffer, count)
                } else {
                    buffer.copyInto(preRoll, endIndex = count)
                    preRollCount = count
                }
            }
        }
    }

    private fun broadcast(buffer: ByteArray, count: Int) {
        outputs.entries.forEach { (id, output) ->
            runCatching { output.write(buffer, 0, count) }
                .onFailure { outputs.remove(id)?.closeQuietly() }
        }
    }

    private fun pcmRms(buffer: ByteArray, count: Int): Double {
        var sum = 0.0
        var samples = 0
        var index = 0
        while (index + 1 < count) {
            val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toDouble() * sample
            samples++
            index += 2
        }
        return if (samples == 0) 0.0 else sqrt(sum / samples)
    }

    private fun stopPlayback(endpointId: String) {
        playback.remove(endpointId)?.let { session ->
            session.thread.interrupt()
            session.input.closeQuietly()
            runCatching { session.track.pause() }
            runCatching { session.track.flush() }
        }
    }

    private data class PlaybackSession(
        val thread: Thread,
        val input: InputStream,
        val track: AudioTrack,
    )
}

private fun AutoCloseable.closeQuietly() = runCatching { close() }.getOrNull()
