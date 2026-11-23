# Hinata

**100% Free · Open Source (GPLv3) · Zero Ads · Zero Tracking**
Developed by **`rengix-x`**

An ultra-lightweight, privacy-first Android edge gesture, 120fps Prism Glass arc menu, and one-tap on-device screen translator — born on Xiaomi / POCO HyperOS and engineered to work seamlessly across **all Android 10–16 devices**.

**完全無料・オープンソース（GPLv3）・広告ゼロ・トラッキングゼロ**
Xiaomi / POCO HyperOS の操作性向上から生まれ、すべての Android 10〜16 端末に対応する超軽量エッジジェスチャー＆ワンタップ画面翻訳アプリです。

---

## Key Features / 主な機能

- **3-Stage Edge Gestures with 14dp Hysteresis / 3段階エッジジェスチャー＆14dpヒステリシス**
  - Left, right, and bottom edge trigger zones with independent Short, Long (Stage 2), and Deep (Stage 3) actions, plus diagonal up/down swipes.
  - 14dp hysteresis prevents accidental stage jitter; optional bottom gesture bar support even while the keyboard (IME) is open.
- **120fps Prism Glass AGSL Refraction Shader / 120fps プリズムグラス AGSL 屈折シェーダー**
  - Real-time GPU-accelerated liquid droplet & glass refraction feedback (Android 13+ AGSL `RuntimeShader`, with smooth fallback on Android 10–12).
  - Pure `android.view.View` + `Canvas` resident overlay with zero heap allocations inside `onDraw()` / `onTouchEvent()`.
- **One-Stroke Rotating Arc Quick Menu / ワンストローク回転アークメニュー（最大16スロット）**
  - Launch apps, floating windows, shortcuts, URLs, or system actions in a single continuous swipe without lifting your finger, or choose Fixed Edge Bar / Tap-to-Select / Swipe-and-Return modes.
- **Recent App History Switcher / アプリ履歴スイッチャー**
  - Instant visual app switcher backed by accessibility window tracking and optional `PACKAGE_USAGE_STATS` history backfill.
- **One-Tap Screen Translate / ワンタップ画面翻訳**
  - Compact edge-docked `文A` floating button (`46dp × 68dp`) with zero background polling, zero battery drain, and zero flicker.
  - **Offline by default** via Google ML Kit (Latin, Japanese, Chinese, Korean, Devanagari OCR + 59-language on-device translation), plus an **opt-in High-Quality Online Engine** with explicit first-time consent.
  - Optional auto-clear on scroll so stale text never floats over scrolled content.
- **Optional Shizuku Integration / 任意の Shizuku 連携（Root不要）**
  - Hide system navigation bar / suppress conflicting OS edge back gestures, launch genuine floating mini-windows, and repair Google Assistant for native Circle to Search on CN ROMs.
- **5-OEM Auto-Adaptation & 4 Languages / 5大OEM自動適応＆4言語対応**
  - Automatic compatibility tuning for **HyperOS (Xiaomi/POCO)**, **ColorOS / OxygenOS / realme UI**, **OriginOS (vivo/iQOO)**, **One UI (Samsung)**, and **AOSP / Pixel / Xperia / Nothing / Motorola**.
  - Full UI localization in **English**, **日本語**, **简体中文**, and **हिन्दी**.

---

## Honest Privacy Policy / 誠実なプライバシーポリシー

- **Zero Telemetry / Zero Analytics / Zero Ads**: Hinata itself contains no analytics SDKs, crash trackers, or advertising libraries, and disables Android backup (`android:allowBackup="false"`) and cleartext HTTP traffic (`cleartextTrafficPermitted="false"`).
- **Default Local Operation**: During normal gesture navigation and offline screen translation, Hinata never uploads screenshots, images, or gesture history.
- **Two Explicit HTTPS Network Uses Only**:
  1. **Google ML Kit Offline Models**: Downloading and managing on-device ML Kit offline translation language packs (~30 MB, Wi-Fi-only toggle enabled by default), including standard HTTPS model delivery and management requests performed internally by the Google ML Kit SDK.
  2. **Opt-in Online High-Quality Translation**: Disabled by default. Only enabled after explicit user consent in Screen Translate Settings. Even when enabled, **only extracted plain text is sent over HTTPS — screenshots and images are never transmitted**, and password fields / `FLAG_SECURE` screens are automatically excluded. You can switch back to 100% offline mode at any time.

