// Run against the local Firestore emulator only; see README.md.
// These tests define the security boundary to preserve during a Spark migration.
import { before, beforeEach, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc, collection, getDocs, query, where, writeBatch, increment, runTransaction } from 'firebase/firestore';

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
      setDoc(doc(db, 'users/admin-a'), { name: 'Admin', phone: '', designation: '', role: 'ADMIN', mosqueId: 'a', active: true }),
      setDoc(doc(db, 'users/cashier-a'), { role: 'CASHIER', mosqueId: 'a', active: true }),
      setDoc(doc(db, 'users/disabled-a'), { role: 'ADMIN', mosqueId: 'a', active: false }),
      setDoc(doc(db, 'users/member-a'), { name: 'Member', phone: '', designation: '', role: 'MEMBER', mosqueId: 'a', active: true }),
      setDoc(doc(db, 'users/secretary-a'), { name: 'Secretary', phone: '', designation: '', role: 'SECRETARY', mosqueId: 'a', active: true }),
      setDoc(doc(db, 'users/admin2-a'), { name: 'Second Admin', phone: '', designation: '', role: 'ADMIN', mosqueId: 'a', active: true }),
      setDoc(doc(db, 'users/admin-b'), { role: 'ADMIN', mosqueId: 'b', active: true }),
      setDoc(doc(db, 'users/platform'), { role: 'SUPERADMIN', mosqueId: null, active: true }),
      setDoc(doc(db, 'mosques/a'), { name: 'A', active: true, totalBalance: 100, fundCount: 1, donorCount: 0, memberCount: 6 }),
      setDoc(doc(db, 'mosques/b'), { name: 'B', active: true, totalBalance: 200, fundCount: 1, donorCount: 0, memberCount: 1 }),
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

function deleteMember(db, id, mosque = 'a') {
  const b = writeBatch(db);
  b.delete(doc(db, `users/${id}`));
  b.update(doc(db, `mosques/${mosque}`), { memberCount: increment(-1), memberOpId: id });
  return b.commit();
}

test('authorized staff can update a member, but cannot alter identity or tenant', async () => {
  const secretary = dbFor('secretary-a'), member = doc(secretary, 'users/member-a');
  await assertSucceeds(updateDoc(member, { name: 'Changed', role: 'TREASURER', active: true }));
  await assertFails(updateDoc(member, { mosqueId: 'b' }));
  await assertFails(updateDoc(member, { email: 'forged@example.com' }));
  await assertFails(updateDoc(member, { role: 'SUPERADMIN' }));
  await assertFails(updateDoc(member, { role: 'ADMIN' }));
  await assertFails(updateDoc(doc(secretary, 'users/admin2-a'), { role: 'MEMBER' }));
  await assertFails(updateDoc(doc(dbFor('admin-b'), 'users/member-a'), { name: 'Cross tenant' }));
  await assertFails(updateDoc(doc(dbFor('member-a'), 'users/member-a'), { name: 'Self' }));
});

test('admin can promote members but cannot deactivate or change their own role', async () => {
  const db = dbFor('admin-a');
  await assertSucceeds(updateDoc(doc(db, 'users/member-a'), { role: 'ADMIN' }));
  await assertFails(updateDoc(doc(db, 'users/admin-a'), { role: 'MEMBER' }));
  await assertFails(updateDoc(doc(db, 'users/admin-a'), { active: false }));
});

test('member deletion and counter decrement must be paired and respect permissions', async () => {
  const admin = dbFor('admin-a'), secretary = dbFor('secretary-a');
  await assertFails(deleteMember(admin, 'admin-a'));
  await assertFails(deleteMember(secretary, 'admin2-a'));
  await assertFails(deleteMember(dbFor('admin-b'), 'member-a'));
  await assertFails(updateDoc(doc(admin, 'mosques/a'), { memberCount: increment(-1), memberOpId: 'member-a' }));
  const noCounter = writeBatch(admin); noCounter.delete(doc(admin, 'users/member-a'));
  await assertFails(noCounter.commit());
  await assertSucceeds(deleteMember(secretary, 'member-a'));
  if ((await getDoc(doc(admin, 'mosques/a'))).data().memberCount !== 5) throw new Error('Incorrect member count');
  await assertFails(deleteMember(secretary, 'member-a'));
});

test('revoked staff cannot update or delete members', async () => {
  const secretary = dbFor('secretary-a');
  await env.withSecurityRulesDisabled(async context => {
    await updateDoc(doc(context.firestore(), 'users/secretary-a'), { active: false });
  });
  await assertFails(updateDoc(doc(secretary, 'users/member-a'), { name: 'No' }));
  await assertFails(deleteMember(secretary, 'member-a'));
});


