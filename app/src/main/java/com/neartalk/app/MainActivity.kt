package com.neartalk.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Ink = Color(0xFF07111F)
private val Panel = Color(0xFF101D2F)
private val Mint = Color(0xFF58E0B2)
private val TextPrimary = Color(0xFFF3F7FB)
private val TextMuted = Color(0xFF91A2B6)
private val Danger = Color(0xFFFF807A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NearTalkApp() }
    }
}

@Composable
private fun NearTalkApp() {
    val context = LocalContext.current
    val manager = remember { NearTalkRuntime.manager(context) }
    val state by manager.state.collectAsStateWithLifecycle()
    var permissionsGranted by remember { mutableStateOf(hasRequiredPermissions(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionsGranted = hasRequiredPermissions(context) }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NearTalkService::class.java),
            )
            requestNotificationPermissionOnce(context, permissionLauncher::launch)
        }
    }

    MaterialTheme {
        Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
            if (permissionsGranted) {
                NearTalkPager(
                    state = state,
                    onTalkStart = manager::startTalking,
                    onTalkEnd = manager::stopTalking,
                    onVoiceActivationChange = manager::setVoiceActivation,
                    onAudioOutputChange = manager::setAudioOutputMode,
                )
            } else {
                PermissionScreen { permissionLauncher.launch(permissionsToRequest()) }
            }
        }
    }
}

@Composable
private fun NearTalkPager(
    state: NearbyUiState,
    onTalkStart: () -> Unit,
    onTalkEnd: () -> Unit,
    onVoiceActivationChange: (Boolean) -> Unit,
    onAudioOutputChange: (AudioOutputMode) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 2 })
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        if (page == 0) {
            DevicesScreen(state)
        } else {
            HomeScreen(
                state = state,
                onTalkStart = onTalkStart,
                onTalkEnd = onTalkEnd,
                onVoiceActivationChange = onVoiceActivationChange,
                onAudioOutputChange = onAudioOutputChange,
            )
        }
    }
}

@Composable
private fun HomeScreen(
    state: NearbyUiState,
    onTalkStart: () -> Unit,
    onTalkEnd: () -> Unit,
    onVoiceActivationChange: (Boolean) -> Unit,
    onAudioOutputChange: (AudioOutputMode) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        val horizontalPadding = when {
            maxWidth < 360.dp -> 16.dp
            maxWidth < 600.dp -> 24.dp
            else -> 24.dp
        }
        val compactHeight = maxHeight < 700.dp

        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .padding(horizontal = horizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Mint),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MicIcon, null, tint = Ink, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("NearTalk", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Local voice, no internet", color = TextMuted, fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(if (compactHeight) 20.dp else 34.dp))
                StatusPill(state)
                Spacer(Modifier.height(if (compactHeight) 16.dp else 24.dp))

                Text(
                    if (state.peers.isEmpty()) "Looking for people nearby" else "${state.peers.size} nearby ${if (state.peers.size == 1) "person" else "people"}",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    if (state.peers.isEmpty()) "Swipe right to view nearby devices" else "Swipe right to view connected devices",
                    color = TextMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )

                Spacer(Modifier.height(if (compactHeight) 20.dp else 28.dp))
                AudioOutputRow(state.audioOutputMode, onAudioOutputChange)
                Spacer(Modifier.height(10.dp))
                VoiceActivationRow(state.voiceActivationEnabled, onVoiceActivationChange)

                AnimatedVisibility(state.error != null) {
                    Text(state.error.orEmpty(), color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                }
                Spacer(Modifier.height(16.dp))
            }

            TalkButton(
                enabled = state.peers.isNotEmpty() && !state.voiceActivationEnabled,
                talking = state.isTalking,
                voiceActivationEnabled = state.voiceActivationEnabled,
                compact = compactHeight,
                onTalkStart = onTalkStart,
                onTalkEnd = onTalkEnd,
            )
            Text(
                when {
                    state.peers.isEmpty() && state.voiceActivationEnabled -> "Ready when a device connects"
                    state.peers.isEmpty() -> "Connect a device to talk"
                    state.voiceActivationEnabled && state.isTalking -> "Voice detected - transmitting"
                    state.voiceActivationEnabled -> "Listening - speak naturally"
                    else -> "Press and hold to talk"
                },
                color = TextMuted,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun DevicesScreen(state: NearbyUiState) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(top = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Nearby devices", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (state.peers.isEmpty()) "Scanning for NearTalk phones" else "${state.peers.size} connected",
                    color = TextMuted,
                    fontSize = 13.sp,
                )
            }
            StatusPill(state)
        }

        if (state.peers.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(TextMuted))
                    Spacer(Modifier.height(14.dp))
                    Text("No devices yet", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Keep NearTalk open on the other phones", color = TextMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().weight(1f).padding(top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.peers, key = { it.endpointId }) { peer -> PeerRow(peer) }
            }
        }
        Text("Swipe left to return home", color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
    }
}

