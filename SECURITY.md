# Security

## Reporting a vulnerability

Please do **not** open a public issue. Use GitHub's **Security → Report a vulnerability** (private vulnerability reporting) on this repository. Include the affected app, version, and steps to reproduce.

## Security model

A keyboard and an accessibility service can see and inject a lot, so the receiver treats the network as hostile:

- **Nothing is accepted before authentication.** Handshake timeout 10 s, idle timeout 15 s, at most 4 pending connections.
- **Paired phones** prove knowledge of a 256-bit token with a challenge/response (fresh nonces from both sides). The token is never sent over the network.
- **New phones** must enter a one-time 6-digit PIN shown on the receiver: 2-minute window, 5 attempts, plus a per-IP lockout after repeated failures. Pairing uses an ephemeral P-256 ECDH exchange; the token is derived on both sides.
- **After authentication every line is sealed** with AES-256-GCM (separate keys per direction, direction bound as AAD, 64-bit counter). Forged, corrupted or replayed frames drop the connection.
- **Only one phone is active at a time**; others get `BUSY`.
- The Accessibility add-on is reachable **only** through that authenticated link, never through an open port of its own.
- `android:allowBackup="false"` on both apps.

## Known limitations (read before trusting it on a shared network)

These come from reading the code; the project has not had an independent audit.

1. **PIN pairing is not a PAKE.** The PIN proofs are HMACs keyed by a 6-digit PIN over the public keys. An attacker who can actively intercept traffic *during the 2-minute pairing window* may be able to brute-force the PIN offline from an observed proof and pair themselves instead. Pair only on a network you trust, and only while you are looking at the screen. A PAKE (e.g. SPAKE2 / OPAQUE-style) would close this and is a welcome contribution.
2. **File transfer (port 8767) is not encrypted.** The one-time upload ticket is handed out over the encrypted link and is single-use, but the file bytes themselves travel over plain TCP on the LAN. Do not send sensitive files over untrusted networks.
3. **Broad permissions** (see the table in the README). The Accessibility add-on is opt-in; leave it off if you only need the keyboard.
4. **Signing keys.** Never commit a keystore. Anyone with your signing key can publish an APK that Android will accept as an update to your app.

## Supported versions

Only the latest release / `main` branch receives fixes.
