# Firestore security regression baseline

These tests describe the security boundary of the proposed partial Spark migration.
They exercise direct fund/donor directory writes as well as forbidden ledger writes.
The full Spark migration is **not** complete: the rules deny direct ledger writes,
so they cannot be deployed as a complete fix for the current `NOT_FOUND` error.
Rules and client must be reviewed and tested together before any rollout.

To run locally, install JDK 17+ and Firebase CLI and execute:

```sh
cd android/rules-tests
npm ci
firebase emulators:exec --project demo-mosque-rules --config ../firebase.json --only firestore 'npm test'
```

The demo project ID and emulator prevent access to a real Firebase project. The
suite clears emulator data before each test. Do not point these tests at production.

## Isolated ledger prototype (do not deploy)

`ledger-prototype.rules` is a **test-only** experiment, not the production rules
configured by `firebase.json`. It explores append-only contribution, expense and
transfer creates with integer-poisha balances and atomic Firestore invariants.
`ledger-prototype.test.mjs` exercises authorized writes, replay, tampering,
tenant isolation, overdraft and concurrent spending using the emulator.

The isolated `PrototypeLedgerWriter` exercises Android Firestore transactions,
exact retry payloads, integer arithmetic and missing-migration refusal against
these rules in a separately started Auth/Firestore emulator. It is **not wired
into the app's repository or UI**. A separate experimental SQLite outbox stores
immutable UID/mosque-scoped drafts and is excluded from backup, but it has no
worker or UI yet and does not sync. The prototype does not yet support edits,
deletes, historical-data migration or full rules-level
calendar-date validation.
The experiment uses new `balancePoisha`, `totalBalancePoisha` and
`totalDonatedPoisha` aggregate fields alongside the existing taka fields. The
current callable backend does not maintain these fields, so mixing the two would
corrupt the prototype's assumptions. Never deploy or point production traffic at
this file. A safe migration must freeze or upgrade all writers, back up and
reconcile historic data, initialize and verify integer fields, and test the
matching client, rules, concurrency, retries and rollout/rollback together.

## Spark migration acceptance criteria

Before replacing the callable backend, extend these emulator tests to cover both
successful tenant transactions and hostile direct Firestore requests. At minimum:

- A contribution, expense and transfer each update all associated balance and
  donor totals atomically; a failed write changes none of them.
- Concurrent edits/deletes cannot reapply a stale transaction; overdrafts,
  negative amounts, cross-tenant references, and fractional-poisha inputs fail.
- No role, including a cashier with money permissions, can write balances or
  cached totals independently of the corresponding ledger mutation.
- Fund and donor metadata changes cannot overwrite concurrent balances. Archive
  preserves transaction references and prevents new use of inactive records.
- Users cannot read another mosque's private profiles, assign SUPERADMIN, move
  users across tenants, or delete/deactivate their own account.
- Account provisioning is handled through a trustworthy administrative workflow;
  Firebase Authentication account creation and Firestore writes cannot be one
  atomic transaction. Document cleanup and recovery for partial failure.
- Existing data is backed up and reconciled, and the new rules are deployed only
  with a matching client after emulator and real-device tests pass.

Do not weaken the rules simply to get Spark writes working. Rules must enforce
accounting invariants against modified clients, not only rely on Android UI checks.
