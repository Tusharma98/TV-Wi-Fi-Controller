# Wire protocol v1

The Remote (client, **C**) talks to the Receiver (server, **S**) over TCP, default port **8770**. Lines are UTF-8, terminated by `\n`, max 8 KiB. The reference implementation is `ImeProto.kt`, which is identical in both apps.

## 1. Plain handshake

```
C: HI 1 <clientId 32hex>
S: SRV 1 <tvId 16hex> <nonceS 32hex> <paired 0|1> <tvName urlenc>
```

### Paired phone

```
C: AUTH <nonceC 32hex> <authProof hex>
S: OK <srvProof hex>          or   NO UNPAIRED | NO BAD | NO BUSY
```

### New phone (receiver is showing a one-time PIN)

```
C: PAIR <pubC hex> <name urlenc>
S: PUB <pubS hex>             or   NO CLOSED
C: PROOF <hex>
S: OK <proof hex>             or   NO PIN <attemptsLeft>
```

## 2. Cryptography

| Item | Definition |
|---|---|
| Key agreement | ephemeral ECDH on P-256 (`secp256r1`); public keys are X.509-encoded |
| `mac(key, label, parts…)` | HMAC-SHA256 over `label ‖ 0x00 ‖ (len32(part) ‖ part)…` (length-prefixed, so part boundaries are unambiguous) |
| `token` | `mac(z, "token", pubC, pubS)` where `z` is the ECDH shared secret (256-bit, stored by both sides, never transmitted) |
| PIN proof | `mac(pin, "pin-<role>", pubC, pubS, name)` |
| `authProof` | `mac(token, "auth", clientId, nonceS, nonceC)` |
| `srvProof` | `mac(token, "srv", nonceC, nonceS)` |
| Session keys | `c2s = mac(token, "c2s", nonceS, nonceC)`, `s2c = mac(token, "s2c", nonceS, nonceC)` |

## 3. Sealed frames (after `OK`)

Every line in both directions is sent as:

```
hex( counter(8 bytes, big-endian) ‖ AES-256-GCM(ciphertext ‖ tag 16) )
```

- IV = `direction(1) ‖ 0 0 0 ‖ counter(8)`; direction `1` = client→server, `2` = server→client
- AAD = the direction byte
- The counter must be strictly increasing; a frame that fails authentication or is not newer than the last is **dropped and the connection closed**.

## 4. Keyboard commands (inside sealed frames)

| Dir | Line | Meaning |
|---|---|---|
| C→S | `K <code> [P\|D\|U]` | key event: press (default) / down / up (Android `KeyEvent` code) |
| C→S | `T <urlenc>` | insert text at the cursor |
| C→S | `R <urlenc>` | replace the whole text of the field |
| C→S | `PING <n>` | heartbeat |
| C→S | `G` | ask for `CAPS` and `STATE` |
| S→C | `CAPS <csv>` | what this receiver can inject; contains `a11y` while the Accessibility service is on |
| S→C | `STATE input=<0\|1>` | whether an editor currently has focus |
| S→C | `ERR <noinput\|unsupported\|badcmd> [detail]` | error |
| S→C | `PONG <n>` | heartbeat reply |

## 5. Accessibility extension (optional)

Opaque, URL-encoded lines handled by the receiver's Accessibility add-on. Peers that do not support it answer `ERR badcmd` or ignore it; the keyboard link never depends on it.

```
C→S: X <urlenc line>
S→C: X <urlenc line>
     long replies:  XP <part> … XP <part> X <last part>   (concatenate, then urldecode)
```

Inner lines, phone → receiver: `M dx dy` (move cursor), `C` (tap at the cursor), `S dx dy` (scroll), `MOUSE 0|1` (cursor on/off), `CLEAR` (close background apps), `FIELD` (focus a text field), `VOICE`, `LIST`, `ICON <pkg>`, `L <pkg>`, `A <label>`, `OPENF <name>`, `W` (Wi-Fi settings), `A11YSET` (Accessibility settings), `FILE` (request an upload ticket).
Receiver → phone: `APPS …`, `ICON <pkg> <b64>`, `MSG <text>`, `FILE <ticket>`.

### File transfer (port 8767)

The receiver hands out a **single-use, short-lived ticket** over the sealed link (`FILE <ticket>`). The phone then opens a separate TCP connection to port 8767 and sends:

```
writeUTF(ticket) · writeUTF(fileName) · writeLong(sizeBytes) · bytes…
```

The ticket is checked and consumed first; file names are sanitised (`/`, `\`, `:` replaced, leading dots trimmed, 120 chars max). The file is saved to the receiver's Downloads folder. **The file bytes are not sealed**, see [SECURITY.md](../SECURITY.md).

## 6. Limits

| Limit | Value |
|---|---|
| Max line length | 8192 bytes |
| Handshake timeout | 10 s |
| Idle timeout | 15 s (heartbeat keeps it alive) |
| Max pending (unauthenticated) connections | 4 |
| Per-IP failures before lock | 5 failures → 60 s lock |
| PIN | 6 digits, 2-minute window, 5 attempts |

## Changing the protocol

`ImeProto.kt` must stay identical in `remote-app` and `receiver-app`. Update this document and both `ImeProtoTest` golden vectors in the same change.