@Composable
private fun AudioOutputRow(
    selected: AudioOutputMode,
    onSelected: (AudioOutputMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Panel)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("Audio output", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(
            if (selected == AudioOutputMode.MEDIA) "Uses media volume and Android's system route" else "Uses call audio processing",
            color = TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )
        BoxWithConstraints {
            val rows = if (maxWidth < 340.dp) {
                AudioOutputMode.entries.chunked(2)
            } else {
                listOf(AudioOutputMode.entries.toList())
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { modes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        modes.forEach { mode -> AudioOutputChip(mode, mode == selected, onSelected) }
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.AudioOutputChip(
    mode: AudioOutputMode,
    selected: Boolean,
    onSelected: (AudioOutputMode) -> Unit,
) {
    Text(
        text = mode.label,
        color = if (selected) Ink else TextMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .weight(1f)
            .clip(CircleShape)
            .background(if (selected) Mint else Color(0xFF1B2A3C))
            .clickable { onSelected(mode) }
            .padding(horizontal = 8.dp, vertical = 9.dp),
    )
}

@Composable
private fun VoiceActivationRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Panel)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Voice activation", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("Transmit only when you speak", color = TextMuted, fontSize = 12.sp)
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = Mint,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = Color(0xFF283749),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun StatusPill(state: NearbyUiState) {
    val connected = state.peers.isNotEmpty()
    Row(
        modifier = Modifier.clip(CircleShape).background(if (connected) Mint.copy(alpha = .12f) else Panel).padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) Mint else TextMuted))
        Spacer(Modifier.width(8.dp))
        Text(if (connected) "Connected" else "Scanning nearby", color = if (connected) Mint else TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PeerRow(peer: NearbyPeer) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(Mint.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            Text(peer.name.take(1).uppercase(), color = Mint, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("Ready", color = TextMuted, fontSize = 12.sp)
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(Mint))
    }
}

@Composable
private fun TalkButton(
    enabled: Boolean,
    talking: Boolean,
    voiceActivationEnabled: Boolean,
    compact: Boolean,
    onTalkStart: () -> Unit,
    onTalkEnd: () -> Unit,
) {
    val color = when {
        talking -> Color(0xFFFFC56D)
        voiceActivationEnabled -> Mint
        enabled -> Mint
        else -> Color(0xFF283749)
    }
    val foreground = if (enabled || voiceActivationEnabled || talking) Ink else TextMuted
    Box(
        modifier = Modifier
            .size(if (compact) 132.dp else 154.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = if (talking) .18f else .10f))
            .padding(10.dp)
            .clip(CircleShape)
            .background(color)
            .pointerInput(enabled, voiceActivationEnabled) {
                if (enabled) detectTapGestures(onPress = {
                    onTalkStart()
                    tryAwaitRelease()
                    onTalkEnd()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(MicIcon, null, tint = foreground, modifier = Modifier.size(if (compact) 32.dp else 38.dp))
            Text(
                when {
                    talking -> "TALKING"
                    voiceActivationEnabled -> "LISTENING"
                    else -> "HOLD"
                },
                color = foreground,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
      Column(
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Box(Modifier.size(82.dp).clip(RoundedCornerShape(24.dp)).background(Mint), contentAlignment = Alignment.Center) {
            Icon(MicIcon, null, tint = Ink, modifier = Modifier.size(42.dp))
        }
        Text("Talk to nearby devices", color = TextPrimary, fontSize = 25.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 28.dp))
        Text("NearTalk needs microphone and nearby-device access to find phones and carry your voice. Audio never goes to the internet.", color = TextMuted, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, bottom = 28.dp))
        Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text("Allow access", fontWeight = FontWeight.Bold)
        }
      }
    }
}

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    when {
        Build.VERSION.SDK_INT >= 33 -> {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        Build.VERSION.SDK_INT >= 31 -> {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        Build.VERSION.SDK_INT >= 29 -> add(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}.toTypedArray()

private fun permissionsToRequest(): Array<String> = buildList {
    addAll(requiredPermissions())
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun requestNotificationPermissionOnce(
    context: Context,
    launch: (Array<String>) -> Unit,
) {
    if (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    ) return

    val preferences = context.getSharedPreferences("neartalk_settings", Context.MODE_PRIVATE)
    if (!preferences.getBoolean("notification_permission_requested", false)) {
        preferences.edit().putBoolean("notification_permission_requested", true).apply()
        launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }
}

private fun hasRequiredPermissions(context: android.content.Context): Boolean =
    requiredPermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

private val MicIcon: ImageVector = ImageVector.Builder(
    name = "Mic",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = androidx.compose.ui.graphics.SolidColor(Color.Black)) {
        moveTo(12f, 14f)
        curveTo(13.66f, 14f, 14.99f, 12.66f, 14.99f, 11f)
        lineTo(15f, 5f)
        curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
        curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
        lineTo(9f, 11f)
        curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
        close()
        moveTo(17.3f, 11f)
        curveTo(17.3f, 14f, 14.76f, 16.1f, 12f, 16.1f)
        curveTo(9.24f, 16.1f, 6.7f, 14f, 6.7f, 11f)
        lineTo(5f, 11f)
        curveTo(5f, 14.41f, 7.72f, 17.23f, 11f, 17.72f)
        lineTo(11f, 21f)
        lineTo(13f, 21f)
        lineTo(13f, 17.72f)
        curveTo(16.28f, 17.23f, 19f, 14.41f, 19f, 11f)
        close()
    }
}.build()
