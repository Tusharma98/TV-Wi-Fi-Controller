# TV Wi-fi Controller

Use your phone as a remote control and keyboard for an Android TV, Fire TV, tablet or any other Android device on the same network. Type with your phone's keyboard, move around with a D-pad, and (optionally) drive the whole screen with a mouse pad, gamepad and app launcher.

No cloud, no account, no internet connection needed. The two devices talk directly over your local network using an authenticated, encrypted link.

| App | Folder | Install it on | Package |
|---|---|---|---|
| **Remote** (controller) | [`remote-app/`](remote-app) | the phone that CONTROLS | `app.tvremote.remote` |
| **Receiver** (TV keyboard) | [`receiver-app/`](receiver-app) | the device that RECEIVES the typing | `app.tvremote.ime` |

## How it works

```
 ┌──────────────┐   TCP :8770, PIN pairing, then AES-256-GCM    ┌──────────────────────┐
 │  Remote app  │ ───────────────────────────────────────────▶ │     Receiver app     │
 │  (phone)     │ ◀─────────────────────────────────────────── │ keyboard (IME) +     │
 └──────────────┘          same Wi-Fi or phone hotspot          │ optional Accessibility│
                                                                └──────────────────────┘
```

- The Receiver is a real Android **input method** (system keyboard) plus a TCP server on port **8770**. The keyboard works on its own.
- The Remote scans the local /24 networks (Wi-Fi and phone hotspot) for the receiver, or you can type its IP address. It reconnects by itself and finds the TV again if its IP changes.
- First connection: the receiver shows a one-time **6-digit PIN**; enter it on the phone. After that the phone is paired and reconnects automatically.

Full wire format: [`docs/PROTOCOL.md`](docs/PROTOCOL.md). Security notes: [`SECURITY.md`](SECURITY.md).

## Features

**Keyboard link (no extra permissions on the receiver):**
- Type text on the phone, it appears in the focused field on the receiver; replace a field's whole text
- Enter, Backspace, D-pad, Back, volume and media keys
- Auto-reconnect, heartbeat, several TVs supported, status display

**Optional Accessibility add-on (enable it on the receiver, setup step 4):**
- D-pad / OK / Back / Home / Recents / Power / Menu even when no text field is focused
- Mouse pad with an on-screen cursor, gamepad
- Favourite apps and an app launcher
- "Clear all" (close background apps), voice input
- Send a file from the phone to the receiver's Downloads folder
- Open the receiver's Wi-Fi or Accessibility settings from the phone

When the add-on is off, nothing changes for the keyboard.

## Install

1. Build both APKs (below) or download them from the **Actions** tab / **Releases** page of this repository.
2. Install the **Receiver** APK on the TV / target device, the **Remote** APK on your phone. (You may need to allow installs from unknown sources.)
3. Open the Receiver and follow the green button: **1** enable the keyboard, **2** select it, **3** pair a new phone (shows a PIN), **4** optional Accessibility add-on.
4. Open the Remote, pick the receiver from the list (or type its IP), and enter the PIN.

Requirements: Remote Android 6.0+ (API 23), Receiver Android 7.0+ (API 24). Both devices on the same network. Some routers block device-to-device traffic ("AP / client isolation"), which will prevent discovery; use the phone's hotspot instead, or disable isolation.

## Build from source

Needs JDK 17 and Gradle 8.7 (or just open each folder in Android Studio).

```bash
cd receiver-app && gradle assembleRelease testReleaseUnitTest
cd ../remote-app && gradle assembleRelease testDebugUnitTest
```

APKs land in `<app>/app/build/outputs/apk/release/`.

> Tip: run `gradle wrapper --gradle-version 8.7` inside each app folder and commit the generated `gradlew` files if you want contributors to build without installing Gradle.

### Signing

- **Remote**: release builds are signed with the local debug key. Fine for sideloading.
- **Receiver**: provide your own key through `receiver-app/keystore.properties` (copy `keystore.properties.example`; the file is git-ignored) or the `RELEASE_*` environment variables. Without one it falls back to the debug key.
- Android only installs an update over an existing app if both are signed with the **same key**. Keep your release keystore backed up and **never commit it**.

### GitHub Actions

`.github/workflows/build.yml` builds and tests both apps on every push and pull request and uploads the APKs as artifacts. To sign the receiver with your release key in CI, add these repository secrets: `KEYSTORE_BASE64` (`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Permissions (receiver)

The receiver asks for more than a typical app, so here is why. Nothing is requested automatically; powerful ones are optional.

| Permission / service | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | the TCP link and file receive on the local network (no internet traffic is made) |
| Input method service | the keyboard itself |
| Accessibility service (optional) | focus moves, clicks, gestures, mouse pad, Back/Home/Recents |
| `SYSTEM_ALERT_WINDOW` (optional) | on-screen mouse cursor and the Home action, which an input method cannot send |
| `QUERY_ALL_PACKAGES` | list and launch apps for the favourites / app launcher |
| `KILL_BACKGROUND_PROCESSES` | "Clear all" |
| `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT` | "Select to open" prompts when Android blocks launching from the background |
| `WAKE_LOCK`, `ACCESS_WIFI_STATE` | keep CPU and Wi-Fi awake in low-latency mode only while a phone is connected |
| `WRITE_EXTERNAL_STORAGE` (API ≤ 28) | save received files to Downloads on old Android versions |

The remote only needs network, foreground-service and notification permissions.

## Project layout

```
remote-app/      phone app (controller)   - Kotlin, no AndroidX
receiver-app/    TV app (keyboard + server)
docs/PROTOCOL.md   wire protocol
.github/workflows  CI for both apps
```

`ImeProto.kt` exists in **both** apps and must stay byte-identical. A golden-vector unit test in each project fails if they drift. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Limitations

- Local network only; there is no relay or remote access over the internet.
- One phone can be active at a time per receiver.
- Not independently security-audited, see [SECURITY.md](SECURITY.md) before using it on networks you do not trust.

## License

[MIT](LICENSE)
