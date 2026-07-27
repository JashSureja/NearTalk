package com.neartalk.app

data class NearbyPeer(
    val endpointId: String,
    val name: String,
)

enum class AudioOutputMode(val label: String) {
    SPEAKER("Speaker"),
    EARPIECE("Earpiece"),
    BLUETOOTH("Bluetooth"),
    MEDIA("Media"),
}

data class NearbyUiState(
    val isScanning: Boolean = false,
    val isTalking: Boolean = false,
    val voiceActivationEnabled: Boolean = false,
    val audioOutputMode: AudioOutputMode = AudioOutputMode.SPEAKER,
    val peers: List<NearbyPeer> = emptyList(),
    val error: String? = null,
)