---

## Architecture & Developer Guide / アーキテクチャ設計

Hinata enforces strict **dual-process isolation** to keep background RAM usage minimal (~10–15 MB) while offering a rich Material 3 settings experience:

| Process | Entry Point | Tech Stack | Purpose |
| --- | --- | --- | --- |
| Default (`io.github.rengixx.hinata`) | `EdgeAccessibilityService`, `EdgeConfigProvider`, `FloatingProxyActivity` | Pure Kotlin + Android `View` / `Canvas` + AGSL (`RuntimeShader`) | Always-on 120Hz touch interception, Prism Glass overlays, Arc Menu, One-Tap Screen Translate (**ZERO Jetpack Compose imports**) |
| `:settings` (`io.github.rengixx.hinata:settings`) | `SettingsActivity` | Jetpack Compose + Material 3 + `HinataIcons` | Onboarding, gesture tuning, diagnostics, translate settings — completely reclaimed by Android when closed |

### Directory Map

```text
app/src/main/java/
├── com/edgeflow/
│   ├── data/                # EdgeConfig, ConfigStore, EdgeConfigProvider (IPC), OEM detection, MlKitSafeInit
│   ├── service/             # Resident process ONLY (NO Compose): EdgeAccessibilityService, EdgeOverlayController,
│   │   │                    # ActionExecutor, LiquidGlassShader, FloatingWindowLauncher, ControlCenterFloatingMacro
│   │   ├── shizuku/         # ShizukuShellExecutor, NavBarUserService (AIDL)
│   │   └── translate/       # One-tap LiveScreenTranslateController, TranslateFloatingControlView,
│   │                        # StructuredNodeTextExtractor, WindowCleanCapturer, OnDeviceTranslationPipeline
│   └── settings/            # Isolated :settings Compose UI (Onboarding, Zones, Arc, Actions, Blacklist, Guide, Translate)
└── com/example/ui/
    ├── icons/HinataIcons.kt # Self-contained Material Rounded 24x24 vector paths (zero material-icons-extended bloat)
    └── theme/               # Material 3 Dynamic Color, OS-native SansSerif Typography & Tabular Numerics (`tnum`)
```

> Note: Internal Kotlin package names (`com.edgeflow.*` and `com.example.*`) are intentionally preserved for backward compatibility with existing configuration storage (`edgeflow_config.cfg`), AndroidManifest components, and unit tests, while the public `applicationId` is `io.github.rengixx.hinata`.

---

## Building from Source / ビルド手順

Requirements: **JDK 17+**, **Android SDK 36** (`compileSdk = 36`, `minSdk = 29`).

```bash
# 1. Run all unit tests
./gradlew testDebugUnitTest

# 2. Build a single optimized Release APK (arm64-v8a + armeabi-v7a, R8 Full Mode)
./gradlew assembleRelease

# 3. Build ABI-split Release APKs (arm64-v8a, armeabi-v7a, and universal)
./gradlew assembleRelease -PsplitAbi=true
```

For production release signing (local `keystore.properties` or GitHub Actions Secrets), see **[docs/KEYSTORE_GUIDE.md](docs/KEYSTORE_GUIDE.md)**.

---

## Contributing, License & Credits

- **Contributing Guide**: [CONTRIBUTING.md](CONTRIBUTING.md)
- **Keystore & Release Signing Guide**: [docs/KEYSTORE_GUIDE.md](docs/KEYSTORE_GUIDE.md)
- **Release Notes (v1.0.0)**: [RELEASE_NOTES_v1.0.0.md](RELEASE_NOTES_v1.0.0.md)
- **Open-Source Credits & Disclaimers**: [CREDITS.md](CREDITS.md)
- **License**: [GNU General Public License v3.0 (GPLv3)](LICENSE) (`Copyright (C) 2026 rengix-x` — Open Source (GPLv3))
