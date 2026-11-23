# Keystore & Release Signing Guide / 本番署名キー作成＆リリース手順書

This guide explains how to generate your own Android release keystore, sign APKs locally, and automate signed GitHub Releases with GitHub Actions — safely without ever committing secrets to Git.

このガイドでは、本番配布用の Android 署名キー（Keystore）の作成方法、ローカルでの署名ビルド、および GitHub Actions による自動署名リリースの手順を初心者向けに解説します。

---

## Step 1: Create a Release Keystore (1 Command) / 署名キーを作成する（1コマンド）

Run the following `keytool` command (included with JDK 17+) in your terminal. Replace the placeholder passwords with strong unique passwords and store them in a password manager:

ターミナルで以下のコマンドを実行します（パスワードは任意の強力な文字列に変更し、必ず安全なパスワードマネージャーに控えてください）：

```bash
keytool -genkeypair -v \
  -keystore hinata-release.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias upload \
  -storepass YOUR_STORE_PASSWORD \
  -keypass YOUR_KEY_PASSWORD \
  -dname "CN=Hinata, OU=NonCommercial, O=rengix-x, L=Tokyo, ST=Tokyo, C=JP"
```

> **CRITICAL / 重要**:
> - **NEVER commit `*.jks`, `*.keystore`, or `keystore.properties` to GitHub!** (`.gitignore` already blocks them.)
> - Keep a secure offline backup of `hinata-release.jks` and your passwords. If you lose the keystore, users cannot update an existing installation without uninstalling first.
> - **秘密鍵ファイル（`.jks`）とパスワードは絶対に GitHub にアップロードしないでください。** また、紛失すると既存ユーザーへの上書きアップデートができなくなるため、必ず安全な場所にバックアップしてください。

---

## Step 2: Build Signed Release APKs Locally / ローカル環境で署名ビルドする

### Option A: Environment Variables (Works out-of-the-box with `app/build.gradle.kts`)

```bash
export KEYSTORE_PATH="$(pwd)/hinata-release.jks"
export STORE_PASSWORD="YOUR_STORE_PASSWORD"
export KEY_PASSWORD="YOUR_KEY_PASSWORD"

# Single APK (arm64-v8a + armeabi-v7a)
./gradlew assembleRelease

# Split APKs (arm64-v8a, armeabi-v7a, universal)
./gradlew assembleRelease -PsplitAbi=true
```

### Option B: `keystore.properties` (Optional for Standalone Git Repositories)

If you export this repository outside AI Studio and prefer a local `keystore.properties` file in the project root (already ignored by `.gitignore`):

```properties
storeFile=hinata-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=upload
keyPassword=YOUR_KEY_PASSWORD
```

*(Note: When running inside AI Studio, keep `app/build.gradle.kts`'s default `signingConfigs` untouched and use Option A environment variables or GitHub Actions below.)*

---

## Step 3: Automated Signed Releases via GitHub Actions / GitHub Actions で自動署名リリースする

1. Encode your keystore file into a single Base64 string:
   - **macOS**:
     ```bash
     base64 -i hinata-release.jks | pbcopy
     ```
   - **Linux**:
     ```bash
     base64 -w 0 hinata-release.jks
     ```
   - **Windows (PowerShell)**:
     ```powershell
     [Convert]::ToBase64String([IO.File]::ReadAllBytes("hinata-release.jks")) | Set-Clipboard
     ```

2. Open your GitHub repository → **Settings → Secrets and variables → Actions → New repository secret**, and add these 4 secrets:

   | Secret Name | Value / 設定する値 |
   | --- | --- |
   | `KEYSTORE_BASE64` | Base64 string from Step 1 / 上記で変換した Base64 文字列 |
   | `STORE_PASSWORD` | Your keystore password / ストアパスワード |
   | `KEY_ALIAS` | `upload` (matches `app/build.gradle.kts`) |
   | `KEY_PASSWORD` | Your key password / キーパスワード |

3. Push a version tag (e.g. `v1.0.0`) or run the workflow manually from the **Actions** tab:

   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

GitHub Actions (`.github/workflows/android-ci-release.yml`) will automatically run all unit tests, build the ABI-split Release APKs (`arm64-v8a`, `armeabi-v7a`, `universal`), sign them with your keystore, and attach them to the GitHub Release!
