# Remote app (controller) - applicationId app.tvremote.remote
Install on the device that CONTROLS. Finds the receiver on the same Wi-Fi / hotspot (or type its IP), pairs with the PIN shown on the receiver,
then sends text, D-pad, Enter, Backspace, Back, volume and media keys. Reconnects by itself.
Build: open this folder in Android Studio, or run `gradle assembleRelease testDebugUnitTest` here (CI: root `.github/workflows/build.yml`). More in the [root README](../README.md).

## Tray
- Accessibility OFF on the TV: the tray has one slide: **Keyboard** + **Accessibility**. The Accessibility button opens the TV's Accessibility settings on the TV.
- Accessibility ON: two more slides appear (swipe as before): **Favourite apps**, and **Mouse / Gamepad / Send file**.
- Gear button: tap = Settings key, **hold = Wi-Fi settings on the TV**.
- Long-press the Wi-Fi pill / "Disconnect" chip: Control mode (status, pair, switch TV, shortcuts, forget).
Needs the Receiver app (app.tvremote.ime) on the other device.
