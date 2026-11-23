# Hinata v1.0.0 — Initial Public Release

**100% Free · Open Source (GPLv3) · Zero Ads · Zero Tracking**
Android 10–16 (API 29–36) · Application ID: `io.github.rengixx.hinata` · Author: `rengix-x`

---

## Highlights / 主な特徴

- **3-Stage Edge Gestures with 14dp Hysteresis** — Smooth left, right, and bottom edge triggers with diagonal swipes, independent Short / Long / Deep actions, and optional bottom gesture bar support during keyboard (IME) input.
- **120fps Prism Glass Visuals** — GPU-accelerated AGSL refraction droplet & translucent arc overlays (Android 13+, graceful fallback on Android 10–12) built in a zero-Compose, zero-GC resident process.
- **One-Stroke Rotating Arc Menu (up to 16 slots)** — Fast single-gesture launching for apps, floating mini-windows, shortcuts, URLs, and system actions, plus Fixed Edge Bar, Tap-to-Select, and Swipe-and-Return modes.
- **One-Tap Screen Translate** — Compact floating edge button (`文A`) with zero background polling, zero battery drain, and zero flicker. Offline by default (Google ML Kit OCR + 59-language on-device translation), plus optional opt-in High-Quality Online Translation and auto-clear on scroll.
- **Shizuku Integration (Optional, No Root)** — Hide system navigation bars, suppress conflicting OS edge back gestures, launch genuine floating windows, and repair native Google Circle to Search on CN ROMs.
- **5-OEM Auto-Adaptation & 4 Languages** — HyperOS (Xiaomi/POCO), ColorOS/OxygenOS/realme UI, OriginOS, One UI, and AOSP/Pixel in **English, 日本語, 简体中文, and हिन्दी**.

---

## Which APK Should I Download? / どの APK を選べばよいですか？

| File Name | Recommended For / 対象端末 |
| --- | --- |
| **`app-arm64-v8a-release.apk`** *(Recommended / 推奨)* | **99% of modern Android phones** (2018+, Snapdragon / Dimensity / Exynos / Tensor 64-bit ARM). Smallest download & fastest install. / **最近のほとんどの Android スマホ（64bit ARM）はこちらが最軽量・おすすめです。** |
| **`app-armeabi-v7a-release.apk`** | Older 32-bit ARM devices only. / 古い 32bit ARM 端末向け。 |
| **`app-universal-release.apk`** | Works on both 64-bit and 32-bit ARM devices if you are unsure. / 端末のアーキテクチャが分からない場合の汎用版。 |

---

## Quick Setup Tips / 初期設定のポイント

1. **Enable Accessibility Service / ユーザー補助をオンにする**: Open Android Settings → Accessibility → Downloaded Apps → **Hinata** → ON.
2. **Set Battery to Unrestricted / バッテリー制限を「制限なし」にする**: Prevents HyperOS / OEM battery savers from stopping edge gestures in the background.
3. **HyperOS Autostart (Xiaomi / POCO)**: Enable **Autostart** in App Info so Hinata resumes automatically after reboot.
4. **Optional Floating Windows & System Nav Control**:
   - **With Shizuku ON (Recommended)**: Works directly from any screen with zero Control Center setup.
   - **HyperOS without Shizuku**: Place the *"Floating windows"* tile on Page 1 of Control Center if fallback macro mode is needed.

---

## License & Credits

- Licensed under the **GNU General Public License v3.0 (GPLv3)** (`Copyright (C) 2026 rengix-x` — Open Source (GPLv3)).
- See [CREDITS.md](CREDITS.md) for open-source attributions and disclaimers.
