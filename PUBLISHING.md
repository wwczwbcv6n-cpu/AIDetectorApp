# Publishing the AI Detector App

This doc covers shipping `AIDetectorApp` to the Google Play Store and Apple App Store. The shipped flow is API-first: the app seals the file (TSE2 envelope), posts it to the Tayanch API with the user's key, shows the server's verdict, and can request the heat map with the returned token. `HeuristicAIDetector` is only the OFFLINE ESTIMATE the app falls back to (clearly labelled) when the API is unreachable or not configured — it is not the product and there is no plan to ship a model file in the app (updated 2026-09-19).

---

## Current state

**Works (2026-09-19):**
- API path — the product. `ApiClient` seals the request (TSE2, HPKE via BouncyCastle), classifies the server's `/pubkey` attestation state against the pinned policy, posts to `/analyze` with the X-API-Key, and decodes the served schema (`ApiAnalysisResult`, pinned by the main repo's `contract/api_v2.json`).
- No offline verdict — when the server is not reached (offline, timeout, no server configured) the app shows "Not analyzed" with no percentage. The old `HeuristicAIDetector` fallback was never measured and cleared most AI photos as "Likely authentic" (audit 2026-09-24 APP-01); only a file that confesses its own generation in metadata is still decided on the device.
- History, settings, image picker, share intent — functional.
- Gates: `./gradlew :shared:desktopTest` (DTO parsing + TSE2 vectors) and `./gradlew :androidApp:assembleDebug` must be green before a build is uploaded.

**Store-readiness issues:** the May 2026 table that stood here listed prototype-era problems and was stale; re-audit against the current code before a store submission (icon, name, privacy strings, data-safety form) and keep the list here.

## Android → Google Play Store

### Prerequisites
- Google Play Console account ($25 one-time): <https://play.google.com/console>
- Android SDK (Linux): install Android Studio or `sdkmanager` from cmdline-tools
- `local.properties` in `AIDetectorApp/` with `sdk.dir=/path/to/Android/Sdk`

### Build a signed AAB
```bash
# 1. Generate an upload keystore (keep the .jks file safe — losing it bricks updates)
keytool -genkey -v -keystore upload-key.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias upload

# 2. Add to AIDetectorApp/androidApp/build.gradle.kts:
#    signingConfigs { create("release") { ... storeFile = file("upload-key.jks") } }
#    buildTypes.release.signingConfig = signingConfigs.getByName("release")

# 3. Build AAB
./gradlew :androidApp:bundleRelease

# Output: AIDetectorApp/androidApp/build/outputs/bundle/release/androidApp-release.aab
```

### Listing requirements
- App name, short (80 char) + full (4000 char) description
- Icon (512×512 PNG), feature graphic (1024×500), 2–8 screenshots (16:9 phone)
- Privacy policy URL — **required** because the app uses camera/photos. Easiest path: GitHub Pages with a one-page HTML privacy policy.
- Content rating questionnaire
- Data safety form: declare you process images on-device (or via the API if user enables it). Be honest here; Google checks.

### Submission flow
1. Play Console → **Create app**
2. Set up app → fill all sections (privacy policy, app access, ads, content rating, target audience, news, COVID, government, data safety)
3. Production → **Create new release** → upload AAB
4. Set release notes
5. Review → **Start rollout to production**

First review takes 1–3 days; subsequent updates are usually approved in hours.

---

## iOS → Apple App Store

### Prerequisites
- **Mac required** for code signing (rent one via MacStadium / GitHub Actions `macos-latest` / Codemagic if you don't own one)
- Apple Developer Program account ($99/yr): <https://developer.apple.com/programs>
- Xcode 15+
- App Store Connect account: <https://appstoreconnect.apple.com>

### Build & archive
```bash
cd AIDetectorApp/iosApp
./gradlew :shared:embedAndSignAppleFrameworkForXcode  # if needed
xcodebuild -workspace iosApp.xcworkspace -scheme iosApp \
           -configuration Release -archivePath build/iosApp.xcarchive archive
xcodebuild -exportArchive -archivePath build/iosApp.xcarchive \
           -exportPath build/ -exportOptionsPlist ExportOptions.plist
```

Or just use Xcode UI: **Product → Archive → Distribute App → App Store Connect → Upload**.

### Listing requirements
- App name, subtitle (30 char), description, keywords (100 char)
- Icon (1024×1024 PNG, no transparency, no rounded corners — Apple does that)
- Screenshots: 6.7" iPhone, 5.5" iPhone, plus iPad if supporting iPad
- Privacy policy URL (required)
- App privacy: declare camera/photos usage and what's collected
- Age rating questionnaire
- Export compliance: most apps qualify for the standard exemption (no proprietary crypto)

### Submission flow
1. App Store Connect → **My Apps → +**
2. Fill **App Information** (bundle ID must match Xcode project)
3. **Pricing and Availability**
4. **App Privacy** — fill out the entire questionnaire honestly
5. **iOS App** version → upload screenshots, description, build (the archive uploaded above)
6. **Submit for Review**

First review takes 1–3 days. Common rejection reasons for AI detector apps:
- Vague description ("uses AI" without specifics)
- Missing privacy policy
- Asking for camera access without a justified use case in `Info.plist`'s `NSCameraUsageDescription`

---

## App icon, name, copy

The user-facing product name is up to you, but a few practical hints:
- "AI Detector" alone is taken in both stores; pick a brandable name (`Veracite`, `RealLens`, `Authentik`, etc.)
- Description should set realistic expectations: "estimates likelihood that an image was AI-generated using statistical heuristics. Not a guarantee."
- Add a "limitations" section in your description — Apple in particular dings apps that overpromise

---

## CI/CD

The repo has `.github/workflows/docker-build.yml` (server image). For the mobile app you'll want:
- `.github/workflows/android-release.yml` — builds AAB on tag, uploads to Play via [r0adkll/upload-google-play](https://github.com/r0adkll/upload-google-play)
- `.github/workflows/ios-release.yml` — runs on `macos-latest`, uses [maierj/fastlane-action](https://github.com/maierj/fastlane-action) with App Store Connect API key

I can scaffold these in a follow-up if you want; they're not in this PR because they need secrets configured first (signing key, API keys).

---

## The model lives on the server

There is no on-device model to update. The detector is served by the Tayanch API (`deploy/modal_app.py` → `demo_api.py` in the main repo); the app only needs the API base URL and a key. The `UnifiedFusionNet` / TorchScript export plan described here earlier was the spring-2026 trainer, which is archived in the main repo (`docs/INDEX.md`).
