# Contributing

Thanks for helping out. Bug reports, fixes and ideas are welcome.

## Setup

- JDK 17, Android SDK 34, Gradle 8.7 (or Android Studio)
- Two independent Gradle projects: `remote-app/` and `receiver-app/`. Open them separately.

```bash
cd receiver-app && gradle assembleRelease testReleaseUnitTest
cd remote-app   && gradle assembleRelease testDebugUnitTest
```

## The one rule: `ImeProto.kt` is shared

`app/src/main/java/app/tvremote/imeproto/ImeProto.kt` must be **identical** in both apps. If you change the protocol, change both copies in the same commit, update [`docs/PROTOCOL.md`](docs/PROTOCOL.md), and make sure the golden-vector tests (`ImeProtoTest`) pass in both projects. Check quickly with:

```bash
diff remote-app/app/src/main/java/app/tvremote/imeproto/ImeProto.kt \
     receiver-app/app/src/main/java/app/tvremote/imeproto/ImeProto.kt
```

## Pull requests

- Keep changes focused; describe what and why.
- Add or update unit tests (`ImeServerTest`, `ImeClientTest`, ...) for protocol or connection logic.
- Never commit keystores, passwords, or personal data.
- Security issues: see [SECURITY.md](SECURITY.md) instead of opening a PR or issue.
