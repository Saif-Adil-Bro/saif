# Mosque Hisab — Production Readiness, Migration & Operations Runbook

This runbook documents the complete deployment, financial reconciliation, backup/restore, security, and release procedures for **Mosque Hisab**.

---

## 1. Deployment Architecture: Spark vs. Blaze Support

The codebase and [`android/firestore.rules`](android/firestore.rules) support **both** Firebase Spark (no Cloud Functions) and Firebase Blaze (with [`android/functions/index.js`](android/functions/index.js)):

| Capability | Spark Plan (Cloud Functions-Free) | Blaze Plan (Trusted Backend + Rules) |
|---|---|---|
| **New Mosque & First Admin Provisioning** | [`PlatformRepository.createMosque`](android/app/src/main/java/com/saif/mosque/data/repository/PlatformRepository.kt) creates the admin Auth account via an isolated secondary `FirebaseApp` and commits an atomic batch (`mosques/{id}` + `users/{adminUid}`) verified by `mosqueCreate` + `platformAdminCreate` in [`firestore.rules`](android/firestore.rules), followed by starter funds. | Calls the `mutate` HTTPS callable (`createMosque`) first; automatically falls back to the Spark atomic flow if `NOT_FOUND`. |
| **Member Onboarding** | Self-service invitation issuance (`mosques/{id}/invitations/{inviteId}`) in [`MembersScreen.kt`](android/app/src/main/java/com/saif/mosque/ui/screens/MembersScreen.kt) + verified-email redemption (`memberClaims/{email}` + `users/{uid}`) in [`LoginScreen.kt`](android/app/src/main/java/com/saif/mosque/ui/screens/LoginScreen.kt) via [`PrototypeJoinCoordinator`](android/app/src/main/java/com/saif/mosque/data/invitations/PrototypeJoinCoordinator.kt). Also supports direct password-based creation via secondary `FirebaseApp`. | Same invitation flow + optional `createMember` callable in [`functions/index.js`](android/functions/index.js). |
| **Ledger Entries & Corrections (`ledgerVersion == 2`)** | Atomic client batches enforced by reciprocal poisha rules in [`firestore.rules`](android/firestore.rules) (`ledgerCreate`, `ledgerMutations`, `ledgerCorrectionUpdate`, `ledgerVoidTombstone`) with durable SQLite outboxes ([`PrototypeLedgerOutbox`](android/app/src/main/java/com/saif/mosque/data/ledger/PrototypeLedgerOutbox.kt) and [`PrototypeCorrectionOutbox`](android/app/src/main/java/com/saif/mosque/data/ledger/PrototypeCorrectionOutbox.kt)). | Compatible with `ledgerVersion == 2` rules and server-side audit scripts. |

---

## 2. Financial Data Safety, Accountant Reconciliation & Migration

> **CRITICAL RULE**: Never manually edit `ledgerVersion` in the Firebase Console or publish rules without migrating and reconciling balances first.

### Step 2.1 — Full Mosque Backup & Cryptographic Verification
Before any migration or rule deployment, export a complete JSON snapshot of the mosque and verify its SHA-256 integrity and receipt chain using [`android/scripts/backup-restore-mosque.mjs`](android/scripts/backup-restore-mosque.mjs):

```bash
# Export mosque backup (mosque doc, funds, donors, contributions, expenses, transfers, ledgerMutations, invitations, memberClaims, users)
node android/scripts/backup-restore-mosque.mjs export \
  --project <FIREBASE_PROJECT_ID> \
  --mosque <MOSQUE_ID> \
  --out backups/mosque-<MOSQUE_ID>-$(date +%F).json

# Verify SHA-256 digest, poisha/taka invariants, tombstones, and ledgerMutations receipt chain
node android/scripts/backup-restore-mosque.mjs verify \
  --in backups/mosque-<MOSQUE_ID>-$(date +%F).json
```

### Step 2.2 — Accountant Reconciliation Worksheet
Generate a printable JSON worksheet comparing stored fund/donor balances against the exact sum of active ledger entries (and computing any implied opening balances) for sign-off by the mosque's responsible accountant/treasurer:

```bash
node android/scripts/backup-restore-mosque.mjs reconcile \
  --in backups/mosque-<MOSQUE_ID>-$(date +%F).json \
  --out backups/reconciliation-<MOSQUE_ID>-$(date +%F).json
```

Review `funds`, `donors`, `impliedOpeningBalancePoisha`, and `receiptChainVerification` with the accountant before proceeding.

### Step 2.3 — Staging Dry-Run & Legacy Writer Freeze
1. **Freeze legacy writers**: Ensure all committee devices are updated to the new app build and disable any legacy external scripts before migrating.
2. **Test in Staging first**: Restore the production backup into the **staging** Firebase project and run the migration dry-run and apply:
   ```bash
   node android/scripts/backup-restore-mosque.mjs restore \
     --project <STAGING_PROJECT_ID> \
     --in backups/mosque-<MOSQUE_ID>-$(date +%F).json \
     --confirm-restore

   node android/scripts/audit-ledger.mjs --project <STAGING_PROJECT_ID> --mosque <MOSQUE_ID>
   node android/scripts/migrate-ledger.mjs --project <STAGING_PROJECT_ID> --mosque <MOSQUE_ID> --apply
   ```

