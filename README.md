# StreamPlayer

A resilient Android background audio streaming app that plays live internet radio and audio streams. Built to survive crashes, network drops, device reboots, Doze mode, and aggressive OEM battery killers (e.g. Samsung).

## Features

- **Live stream playback** — ExoPlayer (Media3) with HLS support and OkHttp networking
- **Persistent foreground service** — always visible notification with Play/Stop controls
- **Auto-start on boot** — stream starts automatically when the device reboots (configurable)
- **Auto-relaunch if stopped** — watchdog alarms and WorkManager restart the stream after crashes or network loss (configurable)
- **Network auto-recovery** — detects when connectivity is restored and reconnects immediately
- **Headphone disconnect handling** — pauses when headphones or Bluetooth audio device is unplugged
- **Adaptive buffering** — 8–30 s buffer with stall detection and exponential backoff retries
- **Per-app volume** — independent of system volume (1–100%)
- **Battery-aware** — Doze-resistant alarms, WhileIdle wakeups, battery optimization whitelist dialog
- **Parallel audio** — does not claim audio focus; stream plays alongside GPS, music, or calls

## Requirements

- Android 5.1 (API 22) or higher
- Android Studio Hedgehog or later
- JDK 17

## Build

```bash
# Clone and open in Android Studio, or build from command line:

# Debug APK
./gradlew assembleDebug

# Release APK (unsigned — sign separately or configure signing via env vars)
./gradlew assembleRelease

# Release AAB (for Google Play)
./gradlew :app:bundleRelease
```

The output APK is named `StreamPlayer-{versionName}-{buildType}.apk`.

## Settings Reference

Open the app and tap the **Settings** button to configure:

| Setting | Default | Description |
| --- | --- | --- |
| Stream Name | `Live Stream` | Display name shown in the notification |
| Stream URL | radiojar default | Full HTTP(S) URL of the audio stream |
| Auto-start on Boot | **Enabled** | Start the stream automatically after device reboot |
| Auto-relaunch if Stopped | **Enabled** | Watchdogs automatically restart the stream after a crash, network loss, or stop |
| Reconnect Delay | 5 s | Base delay before the first reconnect attempt |
| Max Retries | −1 (infinite) | Maximum reconnect attempts; −1 = retry forever |
| Playback Volume | 100% | App-level volume independent of the system volume knob |

### Auto-start on Boot

When enabled, `BootReceiver` intercepts `BOOT_COMPLETED` and starts the foreground service automatically. Disable this if you only want to start playback manually.

### Auto-relaunch if Stopped

When enabled (default), two independent watchdogs keep the stream alive:

- **AlarmManager watchdog** — fires every 2 minutes using `setExactAndAllowWhileIdle()` (Doze-resistant). Suitable for Samsung and other OEMs with aggressive battery killers.
- **WorkManager watchdog** — fires every 15 minutes (Android minimum). Google-recommended background mechanism.

When disabled, pressing **Stop** keeps the stream stopped until you manually press **Play** again. Watchdogs will not restart the service.

## Architecture

```text
StreamPlayer
├── MainActivity              — Player UI (Play / Stop / Settings)
├── SettingsActivity          — Configuration screen
│
├── service/
│   └── AudioStreamService    — Foreground service; ExoPlayer lifecycle, retry logic, buffering
│
├── receiver/
│   ├── BootReceiver          — Starts service on BOOT_COMPLETED
│   ├── RestartReceiver       — AlarmManager 2-min watchdog
│   ├── NetworkReceiver       — Reconnects on network restore
│   └── BecomingNoisyReceiver — Stops on headphone unplug
│
├── worker/
│   └── WatchdogWorker        — WorkManager 15-min watchdog
│
├── model/
│   └── StreamConfig          — Immutable data class for all user settings
│
├── repository/
│   └── StreamRepository      — SharedPreferences read/write
│
├── notification/
│   └── NotificationHelper    — Persistent media-style notification builder
│
└── ui/
    ├── MainViewModel         — State for MainActivity
    └── SettingsViewModel     — State + validation for SettingsActivity
```