test('optimistic metadata transaction detects an edit made on another device', async () => {
  const first = dbFor('admin-a'), second = dbFor('admin-a');
  let readComplete, release;
  const readReady = new Promise(resolve => { readComplete = resolve; });
  const proceed = new Promise(resolve => { release = resolve; });
  let attempt = 0;
  const edit = runTransaction(first, async tx => {
    const ref = doc(first, 'mosques/a/funds/general');
    const snap = await tx.get(ref);
    if (snap.data().name !== 'General') throw new Error('stale metadata');
    if (attempt++ === 0) { readComplete(); await proceed; }
    tx.update(ref, { name: 'First device' });
  });
  await readReady;
  await assertSucceeds(updateDoc(doc(second, 'mosques/a/funds/general'), { name: 'Second device' }));
  release();
  await assert.rejects(edit, /stale metadata/);
  if ((await getDoc(doc(first, 'mosques/a/funds/general'))).data().name !== 'Second device') throw new Error('stale edit overwrote new data');
});


test('sync markers are bound to the actor and document, and cannot be changed', async () => {
  const db = dbFor('admin-a');
  await assertSucceeds(createDirectory(db, 'funds', 'sync1', { ...fund(), syncOpId: 'sync1', syncOwner: 'admin-a' }));
  await assertFails(createDirectory(db, 'funds', 'sync2', { ...fund(), syncOpId: 'sync2', syncOwner: 'admin-b' }));
  await assertFails(createDirectory(db, 'funds', 'sync3', { ...fund(), syncOpId: 'other', syncOwner: 'admin-a' }));
  await assertFails(createDirectory(db, 'funds', 'sync4', { ...fund(), syncOpId: 'sync4' }));
  await assertFails(updateDoc(doc(db, 'mosques/a/funds/sync1'), { syncOwner: 'admin-b' }));
  await assertFails(createDirectory(db, 'funds', 'sync1', { ...fund(), syncOpId: 'sync1', syncOwner: 'admin-a' }));
  if ((await getDoc(doc(db, 'mosques/a'))).data().fundCount !== 2) throw new Error('retry altered counter');
});


function editWithReceipt(db, kind, targetId, opId, change, action = 'update', uid = 'admin-a') {
  const b = writeBatch(db);
  b.update(doc(db, `mosques/a/${kind}/${targetId}`), { ...change, lastSyncOpId: opId, lastSyncOwner: uid });
  b.set(doc(db, `mosques/a/syncReceipts/${opId}`), { uid, kind, targetId, action });
  return b.commit();
}
test('queued metadata updates require a paired immutable receipt', async () => {
  const db = dbFor('admin-a');
  await assertSucceeds(editWithReceipt(db, 'funds', 'general', 'edit1', { name: 'Edited' }));
  await assertFails(editWithReceipt(db, 'funds', 'general', 'edit1', { name: 'Again' }));
  await assertFails(updateDoc(doc(db, 'mosques/a/funds/general'), { name: 'Unpaired', lastSyncOpId: 'missing', lastSyncOwner: 'admin-a' }));
  await assertFails(setDoc(doc(db, 'mosques/a/syncReceipts/fake'), { uid: 'admin-a', kind: 'funds', targetId: 'general', action: 'update' }));
  await assertFails(updateDoc(doc(db, 'mosques/a/syncReceipts/edit1'), { targetId: 'other' }));
  await assertFails(editWithReceipt(db, 'funds', 'general', 'forged', { name: 'Wrong owner' }, 'update', 'admin-b'));
  await assertFails(editWithReceipt(db, 'funds', 'general', 'balance', { balance: 1000 }));
  if ((await getDoc(doc(db, 'mosques/a/funds/general'))).data().name !== 'Edited') throw new Error('invalid update committed');
});

test('archive receipts respect donor permissions and target state', async () => {
  const admin = dbFor('admin-a'), cashier = dbFor('cashier-a');
  await assertSucceeds(createDirectory(admin, 'donors', 'donor-receipt', donor()));
  await assertFails(editWithReceipt(cashier, 'donors', 'donor-receipt', 'arch1', { active: false }, 'archive', 'cashier-a'));
  await assertFails(editWithReceipt(admin, 'donors', 'donor-receipt', 'arch2', { active: true }, 'archive'));
  await assertSucceeds(editWithReceipt(admin, 'donors', 'donor-receipt', 'arch3', { active: false }, 'archive'));
  await assertFails(editWithReceipt(dbFor('admin-b'), 'donors', 'donor-receipt', 'arch4', { active: false }, 'archive', 'admin-b'));
});

