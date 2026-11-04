// Run against the local Firestore emulator only; see README.md.
// These tests define the security boundary to preserve during a Spark migration.
import { before, beforeEach, after, test } from 'node:test';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc, collection, getDocs, query, where, writeBatch, increment } from 'firebase/firestore';

let env;
before(async () => {
  if (!process.env.FIRESTORE_EMULATOR_HOST) {
    throw new Error('Run these tests with firebase emulators:exec --only firestore');
  }
  env = await initializeTestEnvironment({
    projectId: 'demo-mosque-rules',
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
  });
});
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async context => {
    const db = context.firestore();
    await Promise.all([
      setDoc(doc(db, 'users/admin-a'), { role: 'ADMIN', mosqueId: 'a', active: true }),
      setDoc(doc(db, 'users/cashier-a'), { role: 'CASHIER', mosqueId: 'a', active: true }),
      setDoc(doc(db, 'users/disabled-a'), { role: 'ADMIN', mosqueId: 'a', active: false }),
      setDoc(doc(db, 'users/member-a'), { role: 'MEMBER', mosqueId: 'a', active: true }),
      setDoc(doc(db, 'users/admin-b'), { role: 'ADMIN', mosqueId: 'b', active: true }),
      setDoc(doc(db, 'users/platform'), { role: 'SUPERADMIN', mosqueId: null, active: true }),
      setDoc(doc(db, 'mosques/a'), { name: 'A', active: true, totalBalance: 100, fundCount: 1, donorCount: 0 }),
      setDoc(doc(db, 'mosques/b'), { name: 'B', active: true, totalBalance: 200, fundCount: 1, donorCount: 0 }),
      setDoc(doc(db, 'mosques/a/funds/general'), { name: 'General', code: 'GEN', active: true, balance: 100, mosqueId: 'a' }),
      setDoc(doc(db, 'mosques/b/funds/general'), { name: 'General', balance: 200, mosqueId: 'b' }),
    ]);
  });
});
after(async () => { if (env) await env.cleanup(); });

const dbFor = uid => env.authenticatedContext(uid).firestore();

test('active members can read their mosque but not another tenant', async () => {
  const db = dbFor('member-a');
  await assertSucceeds(getDoc(doc(db, 'mosques/a/funds/general')));
  await assertFails(getDoc(doc(db, 'mosques/b/funds/general')));
});

test('member queries must be scoped to the caller tenant', async () => {
  const db = dbFor('admin-a');
  await assertSucceeds(getDocs(query(collection(db, 'users'), where('mosqueId', '==', 'a'))));
  await assertFails(getDocs(query(collection(db, 'users'), where('mosqueId', '==', 'b'))));
  await assertFails(getDocs(collection(db, 'users')));
});

test('tenant admin cannot escalate themselves or modify other tenants', async () => {
  const db = dbFor('admin-a');
  await assertFails(updateDoc(doc(db, 'users/admin-a'), { role: 'SUPERADMIN', mosqueId: null }));
  await assertFails(updateDoc(doc(db, 'users/admin-b'), { role: 'MEMBER' }));
});

test('tenant admin cannot directly tamper with balances or ledger', async () => {
  const db = dbFor('admin-a');
  await assertFails(updateDoc(doc(db, 'mosques/a/funds/general'), { balance: 1000000 }));
  await assertFails(updateDoc(doc(db, 'mosques/a'), { totalBalance: 1000000 }));
  await assertFails(setDoc(doc(db, 'mosques/a/contributions/fake'), {
    mosqueId: 'a', accountId: 'general', amount: 1000000, date: '2026-01-01',
  }));
});

test('platform admin cannot overwrite cached balances through metadata updates', async () => {
  const db = dbFor('platform');
  await assertSucceeds(updateDoc(doc(db, 'mosques/a'), { name: 'Updated' }));
  await assertFails(updateDoc(doc(db, 'mosques/a'), { totalBalance: 1000000 }));
});

function createDirectory(db, kind, id, value, mosque = 'a') {
  const b = writeBatch(db);
  b.set(doc(db, `mosques/${mosque}/${kind}/${id}`), value);
  b.update(doc(db, `mosques/${mosque}`), {
    [kind === 'funds' ? 'fundCount' : 'donorCount']: increment(1),
    directoryOpId: id, directoryOpKind: kind,
  });
  return b.commit();
}
const fund = (mosqueId = 'a') => ({ mosqueId, name: 'Savings', code: 'SAV', nameEn: '', description: '', active: true, balance: 0 });
const donor = (mosqueId = 'a') => ({ mosqueId, name: 'Donor', phone: '', address: '', active: true, totalDonated: 0, createdAt: 1 });

