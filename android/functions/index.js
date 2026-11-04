'use strict';
const {onCall, HttpsError} = require('firebase-functions/v2/https');
const {initializeApp} = require('firebase-admin/app');
const {getFirestore, FieldValue} = require('firebase-admin/firestore');
const {getAuth} = require('firebase-admin/auth');
const {cents, effects} = require('./ledger');
initializeApp();
const db = getFirestore();
const roles = {
  funds: ['ADMIN', 'SECRETARY', 'TREASURER'],
  donors: ['ADMIN', 'SECRETARY', 'TREASURER', 'CASHIER'],
  contributions: ['ADMIN', 'SECRETARY', 'TREASURER', 'CASHIER'],
  expenses: ['ADMIN', 'SECRETARY', 'TREASURER', 'CASHIER'],
  transfers: ['ADMIN', 'SECRETARY', 'TREASURER'],
  members: ['ADMIN', 'PRESIDENT', 'SECRETARY']
};
const memberRoles = ['ADMIN', 'PRESIDENT', 'SECRETARY', 'TREASURER', 'CASHIER', 'IMAM', 'MEMBER'];
function fail(message, code = 'failed-precondition') { throw new HttpsError(code, message); }
function id(value) {
  if (typeof value !== 'string' || !value || value.includes('/') || value === '.' || value === '..' || value.length > 1500) fail('Invalid ID', 'invalid-argument');
  return value;
}
function text(v, required = false) {
  if (typeof v !== 'string' || v.length > 1000 || (required && !v.trim())) fail('Invalid text', 'invalid-argument');
  return v.trim();
}
function password(v) {
  if (typeof v !== 'string' || v.length < 8 || v.length > 128) fail('Invalid password', 'invalid-argument');
  return v;
}
function date(v) {
  if (typeof v !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(v) || Number.isNaN(Date.parse(v)) || new Date(v).toISOString().slice(0, 10) !== v) fail('Invalid date', 'invalid-argument');
  return v;
}
function fields(src, required, optional) {
  const out = {};
  for (const key of required) out[key] = text(src[key], true);
  for (const key of optional) out[key] = text(src[key] ?? '');
  return out;
}
async function actor(tx, uid, mosqueId, category) {
  const u = (await tx.get(db.doc(`users/${uid}`))).data();
  if (!u || !u.active) fail('Account is inactive', 'permission-denied');
  if (u.role === 'SUPERADMIN') return u;
  if (u.mosqueId !== mosqueId || !roles[category]?.includes(u.role)) fail('Permission denied', 'permission-denied');
  const m = await tx.get(db.doc(`mosques/${id(mosqueId)}`));
  if (!m.exists || !m.data().active) fail('Mosque is inactive', 'permission-denied');
  return u;
}
function validMemberRole(role, caller) {
  if (!memberRoles.includes(role) || (role === 'ADMIN' && caller.role !== 'ADMIN' && caller.role !== 'SUPERADMIN')) fail('Role not permitted', 'permission-denied');
}
function moneyRow(kind, row, mosqueId, uid) {
  if (!row || typeof row !== 'object') fail('Invalid transaction', 'invalid-argument');
  const amount = cents(row.amount, true) / 100;
  const base = {mosqueId, amount, date: date(row.date), note: text(row.note ?? '')};
  if (kind === 'contributions') {
    const donorId = row.donorId ? id(row.donorId) : '';
    const donorName = text(row.donorName ?? '', !donorId);
    return {...base, accountId: id(row.accountId), donorId, donorName, receiptNo: text(row.receiptNo ?? '')};
  }
  if (kind === 'expenses') return {...base, accountId: id(row.accountId), ...fields(row, ['title'], ['category', 'paidTo', 'receiptNo']), status: 'approved', approvedBy: ''};
  if (kind === 'transfers') return {...base, fromAccountId: id(row.fromAccountId), toAccountId: id(row.toAccountId), createdBy: uid};
  fail('Invalid transaction type', 'invalid-argument');
}
// Explicit comparison prevents a stale editor from silently overwriting another edit.
function stale(old, expected, keys) {
  return !expected || keys.some(k => (old[k] ?? '') !== (expected[k] ?? ''));
}
async function ledger(uid, data) {
  const mosqueId = id(data.mosqueId), kind = data.kind, action = data.action;
  if (!['contributions', 'expenses', 'transfers'].includes(kind) || !['add', 'update', 'delete'].includes(action) || (kind === 'transfers' && action === 'update')) fail('Invalid operation', 'invalid-argument');
  const mosque = db.doc(`mosques/${mosqueId}`), ref = action === 'add' ? mosque.collection(kind).doc() : mosque.collection(kind).doc(id(data.id));
  return db.runTransaction(async tx => {
    await actor(tx, uid, mosqueId, kind);
    const m = await tx.get(mosque);
    if (!m.exists || !m.data().active) fail('Mosque is inactive');
    let old = null;
    if (action !== 'add') {
      const snap = await tx.get(ref);
      if (!snap.exists) fail('Transaction no longer exists');
      old = snap.data();
      const keys = kind === 'transfers' ? ['amount','fromAccountId','toAccountId','date','note'] : kind === 'contributions' ? ['amount','accountId','donorId','donorName','date','note','receiptNo'] : ['amount','accountId','date','title','category','paidTo','note','receiptNo'];
      if (stale(old, data.expected, keys)) fail('Record changed; refresh and try again', 'aborted');
    }
    const next = action === 'delete' ? null : moneyRow(kind, data.value, mosqueId, uid);
    const {funds, donors, total} = effects(kind, old, next);
    const fundDocs = await Promise.all([...funds.keys()].map(k => tx.get(mosque.collection('funds').doc(k))));
    const donorDocs = await Promise.all([...donors.keys()].map(k => tx.get(mosque.collection('donors').doc(k))));
    for (const snap of fundDocs) {
      if (!snap.exists) fail('Fund not found');
      const nextUses = next && (next.accountId === snap.id || next.fromAccountId === snap.id || next.toAccountId === snap.id);
      const oldUses = old && (old.accountId === snap.id || old.fromAccountId === snap.id || old.toAccountId === snap.id);
      if (nextUses && !oldUses && snap.data().active === false) fail('Fund is inactive');
      const balance = cents(snap.data().balance || 0) + funds.get(snap.id);
      if (balance < 0) fail('Insufficient balance');
      if (!Number.isSafeInteger(balance)) fail('Balance too large');
      tx.update(snap.ref, {balance: balance / 100});
    }
    for (const snap of donorDocs) {
      if (!snap.exists) fail('Donor not found');
      if (next?.donorId === snap.id && old?.donorId !== snap.id && snap.data().active === false) fail('Donor is inactive');
      if (next?.donorId === snap.id) next.donorName = snap.data().name || next.donorName;
      const donated = cents(snap.data().totalDonated || 0) + donors.get(snap.id);
      if (donated < 0 || !Number.isSafeInteger(donated)) fail('Donor total needs reconciliation');
      tx.update(snap.ref, {totalDonated: donated / 100});
    }
    const totalBalance = cents(m.data().totalBalance || 0) + total;
    if (totalBalance < 0 || !Number.isSafeInteger(totalBalance)) fail('Total balance needs reconciliation');
    if (total) tx.update(mosque, {totalBalance: totalBalance / 100});
    if (next) {
      next.createdAt = old?.createdAt ?? Date.now();
      if (kind === 'contributions') next.createdBy = old?.createdBy ?? uid;
      tx.set(ref, next);
    } else tx.delete(ref);
    return {id: ref.id};
  });
}
async function directory(uid, data) {
  const mosqueId = id(data.mosqueId), kind = data.kind, action = data.action;
  if (!['funds','donors','members'].includes(kind) || !['add','update','delete'].includes(action)) fail('Invalid operation', 'invalid-argument');
  const mosque = db.doc(`mosques/${mosqueId}`), ref = kind === 'members' ? db.doc(`users/${id(data.id)}`) : action === 'add' ? mosque.collection(kind).doc() : mosque.collection(kind).doc(id(data.id));
  return db.runTransaction(async tx => {
    const caller = await actor(tx, uid, mosqueId, kind);
    const m = await tx.get(mosque);
    if (!m.exists || !m.data().active) fail('Mosque is inactive');
    const snap = action === 'add' ? null : await tx.get(ref);
    if (snap && !snap.exists) fail('Record no longer exists');
    const old = snap?.data();
    if (kind === 'members') {
      if (action === 'add') fail('Use member provisioning');
      if (old.mosqueId !== mosqueId || old.role === 'SUPERADMIN') fail('Permission denied', 'permission-denied');
      if (caller.role !== 'ADMIN' && caller.role !== 'SUPERADMIN' && old.role === 'ADMIN') fail('Permission denied', 'permission-denied');
      if (ref.id === uid && (action === 'delete' || data.value?.active === false || data.value?.role !== old.role)) fail('Cannot delete, deactivate or change your own role');
      if (action === 'delete') { tx.delete(ref); tx.update(mosque, {memberCount: FieldValue.increment(-1)}); }
      else {
        validMemberRole(data.value.role, caller);
        tx.update(ref, {...fields(data.value, ['name'], ['phone','designation']), role: data.value.role, active: data.value.active === true});
      }
    } else if (action === 'delete') {
      if (kind === 'donors' && !roles.funds.includes(caller.role) && caller.role !== 'SUPERADMIN') fail('Permission denied', 'permission-denied');
      // Archive rather than delete: historical transactions must remain resolvable.
      tx.update(ref, {active: false});
    } else {
      const value = kind === 'funds' ? {...fields(data.value, ['name','code'], ['nameEn','description']), active: data.value.active === true} : {...fields(data.value, ['name'], ['phone','address']), active: data.value.active === true};
      if (action === 'add') {
        tx.set(ref, {...value, mosqueId, ...(kind === 'funds' ? {balance: 0} : {totalDonated: 0, createdAt: Date.now()})});
        tx.update(mosque, {[kind === 'funds' ? 'fundCount' : 'donorCount']: FieldValue.increment(1)});
      } else tx.update(ref, value); // never overwrite a concurrent balance/total
    }
    return {id: ref.id};
  });
}
async function createMember(uid, data) {
  const mosqueId = id(data.mosqueId), input = data.value || {};
  const caller = await db.runTransaction(tx => actor(tx, uid, mosqueId, 'members'));
  validMemberRole(input.role, caller);
  const email = text(input.email, true).toLowerCase(), secret = password(input.password);
  const details = fields(input, ['name'], ['phone', 'designation']);
  let account;
  try {
    account = await getAuth().createUser({email, password: secret});
    await db.runTransaction(async tx => {
      const verified = await actor(tx, uid, mosqueId, 'members');
      validMemberRole(input.role, verified);
      tx.create(db.doc(`users/${account.uid}`), {...details, email, mosqueId, role: input.role, active: true, createdAt: Date.now()});
      tx.update(db.doc(`mosques/${mosqueId}`), {memberCount: FieldValue.increment(1)});
    });
    return {uid: account.uid};
  } catch (e) {
    if (account) { try { await getAuth().deleteUser(account.uid); } catch (cleanup) { console.error('Orphan auth account requires cleanup', account.uid, cleanup); } }
    throw e;
  }
}
async function createMosque(uid, data) {
  const caller = (await db.doc(`users/${uid}`).get()).data();
  if (!caller?.active || caller.role !== 'SUPERADMIN') fail('Permission denied', 'permission-denied');
  const input = data.value || {}, admin = data.admin || {};
  const email = text(admin.email, true).toLowerCase(), secret = password(admin.password);
  const details = fields(input, ['name'], ['nameEn','address','district','upazila','phone','email','registrationNo']);
  const adminName = text(admin.name, true), adminPhone = text(admin.phone ?? '');
  const year = input.establishedYear;
  if (year != null && (!Number.isInteger(year) || year < 1900 || year > 2100)) fail('Invalid year', 'invalid-argument');
  let account;
  try {
    account = await getAuth().createUser({email, password: secret});
    const mosque = db.collection('mosques').doc();
    await db.runTransaction(async tx => {
      const verified = (await tx.get(db.doc(`users/${uid}`))).data();
      if (!verified?.active || verified.role !== 'SUPERADMIN') fail('Permission denied', 'permission-denied');
      tx.create(mosque, {...details, establishedYear: year ?? null, active: true, totalBalance: 0, fundCount: 5, donorCount: 0, memberCount: 1, createdAt: Date.now()});
      for (const [code, name] of [['GEN','সাধারণ তহবিল'],['CHD','চাঁদা তহবিল'],['CON','নির্মাণ তহবিল'],['WAK','ওয়াকফ তহবিল'],['UTL','ইউটিলিটি তহবিল']]) tx.create(mosque.collection('funds').doc(), {mosqueId: mosque.id, code, name, nameEn: '', description: '', active: true, balance: 0});
      tx.create(db.doc(`users/${account.uid}`), {name: adminName, email, phone: adminPhone, role: 'ADMIN', mosqueId: mosque.id, designation: '', active: true, createdAt: Date.now()});
    });
    return {id: mosque.id};
  } catch (e) {
    if (account) { try { await getAuth().deleteUser(account.uid); } catch (cleanup) { console.error('Orphan auth account requires cleanup', account.uid, cleanup); } }
    throw e;
  }
}
exports.mutate = onCall(async request => {
  if (!request.auth) fail('Sign in required', 'unauthenticated');
  try {
    const data = request.data || {};
    switch (data.operation) {
      case 'ledger': return await ledger(request.auth.uid, data);
      case 'directory': return await directory(request.auth.uid, data);
      case 'createMember': return await createMember(request.auth.uid, data);
      case 'createMosque': return await createMosque(request.auth.uid, data);
      default: fail('Unknown operation', 'invalid-argument');
    }
  } catch (e) {
    if (e instanceof HttpsError) throw e;
    console.error('Mutation failed', e);
    throw new HttpsError('failed-precondition', e.message || 'Operation failed');
  }
});
