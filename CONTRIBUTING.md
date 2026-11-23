# Contributing to Hinata

Thank you for your interest in contributing to **Hinata**!
Because Hinata runs an always-on Accessibility overlay at up to 120Hz alongside an isolated Material 3 settings UI, please review the architectural rules below before opening a Pull Request.

---

## 5 Non-Negotiable Engineering Rules

1. **ZERO Jetpack Compose in `com.edgeflow.service.*` & Zero-GC Drawing**
   - Never import `androidx.compose.*` anywhere under `app/src/main/java/com/edgeflow/service/`.
   - Loading Compose inside the resident process balloons background RAM from ~12 MB to 60+ MB.
   - Inside `onDraw()` and `onTouchEvent()` of any overlay `View` (`EdgeTriggerView`, `QuickArcMenuView`, `TranslateFloatingControlView`, `TranslationOverlayView`, etc.), **never allocate objects** (`Paint`, `Rect`, `RectF`, `Path`, lambdas, or collections). Pre-allocate fields on the class and reuse them.

2. **Always Import `com.example.R` (`android.nonTransitiveRClass = true`)**
   - The Gradle `namespace` is `com.example` while `applicationId` is `io.github.rengixx.hinata`.
   - Always reference app resources via `import com.example.R`.

3. **Respect Process Isolation (`:settings`) & `MlKitSafeInit`**
   - `SettingsActivity` runs in `android:process=":settings"`, while `EdgeAccessibilityService` and `EdgeConfigProvider` run in the default process.
   - All config mutations from `:settings` must go through `ConfigStore` → `EdgeConfigProvider` IPC (never assume static in-memory singletons are shared across processes).
   - Always call `MlKitSafeInit.ensureInitialized(context)` before touching ML Kit APIs inside `:settings`, as Firebase/ML Kit `ContentProvider` initialization runs only in the default process.

4. **No Hardcoded User-Facing Strings (4-Locale Parity)**
   - Every visible label, toast, dialog, and content description must be defined in all 4 resource files:
     - `app/src/main/res/values/strings.xml` (English)
     - `app/src/main/res/values-ja/strings.xml` (Japanese)
     - `app/src/main/res/values-zh-rCN/strings.xml` (Simplified Chinese)
     - `app/src/main/res/values-hi/strings.xml` (Hindi)
   - For settings icons not included in `material-icons-core`, add clean 24×24 Material Rounded paths to `app/src/main/java/com/example/ui/icons/HinataIcons.kt` instead of re-adding `material-icons-extended`.

5. **AGSL `LiquidGlassShader` Safety Rules**
   - Never use `hypot()` in AGSL shaders (unsupported on certain Android 13–14 GPUs); use `length(vec2(dx, dy))` instead.
   - Always guarantee `edge0 < edge1` in every `smoothstep(edge0, edge1, x)` call to avoid undefined GPU NaN artifacts.

---

## Step-by-Step Extension Recipes

### Recipe 1: Adding a New Gesture Action
1. **`EdgeConfig.kt`**: Add the new enum entry to `EdgeActionType` (preserve existing enum names for serialization compatibility).
2. **`ActionExecutor.kt`**: Implement the execution branch inside `execute(...)`. Keep all logic pure Android/Kotlin (no Compose).
3. **`ActionGlyphPathBuilder.kt`**: Add a crisp geometric vector path for the resident overlay droplet/arc menu glyph.
4. **`ActionPickerSheet.kt`**:
   - Add a display label mapping (`actionLabelRes`) and icon mapping (`actionIcon`) using `Icons.Rounded.*` (core) or `HinataIcons.*`.
   - Include the action in the picker list.
5. **Strings & Tests**: Add the label string to all 4 `strings.xml` files and add a round-trip serialization test in `app/src/test/java/com/edgeflow/`.

### Recipe 2: Adding a New UI Language
1. Create `app/src/main/res/values-<locale>/strings.xml` translating all entries from `values/strings.xml`.
2. Add the locale code to:
   - `app/src/main/res/xml/locales_config.xml`
   - `androidResources { localeFilters += ... }` in `app/build.gradle.kts`
   - `AppLanguageOption` in `app/src/main/java/com/edgeflow/data/EdgeConfig.kt` (and its display label in `GeneralSettingsScreen.kt` / `OnboardingScreen.kt`).

### Recipe 3: Adding Support for a New OEM / ROM
1. Inspect `app/src/main/java/com/edgeflow/data/DeviceCompatibilityHelper.kt`.
2. Add manufacturer/system-property detection to `DeviceFamily` and define its `DeviceCapabilities` flags (floating window capability, HyperOS/OEM settings intents, navigation bar Shizuku keys).
3. Add a unit test verifying detection and fallback behavior for the new device family.

---

## Before Opening a Pull Request

Run all checks locally and make sure everything passes cleanly:

```bash
# 1. Verify zero Compose imports in resident service package
grep -rI "androidx.compose" app/src/main/java/com/edgeflow/service/

# 2. Run all unit tests
./gradlew testDebugUnitTest

# 3. Verify Release R8 shrinking & ABI splits compile cleanly
./gradlew assembleRelease -PsplitAbi=true
```