### Step 2.4 — Atomic Production Rollout & Rollback Plan
1. **Rollout**:
   - Take a fresh verified backup (`backup-restore-mosque.mjs export` + `verify`).
   - Run `node android/scripts/migrate-ledger.mjs --project <PROD_PROJECT_ID> --mosque <MOSQUE_ID> --apply` to backfill integer `*Poisha` fields and set `ledgerVersion = 2`.
   - Deploy [`android/firestore.rules`](android/firestore.rules) (and [`android/functions`](android/functions) if on Blaze):
     ```bash
     firebase deploy --project <PROD_PROJECT_ID> --only firestore:rules
     ```
   - Distribute the matching signed release APK/AAB.
2. **Rollback**:
   - If any discrepancy is detected during rollout, immediately restore the verified pre-migration snapshot:
     ```bash
     node android/scripts/backup-restore-mosque.mjs restore \
       --project <PROD_PROJECT_ID> \
       --in backups/mosque-<MOSQUE_ID>-$(date +%F).json \
       --confirm-restore
     ```

---

## 3. Audit Trail, Offline Corrections & Member Invitations

- **Ledger Corrections & Voiding (`ledgerMutations`)**:
  - Every edit or void of a contribution, expense, or transfer writes an immutable receipt under `/mosques/{id}/ledgerMutations/{opId}` with `before`, `after`, `oldAmountPoisha`, and `newAmountPoisha`.
  - Voided entries are converted into zero-amount tombstones (`voided: true`, `voidedOpId: opId`) rather than hard-deleted, preserving a verifiable receipt chain.
  - Offline edits/voids are queued in [`PrototypeCorrectionOutbox`](android/app/src/main/java/com/saif/mosque/data/ledger/PrototypeCorrectionOutbox.kt) and synced via [`PrototypeCorrectionSync`](android/app/src/main/java/com/saif/mosque/data/ledger/PrototypeCorrectionSync.kt).
  - Committee members can inspect all mutation receipts and run live receipt-chain verification from **More → Audit trail & receipt verification** ([`MoreScreen.kt`](android/app/src/main/java/com/saif/mosque/ui/screens/MoreScreen.kt)).
- **Member Invitations**:
  - Admins/Presidents/Secretaries can issue invitations from **Members → Invite member** ([`MembersScreen.kt`](android/app/src/main/java/com/saif/mosque/ui/screens/MembersScreen.kt)).
  - Invited members redeem codes from **Login → Join with invitation** ([`LoginScreen.kt`](android/app/src/main/java/com/saif/mosque/ui/screens/LoginScreen.kt)), which handles new or existing accounts, email verification, revoked/expired invites, and partial signup recovery.

---

## 4. Security, Isolation & Real-World Verification

- **Tenant & Role Isolation**: Enforced at the Firestore rules layer ([`android/firestore.rules`](android/firestore.rules)) and tested in [`android/rules-tests/`](android/rules-tests/) across `rules.test.mjs`, `ledger-prototype.test.mjs`, `ledger-mutations.test.mjs`, and `member-invite-prototype.test.mjs`.
- **Abuse & Lockout Protection**: Client login lockout (`SessionManager`) + strict field-type, length, and reciprocal counter checks in `firestore.rules`. Enable **Firebase App Check** (Play Integrity provider) in the Firebase Console for production builds.

---

## 5. Release Pipeline, Staging Separation & Operations

### Staging vs. Production Firebase Separation
- Keep two separate Firebase projects: `mosque-hisab-staging` and `mosque-hisab-prod`.
- Never share `google-services.json` between staging and production builds.

### Building a Signed Release APK / AAB
[`android/app/build.gradle.kts`](android/app/build.gradle.kts) reads release signing credentials and version metadata from environment variables or Gradle properties:

```bash
cd android
KEYSTORE_FILE=/path/to/release.keystore \
KEYSTORE_PASSWORD="<store-password>" \
KEY_ALIAS="<key-alias>" \
KEY_PASSWORD="<key-password>" \
VERSION_CODE=2 \
VERSION_NAME="1.1.0" \
./gradlew assembleRelease bundleRelease
```

### Backup Schedule, Restore Drill & Monitoring
1. **Weekly Automated/Scheduled Backup**: Run `backup-restore-mosque.mjs export` and `verify` weekly (and before every release), storing encrypted snapshots off-site.
2. **Quarterly Restore Drill**: Every quarter, restore the latest production backup into `mosque-hisab-staging` using `backup-restore-mosque.mjs restore --confirm-restore` and run `backup-restore-mosque.mjs verify` + `reconcile` to prove recovery readiness.
3. **Quota & Alerting**: Configure Firebase Billing / Usage alerts at 50%, 80%, and 100% of daily Firestore document read/write quotas and Authentication email limits.