**Tech stack:** Kotlin · Media3/ExoPlayer · WorkManager · AlarmManager · MVVM · LiveData · SharedPreferences · Material Design 3

## Google Play — Automated Publishing

The repository includes a GitHub Actions workflow at [`.github/workflows/deploy-to-play.yml`](.github/workflows/deploy-to-play.yml) that automatically builds, signs, and uploads the app to Google Play whenever you push a version tag.

### How it works

1. Push a version tag (`v1.0.1`) → workflow triggers
2. JDK 17 is set up and Gradle builds a release AAB
3. AAB is signed with your keystore
4. Signed AAB is uploaded to the **Internal testing** track on Google Play
5. Keystore file is deleted from the runner

### Step-by-step setup

#### 1. Create a Google Play Service Account

1. Open [Google Play Console](https://play.google.com/console) → **Setup → API access**
2. Click **Link to a Google Cloud project** (create one if needed)
3. In [Google Cloud Console](https://console.cloud.google.com/), go to **IAM & Admin → Service Accounts**
4. Click **Create Service Account** → give it a name (e.g. `github-actions-deploy`)
5. Click **Create and Continue** → skip role assignment → click **Done**
6. Click the new service account → **Keys** tab → **Add Key → Create new key → JSON**
7. Download the JSON file — this is your `GOOGLE_PLAY_JSON_KEY`
8. Back in Play Console, under **Setup → API access**, grant the service account **Release manager** (or **Admin**) permissions for your app

#### 2. Encode the keystore for GitHub

The project uses the keystore at `piisoft.android.keystore` (stored outside the repository for security). Encode it to Base64 for the GitHub Secret:

```bash
# macOS / Linux
base64 -i piisoft.android.keystore | tr -d '\n'

# Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("piisoft.android.keystore"))
```

Copy the output — it becomes the `KEYSTORE_BASE64` secret value.

#### 3. Add GitHub Secrets

In your repository, go to **Settings → Secrets and variables → Actions → New repository secret** and add:

| Secret name | Value |
| --- | --- |
| `KEYSTORE_BASE64` | Base64-encoded content of `piisoft.android.keystore` (from step 2) |
| `KEYSTORE_PASSWORD` | Your keystore password |
| `KEY_ALIAS` | Your key alias (check with `keytool -list -keystore piisoft.android.keystore`) |
| `KEY_PASSWORD` | Your key password (same as `KEYSTORE_PASSWORD` for PKCS12) |
| `GOOGLE_PLAY_JSON_KEY` | Full contents of the JSON file downloaded in step 1 |

> Never commit passwords or secrets to the repository. Store them only in GitHub Secrets.

#### 4. Update version and push a tag

Bump `versionCode` and `versionName` in [app/build.gradle.kts](app/build.gradle.kts), commit, then:

```bash
git tag v1.0.1
git push origin v1.0.1
```

The workflow will run automatically. Check progress under **Actions** in your GitHub repository.

#### 5. Change the release track (optional)

By default the workflow uploads to the **Internal testing** track. Edit the `track` field in the workflow to promote releases:

```yaml
track: internal    # internal → alpha → beta → production
```

### Local release build (without CI)

Set the signing environment variables and run Gradle:

```bash
export KEYSTORE_PATH=/path/to/release.jks
export KEYSTORE_PASSWORD=your-keystore-password
export KEY_ALIAS=my-key-alias
export KEY_PASSWORD=your-key-password

./gradlew :app:bundleRelease
# Signed AAB → app/build/outputs/bundle/release/
```

## Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Build and test: `./gradlew assembleDebug`
4. Commit your changes and open a pull request

## License

MIT — see [LICENSE](LICENSE) if present, otherwise all rights reserved.
