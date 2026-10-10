# Lucy Voice Agent — Android Native Kotlin

A native Android voice agent that listens for voice commands and executes them offline, with speaker verification and device-owner actions support.

## Features

- **Offline Speech-to-Text**: Uses Vosk for high-accuracy English speech recognition (no internet required).
- **Wake-word Detection**: Silero VAD for efficient voice activity detection.
- **Speaker Verification**: TensorFlow Lite speaker-embedding model for voice authentication.
- **Safe Command Execution**: Whitelist-based intent mapping; no arbitrary command execution.
- **Device Admin Support**: Optional device-owner integration for system actions (shutdown, reboot, lock).
- **Foreground Service**: Runs reliably in the background with proper notifications.

## Requirements

- **Android SDK**: Target API 36, Min API 28
- **Android Studio**: Gradle 8.0+
- **Device**: Physical Android device with microphone (emulator microphone simulation unreliable)
- **Models**: Download offline models before building

## Quick Setup

### 1. Clone or Create Project

```bash
git clone <your-repo-url>
cd Lucy.voiceagent
```

### 2. Download Required Models

#### Vosk English Model (~100MB compressed, ~600MB extracted)
1. Visit: https://github.com/alphacep/vosk-api/releases
2. Download `model-en-us.zip`
3. Place in: `app/src/main/assets/vosk/model-en-us.zip`

```bash
# Example using PowerShell on Windows
$url = "https://github.com/alphacep/vosk-api/releases/download/0.3.32/model-en-us.zip"
$output = "app\src\main\assets\vosk\model-en-us.zip"
Invoke-WebRequest -Uri $url -OutFile $output
```

#### Silero VAD Model
1. Visit: https://github.com/snakers4/silero-vad
2. Download the TFLite model from their releases
3. Place in: `app/src/main/assets/silero/silero_vad.tflite`

#### Speaker Verification Model (TFLite)
Option A: Use a pre-trained model from TensorFlow Hub
```bash
# Download speaker recognition model from TensorFlow Hub
wget https://tfhub.dev/google/lite-model/spokendigits/sv-embedding/1?lite-format=tflite -O app/src/main/assets/speaker/speaker_verification.tflite
```

Option B: Use a wav2vec2 model converted to TFLite (advanced):
- Follow: https://huggingface.co/docs/transformers/tasks/audio_classification

### 3. Build the Project

```bash
# Build debug APK
./gradlew assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

### 4. Install on Device

```bash
# Connect device via USB, enable Developer Mode & USB Debugging
adb devices

# Install APK
adb install app/build/outputs/apk/debug/app-debug.apk

# Grant permissions
adb shell pm grant com.lucy.voiceagent android.permission.RECORD_AUDIO
adb shell pm grant com.lucy.voiceagent android.permission.FOREGROUND_SERVICE
```

### 5. (Optional) Set as Device Owner

To enable shutdown/reboot commands, set the app as device owner:

```bash
# Clear any existing device owner (requires factory reset or special provisioning)
adb shell dpm clear-device-owner

# Set Lucy Voice Agent as device owner
adb shell dpm set-device-owner com.lucy.voiceagent/.DeviceAdminReceiver

