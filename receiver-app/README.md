# Receiver app (TV Remote Keyboard) - applicationId app.tvremote.ime
Install on the device that RECEIVES the typing. It is a system keyboard (InputMethodService) + a TCP server on :8770.
Build: open this folder in Android Studio, or run `gradle assembleRelease testReleaseUnitTest` here (CI: root `.github/workflows/build.yml`). More in the [root README](../README.md).

## Setup
Open the app and follow the green button: 1 enable the keyboard, 2 select it, 3 pair a new phone (PIN). The keyboard works on its own.

## Optional: Accessibility add-on (step 4 on the setup screen)
Turn on **TV Remote Keyboard** in Accessibility (the phone's Accessibility button opens this screen on the receiver for you).
When it is on, the receiver announces `a11y` and the phone unlocks: D-pad / OK / Back / Home / Recents / Power / Menu without a focused field,
mouse pad, gamepad, favourite apps, Clear all, voice, and file receive. When it is off, nothing changes for the keyboard.
All of this travels over the same authenticated, encrypted link (`X` extension lines). File receive uses a one-time ticket from that link (port 8767).

## Latency
Cached AES-GCM cipher/key per direction, buffered socket reads, TCP_NODELAY + low-delay traffic class, and while a phone is connected a CPU wake lock + Wi-Fi low-latency lock.
