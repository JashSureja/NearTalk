# NearTalk

A small Android group voice app. Phones running NearTalk discover one another through Google Nearby Connections, which selects Bluetooth/BLE/Wi-Fi transports without internet access. Hold the talk button, or enable **Voice activation** to transmit automatically only while speech is detected.

## Run

1. Open this directory in Android Studio, or run `build-debug.bat` from a Windows terminal.
2. Install `app/build/outputs/apk/debug/app-debug.apk` on two or more physical Android phones (Android 8+ with Google Play services).
3. Grant microphone and nearby-device permissions on each phone.
4. Open the app and wait for the devices to appear. After the persistent **NearTalk is active** notification appears, the session keeps running with the screen off.
5. Press and hold **HOLD**, or turn on **Voice activation** for hands-free talking.

Choose an **Audio output** in the app: Speaker, Earpiece, Bluetooth, or Media. Media uses Android's normal media-volume controls; the other routes use the communication audio path for echo handling.

Use the notification's **Stop** action when the ride/session is over. NearTalk holds CPU and high-performance Wi-Fi locks while active to keep screen-off audio responsive, so leaving the service running consumes more battery. Some aggressive custom-ROM battery managers may still require setting NearTalk to **Unrestricted** or excluding it from battery optimization.

The Android emulator is not suitable for verifying nearby discovery. Use physical devices. Headphones are recommended when several phones are close together.

The build requires JDK 17, Android API 35, Build Tools 35.0.1, and platform-tools. Java 26 can remain installed, but it is too new for this project's Kotlin/Gradle toolchain.

## Current MVP boundaries

- A microphone + connected-device foreground service keeps an already-started session active while the screen is locked. Android requires it to be started from the visible app; it does not auto-start after a reboot.
- Uses uncompressed 16 kHz mono PCM for low processing delay.
- Voice activation uses local adaptive energy detection with a short silence delay. It reduces radio traffic during silence, although the microphone remains active while listening.
- Connections are accepted automatically for a zero-setup experience. Before public release, add connection-token confirmation or a trusted-room mechanism to prevent a nearby impersonating app from joining.
- Nearby Connections requires Google Play services. Supporting devices without it would require a separate Wi-Fi Direct/Bluetooth transport implementation.