test('ledger migration flag cannot be self-enabled and v2 directory entries require integer fields', async () => {
  const db = dbFor('admin-a');
  await assertFails(updateDoc(doc(db, 'mosques/a'), {ledgerVersion:2, totalBalancePoisha:10000}));
  await assertFails(updateDoc(doc(db, 'mosques/a'), {ledgerVersion:2, totalBalancePoisha:999, ledgerInitOp:'v2-init'}));
  await assertSucceeds(updateDoc(doc(db, 'mosques/a/funds/general'), {balancePoisha:10000}));
  await assertSucceeds(updateDoc(doc(db, 'mosques/a'), {ledgerVersion:2, totalBalancePoisha:10000, ledgerInitOp:'v2-init'}));
  await assertFails(updateDoc(doc(db, 'mosques/a'), {ledgerVersion:2, totalBalancePoisha:10000, ledgerInitOp:'v2-init'}));
  await env.withSecurityRulesDisabled(async ctx => {
    await updateDoc(doc(ctx.firestore(), 'mosques/a'), {ledgerVersion:2, totalBalancePoisha:10000});
  });
  await assertFails(createDirectory(db, 'funds', 'old-client', fund()));
  await assertFails(createDirectory(db, 'donors', 'old-client', donor()));
  await assertSucceeds(createDirectory(db, 'funds', 'new-client', {...fund(), balancePoisha:0}));
  await assertSucceeds(createDirectory(db, 'donors', 'new-client', {...donor(), totalDonatedPoisha:0}));
  await assertFails(updateDoc(doc(db, 'mosques/a/funds/new-client'), {balancePoisha:1}));
});

test('platform cannot reactivate or modify a frozen tenant during ledger migration', async () => {
  const db = dbFor('platform');
  await env.withSecurityRulesDisabled(async ctx => {
    await updateDoc(doc(ctx.firestore(), 'mosques/a'), {active:false, ledgerMigration:'frozen'});
  });
  await assertFails(updateDoc(doc(db, 'mosques/a'), {active:true}));
  await assertFails(updateDoc(doc(db, 'mosques/a'), {name:'Edited during migration'}));
  await assertFails(updateDoc(doc(db, 'mosques/a'), {ledgerMigration:'complete'}));
});

test('platform admin can atomically provision a new v2 mosque and its first admin, and non-atomic or non-platform attempts fail', async () => {
  const platformDb = dbFor('platform');
  const mosqueDoc = {
    name: 'নতুন মসজিদ', nameEn: 'New Mosque', address: 'Dhaka', district: 'Dhaka',
    upazila: 'Savar', phone: '01711223344', email: 'mosque@example.test',
    establishedYear: '2026', registrationNo: 'REG-1', active: true, createdAt: 1000,
    totalBalance: 0, totalBalancePoisha: 0, ledgerVersion: 2,
    memberCount: 1, fundCount: 0, donorCount: 0, firstAdminUid: 'new-admin-1'
  };
  const adminDoc = {
    name: 'প্রথম অ্যাডমিন', email: 'firstadmin@example.test', phone: '01711223344',
    role: 'ADMIN', mosqueId: 'mosque-new-1', designation: 'প্রশাসক', active: true, createdAt: 1000
  };
  await assertFails(setDoc(doc(platformDb, 'mosques/mosque-new-1'), mosqueDoc));
  await assertFails(setDoc(doc(platformDb, 'users/new-admin-1'), adminDoc));
  const adminADb = dbFor('admin-a');
  const tenantBatch = writeBatch(adminADb);
  tenantBatch.set(doc(adminADb, 'mosques/mosque-new-1'), mosqueDoc);
  tenantBatch.set(doc(adminADb, 'users/new-admin-1'), adminDoc);
  await assertFails(tenantBatch.commit());

  const okBatch = writeBatch(platformDb);
  okBatch.set(doc(platformDb, 'mosques/mosque-new-1'), mosqueDoc);
  okBatch.set(doc(platformDb, 'users/new-admin-1'), adminDoc);
  await assertSucceeds(okBatch.commit());
  assert.equal((await getDoc(doc(platformDb, 'mosques/mosque-new-1'))).data().ledgerVersion, 2);
  assert.equal((await getDoc(doc(platformDb, 'users/new-admin-1'))).data().role, 'ADMIN');
});

