# Firestore security regression baseline

These tests describe the security boundary of the proposed partial Spark migration.
They exercise direct fund/donor directory writes, denial of legacy ledger writes,
and atomic ledger v2 creates when an admin-only migration flag is set.
The full Spark migration is **not** complete and must not be deployed as a fix for
`NOT_FOUND`. New ledger entries are connected to v2; edits, deletes, provisioning
and a safe historical-data migration are not.
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

The `PrototypeLedgerWriter`, SQLite outbox and WorkManager worker are connected
to new-entry screens and tested against v2 rules in a separately started emulator.
The queue is scoped to the signed-in account and mosque, excludes pending values
from confirmed totals and uses insertion order within a tenant/account. A blocked
head stops subsequent operations until retry or discard; pending entries from all
ledger types are visible together. Recovery
is scheduled on startup and periodically. A process may still die before scheduling;
startup/periodic recovery covers that gap. Unsynced drafts are excluded from backup
and are lost on uninstall or data clear. Edits, deletes, account provisioning and
historical-data rollout are not yet complete for v2. An experimental, freeze-based
maintenance migration tool is emulator-tested separately; it requires an operator
to back up and stop all privileged writers and explicitly provide all openings. Real devices, actual
network loss and role changes still need validation.
The experiment uses new `balancePoisha`, `totalBalancePoisha` and
`totalDonatedPoisha` aggregate fields alongside the existing taka fields. The
legacy callable backend does not maintain these fields. The updated callable
refuses ledger writes after v2 is enabled, but an older deployed callable or other
privileged writer can still corrupt the assumptions unless removed or frozen. Never deploy or point production traffic at
this file. A safe migration must freeze or upgrade all writers, back up and
reconcile historic data, initialize and verify integer fields, and test the
matching client, rules, concurrency, retries and rollout/rollback together.

## Isolated correction prototype (do not deploy)

`ledger-corrections-prototype.rules` and `ledger-mutations.test.mjs` explore
immutable correction receipts, atomic aggregate deltas and soft deletion. They
are separate from `android/firestore.rules`, and are not yet integrated with the
app, reports, migration or offline queue. The design and emulator suite still
need review and must not be deployed. In particular, tombstones require filtering
from all financial reports and verifiable audit handling before activation.

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
