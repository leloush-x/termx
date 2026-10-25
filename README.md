# TermX

A light, fast Android terminal for proot Linux distros (Wolfi by default, plus Alpine, Debian and Void) that runs without root, Shizuku or Shevery.

**Status:** Phase 1 terminal surface is implemented and passes CI. Its on-device interaction gate is pending. The plan lives in [docs/EXECUTION_PLAN.md](docs/EXECUTION_PLAN.md).

## Builds

- Every push runs the CI workflow at `.github/workflows/android.yml`. It runs unit tests, builds `TermX-<version>-direct.apk`, checks 16 KB alignment, and uploads the APK as the `termx-apk` artifact.
- Pushing a tag `v<versionName>` (for example `v0.1.0`) also publishes a GitHub release with the APK and its SHA-256. The release job refuses unsigned APKs and tags that don't match `versionName`.
- Local build (optional, needs Android SDK 37 and JDK 17): `./gradlew assembleDirectDebug`

## Release signing (GitHub Actions secrets)

Set these under Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `TERMX_KEYSTORE_B64` | `base64 -w0 termx-release.keystore` |
| `TERMX_KEYSTORE_PASSWORD` | keystore password |
| `TERMX_KEY_ALIAS` | key alias |
| `TERMX_KEY_PASSWORD` | key password |

Without these, branch builds still produce an unsigned APK for testing, but releases are refused.
Back up the keystore somewhere safe. If it is lost, existing installs cannot be updated.

## Docs

- [docs/AI_BUILD_PROMPT.md](docs/AI_BUILD_PROMPT.md): the prompt for the AI build agent, with sources to adapt
- [docs/EXECUTION_PLAN.md](docs/EXECUTION_PLAN.md): phases, gates and status
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): full design and budgets
- [docs/DECISIONS.md](docs/DECISIONS.md): decision log
- [docs/REFERENCES.md](docs/REFERENCES.md): pinned sources and their licenses
- [NOTICE](NOTICE): third-party code and licenses

## License

MIT. See [LICENSE](LICENSE).