test('directory creation pairs the new document and exactly one counter increment', async () => {
  const db = dbFor('admin-a');
  await assertSucceeds(createDirectory(db, 'funds', 'newfund', fund()));
  await assertSucceeds(createDirectory(db, 'donors', 'newdonor', donor()));
  const mosque = (await getDoc(doc(db, 'mosques/a'))).data();
  if (mosque.fundCount !== 2 || mosque.donorCount !== 1) throw new Error('Incorrect counts');
  await assertFails(setDoc(doc(db, 'mosques/a/funds/unpaired'), fund()));
  await assertFails(updateDoc(doc(db, 'mosques/a'), { fundCount: increment(1), directoryOpId: 'missing', directoryOpKind: 'funds' }));
  await assertFails(createDirectory(db, 'funds', 'nonzero', { ...fund(), balance: 1 }));
  await assertFails(createDirectory(db, 'donors', 'nonzero', { ...donor(), totalDonated: 1 }));
});

test('roles, active status and tenant scope are enforced for directory writes', async () => {
  await assertFails(createDirectory(dbFor('member-a'), 'donors', 'x', donor()));
  await assertFails(createDirectory(dbFor('disabled-a'), 'funds', 'x', fund()));
  await assertFails(createDirectory(dbFor('admin-b'), 'funds', 'x', fund()));
  await assertFails(createDirectory(dbFor('cashier-a'), 'funds', 'x', fund()));
  await assertSucceeds(createDirectory(dbFor('cashier-a'), 'donors', 'x', donor()));
  await assertFails(updateDoc(doc(dbFor('cashier-a'), 'mosques/a/donors/x'), { active: false }));
  await assertSucceeds(updateDoc(doc(dbFor('admin-a'), 'mosques/a/donors/x'), { active: false }));
});

test('directory edits cannot tamper with financial fields or tenant ownership', async () => {
  const db = dbFor('admin-a');
  const ref = doc(db, 'mosques/a/funds/general');
  await assertSucceeds(updateDoc(ref, { name: 'Renamed' }));
  await assertFails(updateDoc(ref, { balance: 10000 }));
  await assertFails(updateDoc(ref, { mosqueId: 'b' }));
  await assertFails(updateDoc(ref, { name: '', balance: 0 }));
  await assertSucceeds(createDirectory(db, 'donors', 'd', donor()));
  await assertFails(updateDoc(doc(db, 'mosques/a/donors/d'), { totalDonated: 100 }));
});

test('cannot create two documents for a single counter increment', async () => {
  const db = dbFor('admin-a');
  const b = writeBatch(db);
  b.set(doc(db, 'mosques/a/funds/x'), fund());
  b.set(doc(db, 'mosques/a/funds/y'), fund());
  b.update(doc(db, 'mosques/a'), { fundCount: increment(1), directoryOpId: 'x', directoryOpKind: 'funds' });
  await assertFails(b.commit());
});

test('failed or repeated creation does not alter counters or leave a record', async () => {
  const db = dbFor('admin-a');
  await assertFails(createDirectory(db, 'funds', 'bad', { ...fund(), balance: 500 }));
  if ((await getDoc(doc(db, 'mosques/a/funds/bad'))).exists()) throw new Error('Partial write');
  if ((await getDoc(doc(db, 'mosques/a'))).data().fundCount !== 1) throw new Error('Counter changed on failure');
  await assertSucceeds(createDirectory(db, 'funds', 'same', fund()));
  await assertFails(createDirectory(db, 'funds', 'same', fund()));
  if ((await getDoc(doc(db, 'mosques/a'))).data().fundCount !== 2) throw new Error('Duplicate counter increment');
});

test('concurrent directory additions each increment the correct counter once', async () => {
  const admin = dbFor('admin-a'), cashier = dbFor('cashier-a');
  await Promise.all([
    assertSucceeds(createDirectory(admin, 'funds', 'f1', fund())),
    assertSucceeds(createDirectory(admin, 'funds', 'f2', fund())),
    assertSucceeds(createDirectory(admin, 'donors', 'd1', donor())),
    assertSucceeds(createDirectory(cashier, 'donors', 'd2', donor())),
  ]);
  const mosque = (await getDoc(doc(admin, 'mosques/a'))).data();
  if (mosque.fundCount !== 3 || mosque.donorCount !== 2) throw new Error('Incorrect counts after concurrent creation');
});

test('revoking a role or deactivating a mosque immediately prevents subsequent writes', async () => {
  const db = dbFor('admin-a');
  await env.withSecurityRulesDisabled(async context => {
    await updateDoc(doc(context.firestore(), 'users/admin-a'), { role: 'MEMBER' });
  });
  await assertFails(createDirectory(db, 'funds', 'role-revoked', fund()));
  await env.withSecurityRulesDisabled(async context => {
    await updateDoc(doc(context.firestore(), 'users/admin-a'), { role: 'ADMIN' });
    await updateDoc(doc(context.firestore(), 'mosques/a'), { active: false });
  });
  await assertFails(createDirectory(db, 'donors', 'tenant-inactive', donor()));
});
