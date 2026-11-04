# মসজিদ হিসাব — Mosque Account Management System

Android (Kotlin/Compose) client with Firebase Authentication, Cloud Firestore and
Firebase callable Cloud Functions. The app supports Bangla and English, a platform
console, and tenant funds, contributions, expenses, donors, members, transfers and
monthly reports.

## Required Firebase setup

1. Create an isolated Firebase project and an Android app with package name
   `com.saif.mosque`. Put its downloaded `google-services.json` in `android/app/`
   (never commit it). Enable Email/Password sign-in and create a Firestore database.
2. Install the Firebase CLI, Node.js 20 and JDK 17. Functions deployment requires a
   Firebase project eligible for Cloud Functions (normally the Blaze billing plan).
   Select the intended Firebase project explicitly before deploying:
   ```sh
   cd android
   firebase use <your-project-id>
   cd functions && npm ci && npm test && cd ..
   firebase deploy --only functions,firestore:rules,firestore:indexes
   ```
   The callable function uses the default `us-central1` region; the Android SDK
   uses that same default. Deploying rules without the function and updated client
   will block writes. Old app versions cannot write with these rules: schedule a
   maintenance window, back up data, deploy the function and rules, and distribute
   the updated app together. Do not deploy the function alone with old rules still
   permitting direct writes and assume the ledger is protected.
3. Provision the first `SUPERADMIN` using a trusted Firebase Admin SDK or console:
   create an Auth account with a strong, unique password, and create
   `users/<auth-uid>` with `role: "SUPERADMIN"`, `active: true`, `mosqueId: null`,
   plus the name/email fields. Then use the platform console to create mosques.
   Account provisioning uses the Admin SDK and attempts to remove newly created
   Auth accounts if Firestore provisioning fails; check function logs for cleanup
   errors and reconcile any orphaned account manually.

### Demo data — isolated projects only

The demo seed creates a **publicly known superadmin password**, and resets existing
accounts with those demo emails. Never use it in a project containing real data or
accessible to untrusted users. It now requires an explicit acknowledgement:

```sh
cd android/scripts
npm install
node seed-demo.mjs --service-account /path/to/serviceAccountKey.json --unsafe-demo-only
```

Debug builds offer quick-fill demo credentials; release builds do not. Removing the
buttons does not remove accounts already seeded in Firebase. Delete or disable any
existing demo accounts in production immediately, and review their activity.

## Build and test

Open `android/` in Android Studio with Android SDK 35 and JDK 17, or use Gradle
8.10.2: `cd android && gradle assembleDebug`. A wrapper properties file is present,
but the wrapper script and jar are not committed. CI uses Gradle 8.10.2 and the
raw JSON in the `GOOGLE_SERVICES_JSON` repository secret to build a debug APK.

The shared components include Android Studio previews for compact phones, large text,
and tablets. The Android instrumentation suite includes local SQLite, responsive UI,
and end-to-end queue tests against local Firebase Auth/Firestore emulators. With an
Android emulator running, Node, JDK and Android SDK installed, and the app's Firebase
configuration available locally, run `bash android/scripts/run-sync-emulator-tests.sh`
from the repository root. It explicitly redirects the test clients to local emulators;
it does not write to the production Firebase project. Run `gradle testDebugUnitTest`
from `android/` for the local policy tests.
Also verify the full app manually at 320 dp and tablet widths, with increased font
size, the keyboard open, both languages, and real Firebase data.

Server unit tests: `cd android/functions && npm ci && npm test`. The in-memory
transaction tests cover arithmetic and some authorization cases, but **do not
replace Firestore Emulator rules/integration tests** or device testing.

## Spark plan status

Fund/donor directory operations and existing-member edits/deletions have a proposed
Firestore implementation and matching rules. CI runs the Firestore Emulator security tests, but the
changes have not been tested on an Android device and are not deployed.
Ledger operations and new-account provisioning still require the deployed callable
Cloud Function, so the app is **not yet compatible with Spark-only production deployment**.
A safe migration
requires client transaction changes, matching Firestore rules, and provisioning
changes. Offline-first ledger creation/editing and automatic ledger synchronization
also remain unimplemented. The emulator security baseline and migration acceptance criteria are in
[`android/rules-tests/README.md`](android/rules-tests/README.md). An isolated,
append-only integer-poisha ledger rules prototype is being tested there; it is not
used by the app and must not be deployed. Do not switch a production project to
permissive rules as a workaround.

## Read-only ledger audit

Before any accounting migration, take a secure backup and obtain a complete,
consistent tenant snapshot with your approved export process. The optional local
audit below compares balances, donor totals and counters using integer poisha.
It makes no Firebase calls, does not change or repair data, and is not a backup.
Keep exports containing personal data out of the repository and protect them.