# Verify
adb shell dpm get-device-owner
```

**⚠️ Important**: Device owner can only be set on a clean device. If the command fails, you may need to factory-reset via the device's Recovery menu.

### 6. Grant Audio Recording Permission (Runtime)

On Android 12+, RECORD_AUDIO must be granted at runtime:

```bash
adb shell pm grant com.lucy.voiceagent android.permission.RECORD_AUDIO
```

Or grant via:
- Settings → Apps → Lucy Voice Agent → Permissions → Microphone

### 7. Launch App

```bash
adb shell am start -n com.lucy.voiceagent/.MainActivity
```

## Usage

### Starting Voice Listening

1. Open **Lucy Voice Agent** app
2. Tap **Start Voice Agent**
3. App will show a persistent notification: "Lucy Voice Agent — Listening..."
4. Speak commands!

### Supported Commands

#### App Launching
- "open spotify"
- "open chrome"
- "open gmail"
- "open maps"
- "open calculator"
- "open settings"

#### Media Control
- "play"
- "pause"
- "next"
- "previous"

#### System Actions (Device Owner Required)
- "shutdown" → requires speaker verification + confirmation
- "reboot" → requires speaker verification + confirmation
- "lock" → requires speaker verification + confirmation

### Speaker Enrollment

1. Tap **Enroll Speaker**
2. Speak 3-5 utterances (e.g., "voice agent") in a quiet environment
3. App stores your voice embeddings securely
4. Subsequently, sensitive actions (shutdown, etc.) require voice verification

## Project Structure

```
Lucy.voiceagent/
├── app/
│   ├── src/main/
│   │   ├── kotlin/com/lucy/voiceagent/
│   │   │   ├── MainActivity.kt              # Main UI
│   │   │   ├── VoiceCommandService.kt       # ForegroundService (core listening)
│   │   │   ├── SpeakerVerifier.kt           # TFLite speaker verification
│   │   │   ├── VoiceCommandExecutor.kt      # Intent mapper (safe command execution)
│   │   │   ├── DeviceAdminReceiver.kt       # Device-owner actions
│   │   │   ├── AccessibilityHelper.kt       # Optional UI automation
│   │   │   └── PermissionHelper.kt          # Permission handling
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml
│   │   │   ├── values/strings.xml
│   │   │   ├── values/styles.xml
│   │   │   └── xml/
│   │   │       ├── accessibility_service_config.xml
│   │   │       └── device_admin_receiver.xml
│   │   └── assets/
│   │       ├── vosk/model-en-us.zip         (Download & place)
│   │       ├── silero/silero_vad.tflite     (Download & place)
│   │       └── speaker/speaker_verification.tflite (Download & place)
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── settings.gradle.kts
├── build.gradle.kts
├── README.md
└── .gitignore
```

## Troubleshooting

### Model Not Found Error
**Symptom**: `java.io.FileNotFoundException: vosk_model/model-en-us`

**Solution**: 
1. Download `model-en-us.zip` from https://github.com/alphacep/vosk-api/releases
2. Place in `app/src/main/assets/vosk/model-en-us.zip`
3. Ensure filename matches exactly (case-sensitive on Linux)
4. Rebuild and reinstall APK

### Microphone Permission Denied
**Symptom**: App crashes with "permission denied"

**Solution**:
```bash
adb shell pm grant com.lucy.voiceagent android.permission.RECORD_AUDIO
```

Or manually:
- Settings → Apps → Lucy Voice Agent → Permissions → Microphone → Allow

### Device Owner Set-Owner Fails
**Symptom**: `adb shell dpm set-device-owner` returns error

**Solution**:
1. Device must be clean (factory reset recommended)
2. Or use adb in recovery mode / provisioning mode
3. Alternatively, skip device-owner for testing (non-critical features)

### No Audio Playback for STT Feedback
- TensorFlow Lite inference is silent by design
- Add Toast/UI feedback instead via MainActivity UI updates

### Battery Drain
- App runs continuous listening; battery usage expected
- Optimize: increase Silero VAD threshold, reduce microphone sampling rate
- Critical: disable in battery saver mode or whitelist app

## Development Notes

### Adding New Commands

Edit `VoiceCommandExecutor.kt`:

```kotlin
// Add new command mapping
normalizedText.contains("your command") -> yourAction()

// Implement the action
private fun yourAction(): Boolean {
	// Execute safe Intent-based action
	return true
}
```

### Integrating Custom Wake-Word

Replace Silero VAD with Porcupine (free tier available):
1. Get free Porcupine evaluation key: https://console.picovoice.co
2. Replace Silero VAD model in assets
3. Update `VoiceCommandService.kt` to use Porcupine SDK

### Improving Speaker Verification

1. Train custom speaker-embedding model (advanced):
   - Use TensorFlow SpeakerNet or GE2E
   - Convert to TFLite
   - Adjust cosine similarity threshold (currently 0.65)

2. Or use Porcupine Speaker Recognition (paid).

## Security Considerations

⚠️ **Important**:

- **Whitelist Only**: Never execute arbitrary commands from speech
- **Voice Confirmation**: Always confirm destructive actions
- **Speaker Verification**: For sensitive commands, require voice verification
- **No Audio Upload**: All models are local; no cloud dependency
- **Permissions**: Request only necessary permissions
- **Device Owner**: Only enable if you control the device

## Dependencies

- **Vosk**: `com.alphacephei:vosk-android:0.3.47` (Offline STT)
- **TensorFlow Lite**: `org.tensorflow:tensorflow-lite:2.14.0` (Speaker verification)
- **AndroidX**: `androidx.core:core-ktx:1.12.0`, `androidx.appcompat:appcompat:1.6.1`, etc.
- **Timber**: `com.jakewharton.timber:timber:5.0.1` (Logging)

## License

MIT License (or your preferred license)

## Contributing

Contributions welcome! Please open an issue or PR.

## Support

- Issues: GitHub Issues
- Discussions: GitHub Discussions

---

**Built with ❤️ using Kotlin, Vosk, and TensorFlow Lite**
