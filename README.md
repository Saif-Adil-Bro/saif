# মসজিদ হিসাব — Mosque Account Management System (Android)

মসজিদ হিসাব হলো একট মোবাইল জিনিস. সব মডুল অছে: পল্যটফরম কোনসোল, দাশবোরদ, ফানড, চানদা, ব্যায়, দাতা, সদসয, স্থানানতার, রিপোরট. UI পুরণ বাংলা তে.

---

Android client for the mosque accounting platform, built with **Kotlin + Jetpack Compose**
and backed by **Firebase Authentication + Cloud Firestore** (no separate REST server needed).
The UI is fully localized in **Bangla (values-bn)** with English fallback (values).

The app implements the full feature specification (roles & permission matrix, platform
console, dashboard, funds, contributions, expenses, donors, committee members, fund
transfers, monthly reports, login lockout, one-time credentials, etc.).

## Project layout

```
android/                  ← open this folder in Android Studio (or run gradle from here)
  settings.gradle.kts
  build.gradle.kts
  firebase.json           ← Firestore rules deployment config
  firestore.rules         ← security rules (multi-tenant, role matrix, self-guards)
  firestore.indexes.json
  app/
    google-services.json  ← NOT committed — download from Firebase console
    build.gradle.kts
    src/main/
      AndroidManifest.xml
      java/com/saif/mosque/
        MosqueApp.kt, MainActivity.kt
        di/                 AppContainer (FirebaseAuth main+secondary, Firestore, repos)
        data/model/         Firestore-mapped models
        data/repository/    AuthRepository, PlatformRepository, LedgerRepository, DashboardRepository
        data/SessionManager.kt  DataStore: cached profile + login lockout
        ui/                 theme, components, navigation, screens, viewmodels
      res/                  values (en), values-bn (bangla), drawables, xml
```

## Firebase setup (one time)

1. Create a project at https://console.firebase.google.com → add an **Android app**
   with package name `com.saif.mosque` (same for debug and release — no suffix is used).
2. Download `google-services.json` and place it at `android/app/google-services.json`
   (git-ignored). Enable **Email/Password** sign-in in Authentication.
3. Create a Cloud Firestore database (any region) and deploy rules + indexes:
   ```
   cd android
   firebase deploy --only firestore:rules,firestore:indexes   # requires Firebase CLI
   ```
   (or paste `firestore.rules` into the console).
   Note: `firestore.indexes.json` is intentionally **empty** — every query in the app
   uses a single field (`date`, `name`, `mosqueId`), and Firestore auto-creates
   single-field indexes, so deploying them returns HTTP 400 "index is not necessary".
   Add an entry here only when a composite (multi-field) index is needed.
4. Seed the demo accounts + demo mosque automatically (recommended, idempotent):
   ```
   cd android/scripts
   npm install
   node seed-demo.mjs --service-account /path/to/serviceAccountKey.json
   ```
   The service-account key comes from Firebase Console → Project settings →
   Service accounts → "Generate new private key" (**never commit it** — it is git-ignored).
   The script creates Auth users `superadmin@demo.local` and `admin@demo.local`
   (both password `demo12345`), their `users/{uid}` profiles
   (`role: "SUPERADMIN"` with `mosqueId: null`, and `role: "ADMIN"` with
   `mosqueId: "demo-mosque-0001"`), the `mosques/demo-mosque-0001` doc (active: true)
   and its 5 starter funds (GEN, CHD, CON, WAK, UTL).
   Manual alternative: create the two Auth users in the console, then create the
   `users/{uid}` docs and the mosque + funds docs by hand as described above.
5. Now the demo login buttons in the app work:
   `superadmin@demo.local / demo12345` → platform console,
   `admin@demo.local / demo12345` → mosque dashboard.

## Build & run

```
cd android
./gradlew assembleDebug        # or open in Android Studio → Run
```

Requires JDK 17 and Android SDK (compileSdk 35, minSdk 26). First build downloads
Gradle 8.10.2 + dependencies.

## Architecture

- **MVVM**: Compose screens + ViewModels (StateFlow) + repositories exposing Firestore
  `snapshotFlow()` streams (realtime + built-in offline persistence).
