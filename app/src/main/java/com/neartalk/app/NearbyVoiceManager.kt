package com.neartalk.app

import android.content.Context
import android.os.Build
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class NearbyVoiceManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("neartalk_settings", Context.MODE_PRIVATE)
    private val client: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    private val audio = AudioEngine(appContext)
    private val peers = ConcurrentHashMap<String, NearbyPeer>()
    private val endpointNames = ConcurrentHashMap<String, String>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val outgoingStreams = ConcurrentHashMap.newKeySet<String>()
    private val started = AtomicBoolean(false)
    private val localName = makeDeviceName()
    private val serviceId = "com.neartalk.app.voice"
    private val strategy = Strategy.P2P_CLUSTER
    private val _state = MutableStateFlow(
        NearbyUiState(
            audioOutputMode = runCatching {
                AudioOutputMode.valueOf(
                    preferences.getString("audio_output_mode", AudioOutputMode.SPEAKER.name).orEmpty(),
                )
            }.getOrDefault(AudioOutputMode.SPEAKER),
        ),
    )
    val state: StateFlow<NearbyUiState> = _state.asStateFlow()

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.STREAM) {
                payload.asStream()?.asInputStream()?.let { audio.play(endpointId, it) }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            endpointNames[endpointId] = info.endpointName
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pending.remove(endpointId)
            if (result.status.isSuccess) {
                peers[endpointId] = NearbyPeer(endpointId, endpointNames[endpointId] ?: "Nearby device")
                publish(error = null)
                when {
                    _state.value.voiceActivationEnabled -> startVoiceActivationIfPossible()
                    _state.value.isTalking -> sendAudioTo(endpointId)
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            pending.remove(endpointId)
            peers.remove(endpointId)
            endpointNames.remove(endpointId)
            outgoingStreams.remove(endpointId)
            audio.removeEndpoint(endpointId)
            if (peers.isEmpty()) {
                audio.stopCapture()
                outgoingStreams.clear()
                _state.value = _state.value.copy(isTalking = false)
            }
            publish()
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            endpointNames[endpointId] = info.endpointName
            // Both phones advertise and discover. A stable ordering makes exactly one
            // side initiate each pair, avoiding crossed connection requests.
            if (localName < info.endpointName && !peers.containsKey(endpointId) && pending.add(endpointId)) {
                client.requestConnection(localName, endpointId, lifecycleCallback)
                    .addOnFailureListener {
                        pending.remove(endpointId)
                        publish(error = "Could not connect to ${info.endpointName}")
                    }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            pending.remove(endpointId)
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        audio.setOutputMode(_state.value.audioOutputMode)
        _state.value = _state.value.copy(isScanning = true, error = null)
        val advertising = AdvertisingOptions.Builder().setStrategy(strategy).build()
        val discovery = DiscoveryOptions.Builder().setStrategy(strategy).build()

        client.startAdvertising(localName, serviceId, lifecycleCallback, advertising)
            .addOnFailureListener { publish(error = "Nearby advertising could not start") }
        client.startDiscovery(serviceId, discoveryCallback, discovery)
            .addOnFailureListener { publish(error = "Nearby scan could not start") }
    }

    fun startTalking() {
        if (peers.isEmpty() || _state.value.voiceActivationEnabled) return
        if (audio.startCapture()) {
            peers.keys.forEach(::sendAudioTo)
            _state.value = _state.value.copy(isTalking = true, error = null)
        } else {
            publish(error = "Microphone permission is required")
        }
    }

    fun stopTalking() {
        if (_state.value.voiceActivationEnabled) return
        audio.stopCapture()
        outgoingStreams.clear()
        _state.value = _state.value.copy(isTalking = false)
    }

    fun setVoiceActivation(enabled: Boolean) {
        if (enabled == _state.value.voiceActivationEnabled) return
        if (enabled) {
            audio.stopCapture()
            outgoingStreams.clear()
            _state.value = _state.value.copy(voiceActivationEnabled = true, isTalking = false, error = null)
            startVoiceActivationIfPossible()
        } else {
            audio.stopCapture()
            outgoingStreams.clear()
            _state.value = _state.value.copy(voiceActivationEnabled = false, isTalking = false)
        }
    }

    fun setAudioOutputMode(mode: AudioOutputMode) {
        if (mode == _state.value.audioOutputMode) return
        if (audio.setOutputMode(mode)) {
            preferences.edit().putString("audio_output_mode", mode.name).apply()
            _state.value = _state.value.copy(audioOutputMode = mode, error = null)
        } else {
            publish(
                error = when (mode) {
                    AudioOutputMode.BLUETOOTH -> "Connect a Bluetooth headset first"
                    AudioOutputMode.EARPIECE -> "This device has no available earpiece"
                    else -> "That audio output is not available"
                },
            )
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        stopTalking()
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        peers.clear()
        pending.clear()
        endpointNames.clear()
        outgoingStreams.clear()
        audio.release()
        _state.value = NearbyUiState(audioOutputMode = savedAudioOutputMode())
    }

    private fun sendAudioTo(endpointId: String) {
        if (!outgoingStreams.add(endpointId)) return
        val payload = Payload.fromStream(audio.createOutgoingStream(endpointId))
        client.sendPayload(endpointId, payload).addOnFailureListener {
            outgoingStreams.remove(endpointId)
            audio.removeEndpoint(endpointId)
        }
    }

    private fun startVoiceActivationIfPossible() {
        if (!_state.value.voiceActivationEnabled || peers.isEmpty()) return
        val started = audio.startCapture(voiceActivated = true) { active ->
            if (_state.value.voiceActivationEnabled) {
                _state.value = _state.value.copy(isTalking = active)
            }
        }
        if (started) {
            peers.keys.forEach(::sendAudioTo)
        } else {
            _state.value = _state.value.copy(
                voiceActivationEnabled = false,
                error = "Microphone permission is required",
            )
        }
    }

    private fun publish(error: String? = _state.value.error) {
        _state.value = _state.value.copy(
            peers = peers.values.sortedBy { it.name.lowercase(Locale.getDefault()) },
            error = error,
        )
    }

    private fun makeDeviceName(): String {
        val model = Build.MODEL.take(18).ifBlank { "Android" }
        val suffix = UUID.randomUUID().toString().take(6).uppercase(Locale.US)
        return "$model - $suffix"
    }

    private fun savedAudioOutputMode(): AudioOutputMode = runCatching {
        AudioOutputMode.valueOf(
            preferences.getString("audio_output_mode", AudioOutputMode.SPEAKER.name).orEmpty(),
        )
    }.getOrDefault(AudioOutputMode.SPEAKER)
}
