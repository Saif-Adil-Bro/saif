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