- **Auth**: Firebase Auth email/password. Login lockout (5 fails → 15 min) is enforced
  client-side via DataStore; for production also add a Cloud Function / App Check.
- **Multi-tenant**: all mosque data under `/mosques/{mosqueId}/...`; `firestore.rules`
  enforce the spec's role matrix, inactive-mosque blocks, and the self-delete /
  self-deactivate guards (400-equivalents).
- **Atomic money writes**: contributions / expenses / transfers run in Firestore
  transactions that update fund balances and the mosque's cached stats
  (`totalBalance`, `fundCount`, `donorCount`, `memberCount`) — used by the platform summary.
- **Provisioning**: creating a mosque writes mosque + 5 starter funds + admin profile in
  one transaction; the admin Auth account is created through a *secondary* Firebase app
  so the superadmin session is not disturbed. Credentials are shown once in a modal
  with copy buttons (spec 1.9); blank password → secure random temp password (spec 1.8).
- **Creating committee members**: same secondary-app pattern (spec module members).

## Spec → code mapping

| Spec | Where |
|------|-------|
| 0 auth, lockout, demo quick-login | `AuthRepository`, `SessionManager`, `LoginScreen`, `Config` |
| 1 platform console, provisioning, one-time credentials | `PlatformRepository`, `PlatformScreen`, `firestore.rules` |
| 2 roles & permission matrix, self-guards | `util/Permissions.kt`, `firestore.rules`, `MembersScreen` |
| funds / contributions / expenses / transfers | `LedgerRepository` + screens |
| donors | `LedgerRepository` + `DonorsScreen` |
| dashboard & monthly report | `DashboardRepository`, `DashboardScreen`, `ReportsScreen` |
| 9 Bangla localization | `res/values-bn/`, `util/Format.kt` (Bengali digits, ৳, dates) |

## Production hardening notes

- Deploy lockout + audit logging via Cloud Functions (Admin SDK), enable App Check.
- Enable Firestore PII rules / backup schedule; keep `google-services.json` out of git.
- For large platforms, move aggregations (platform summary) to Cloud Functions.

## CI — GitHub Actions APK build (no google-services.json in git)

The workflow `.github/workflows/build-apk.yml` builds the debug APK on every
push to `main` / `arena/**`, on PRs, and via **Run workflow** (workflow_dispatch),
then uploads it as an artifact (`mosque-hisab-debug-apk`) you download from the
Actions run page.

`google-services.json` is never committed (it is in `.gitignore`). Instead it is
provided at build time from a **repository secret**:

1. Open your downloaded `google-services.json` and copy its **entire raw JSON content**.
2. GitHub repo → **Settings → Secrets and variables → Actions → New repository secret**
   - Name: `GOOGLE_SERVICES_JSON`
   - Value: the raw JSON content (no base64 needed — GitHub secrets hold multiline text fine)
3. The workflow writes it to `android/app/google-services.json` before building and
   validates it with `json.load`.
   Note: secrets are NOT available to PRs from forks — the build will fail there
   with a clear error message (expected behavior).

For a **signed release APK**, add three more secrets (`KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_PASSWORD`), wire them into a `signingConfig` in
`app/build.gradle.kts`, and uncomment the `build-release-apk` job in the workflow.

### What goes in google-services.json

You don't type it manually — it is generated by Firebase:

- Create a Firebase project with any display name, e.g. **"Mosque Hisab"**.
  That name becomes `project_name`, and the auto-generated id (e.g.
  `mosque-hisab-abc123`) becomes `project_id` in the JSON.
- Add an **Android app** in that project with package name **`com.saif.mosque`**
  (this must equal `applicationId`; the debug suffix was removed so one entry
  covers both build types).
- Download `google-services.json` → place at `android/app/google-services.json`
  locally, and store its base64 in the secret above for CI.

### Local gradlew

Only `gradle/wrapper/gradle-wrapper.properties` is committed (the wrapper jar is
binary). To get `./gradlew` locally, run once: `cd android && gradle wrapper`
(or just use a locally installed `gradle` 8.10.2+).