```sh
node android/scripts/audit-ledger.mjs /path/to/tenant-snapshot.json
```

The JSON must contain `mosque` (including its `id` and totals/counters), and the
arrays `funds`, `donors`, `contributions`, `expenses` and `transfers`. Each record
must include its document `id` and `mosqueId`; include all archived records and
all historical ledger rows. An optional `members` array enables member-count
checking. Optional `openingFunds` and `openingDonors` objects map document IDs to
opening values in taka. Example empty snapshot:

```json
{"mosque":{"id":"example","totalBalance":0,"fundCount":0,"donorCount":0,"memberCount":0},
 "funds":[],"donors":[],"contributions":[],"expenses":[],"transfers":[],"members":[],
 "openingFunds":{},"openingDonors":{}}
```

Omitted opening amounts are assumed zero; a clean result is meaningful only if
those assumptions and the completeness/consistency of the snapshot are correct.
Historical deletions or concurrent writes during an export can make the result
misleading. Exit status is 0 for no issues, 1 for reported issues/incomplete data,
and 2 for invalid input. Investigate discrepancies manually; never automatically
replace production balances from this report.

## Security and accounting behavior

- Firestore rules permit users to read their own profile and active tenant data;
  tenant queries must filter by `mosqueId`. Direct ledger writes and user profile
  creation are denied. Existing-member edits/deletions are restricted by role,
  tenant and self-protection rules; deletion requires an atomic counter decrement. Fund/donor metadata changes are constrained by role;
  creation uses a paired counter update, and financial fields cannot be changed
  by these directory rules. The Android client uses an online transaction to reject
  stale fund, donor and member metadata edits without overwriting balance changes.
  Platform admins may edit only mosque metadata/status.
- Callable functions re-check active user, role and mosque status, perform ledger
  mutations in Firestore transactions, use persisted records instead of trusting
  old client data, reject stale edits and insufficient balances, and maintain donor
  totals. Arithmetic uses integer poisha; existing Firestore numeric amount and
  balance fields remain in taka for compatibility. Amounts must be positive and
  have at most two decimal places.
- Fund and donor delete actions **archive** rather than erase records, preserving
  references and balances; archived items cannot be selected for new transactions.
  Their `active` switch can restore them. Member profile deletion does not delete
  the Firebase Auth account; an administrator must manage that separately.
- If a callable write times out, check the ledger before retrying: a timeout can
  mean the server committed the operation but the response was lost. Request-level
  idempotency and audit logging are not yet implemented.
- Callable writes require an internet connection and successful Functions deployment.
  A previously server-verified Firebase session can open cached data while offline,
  only when the cached profile matches the current Firebase UID.
  Cached data may be incomplete or stale, and a remote revocation cannot be known
  until connectivity returns. The app revalidates the session when online.
  Fund and donor creation, metadata edits and archive actions can be queued offline;
  all other offline edits remain disabled. The queue stores drafts in a local SQLite database,
  scopes them by Firebase UID and mosque, and uses WorkManager to retry when a
  connection returns. Server-side role and mosque status are rechecked, and an
  immutable operation receipts prevent retries from reapplying a creation or edit.
  Queued edits compare their original metadata with the latest server version;
  conflicts remain visible and must be discarded locally and re-entered from the
  latest record. Pending or unconfirmed items appear separately and do not count
  as online data. Receipts are retained indefinitely and consume Firestore quota;
  do not delete them while any old client could retry the corresponding operation.
  The queue is excluded from backup; clearing app data or uninstalling loses
  unsynced drafts. A different account cannot sync them. If the account is fully
  deactivated and signed out, its drafts cannot currently be viewed until that
  account can authenticate again; export/recovery is not implemented. Android and
  Firebase emulator tests cover offline retry, acknowledgement recovery, concurrent
  adds, account isolation and inactive users. Real devices and real-world connectivity
  have not yet been validated. Role/active changes are observed online.
- **Existing data is not automatically repaired.** Before enabling writes, back up
  and reconcile each fund balance, mosque total, donor totals and counters against
  the historical records. Prior bugs may have caused discrepancies that cannot be
  safely inferred or fixed automatically. Test the reconciliation in a staging
  project and involve the responsible accountant.
- Local login lockout is only a UI deterrent, not a security boundary: a modified
  client or another device can bypass it. Use Firebase/Identity Platform's server
  protections, monitoring and appropriate abuse controls; do not claim this is a
  guaranteed account-level lockout. App Check, audit logging, alerting, backup and
  restore drills, emulator security tests and a signed release pipeline are still
  necessary before production use.
