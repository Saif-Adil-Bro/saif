'use strict';
// A small in-memory transaction harness exercises authorization and mutation
// behavior. It is not a replacement for Firestore Emulator rules/integration tests.
const {test, beforeEach} = require('node:test');
const assert = require('node:assert/strict');
const Module = require('node:module');
const docs = new Map();
class Ref {
  constructor(path) { this.path = path; this.id = path.split('/').at(-1); }
  collection(name) { return {doc: id => new Ref(`${this.path}/${name}/${id || 'new-id'}`)}; }
  async get() { return snapshot(this); }
}
function snapshot(ref) { const v = docs.get(ref.path); return {ref, id: ref.id, exists: !!v, data: () => v && structuredClone(v)}; }
const increment = n => ({__increment: n});
const db = {
  doc: path => new Ref(path),
  collection: name => ({doc: id => new Ref(`${name}/${id || 'new-id'}`)}),
  runTransaction: async fn => {
    const writes = [];
    const tx = {
      get: async ref => { if (writes.length) throw Error('reads after writes'); return snapshot(ref); },
      update: (ref, data) => writes.push(['update', ref, data]),
      set: (ref, data) => writes.push(['set', ref, data]),
      create: (ref, data) => writes.push(['create', ref, data]),
      delete: ref => writes.push(['delete', ref])
    };
    const result = await fn(tx);
    for (const [op, ref, data] of writes) {
      if (op === 'delete') { docs.delete(ref.path); continue; }
      const value = op === 'update' ? {...docs.get(ref.path)} : {};
      for (const [key, v] of Object.entries(data)) value[key] = v?.__increment !== undefined ? (value[key] || 0) + v.__increment : v;
      docs.set(ref.path, value);
    }
    return result;
  }
};
class HttpsError extends Error { constructor(code, message) { super(message); this.code = code; } }
const load = Module._load;
Module._load = function(name, ...args) {
  if (name === 'firebase-functions/v2/https') return {onCall: fn => fn, HttpsError};
  if (name === 'firebase-admin/app') return {initializeApp() {}};
  if (name === 'firebase-admin/firestore') return {getFirestore: () => db, FieldValue: {increment}};
  if (name === 'firebase-admin/auth') return {getAuth: () => ({})};
  return load.call(this, name, ...args);
};
const {mutate} = require('./index');
Module._load = load;
const call = data => mutate({auth:{uid:'u'}, data});
beforeEach(() => {
  docs.clear();
  docs.set('users/u', {active:true, mosqueId:'m', role:'CASHIER'});
  docs.set('mosques/m', {active:true, totalBalance:1000, fundCount:1, donorCount:1, memberCount:1});
  docs.set('mosques/m/funds/f', {balance:1000, active:true});
  docs.set('mosques/m/donors/d', {totalDonated:100, active:true});
  docs.set('mosques/m/contributions/c', {mosqueId:'m', accountId:'f', donorId:'d', donorName:'A', amount:100, date:'2026-01-01', note:'', receiptNo:'', createdAt:1, createdBy:'u'});
});
test('cashier edits a contribution atomically using persisted data and donor delta', async () => {
  const old = docs.get('mosques/m/contributions/c');
  await call({operation:'ledger', kind:'contributions', action:'update', mosqueId:'m', id:'c', expected: old, value:{...old, amount:150}});
  assert.equal(docs.get('mosques/m/funds/f').balance, 1050);
  assert.equal(docs.get('mosques/m').totalBalance, 1050);
  assert.equal(docs.get('mosques/m/donors/d').totalDonated, 150);
});
test('stale edits fail without writes', async () => {
  const old = {...docs.get('mosques/m/contributions/c'), amount:99};
  await assert.rejects(call({operation:'ledger', kind:'contributions', action:'delete', mosqueId:'m', id:'c', expected:old}), /changed/);
  assert.equal(docs.get('mosques/m/funds/f').balance, 1000);
});
test('insufficient funds leave transaction unmodified', async () => {
  const value = {accountId:'f', amount:1001, title:'Test', date:'2026-01-01'};
  await assert.rejects(call({operation:'ledger', kind:'expenses', action:'add', mosqueId:'m', value}), /Insufficient/);
  assert.equal(docs.get('mosques/m/funds/f').balance, 1000);
  assert.equal(docs.has('mosques/m/expenses/new-id'), false);
});
test('cashier cannot manage members or a different tenant', async () => {
  await assert.rejects(call({operation:'directory', kind:'members', action:'update', mosqueId:'m', id:'u', value:{role:'SUPERADMIN', active:true}}), /Permission denied/);
  await assert.rejects(call({operation:'ledger', kind:'expenses', action:'add', mosqueId:'other', value:{}}), /Permission denied/);
});
test('member manager cannot promote themselves to superadmin', async () => {
  docs.set('users/u', {active:true, mosqueId:'m', role:'SECRETARY'});
  await assert.rejects(call({operation:'directory', kind:'members', action:'update', mosqueId:'m', id:'u', value:{role:'SUPERADMIN', active:true}}), /Cannot delete/);
  assert.equal(docs.get('users/u').role, 'SECRETARY');
});
test('member manager cannot assign superadmin to another member', async () => {
  docs.set('users/u', {active:true, mosqueId:'m', role:'SECRETARY'});
  docs.set('users/v', {active:true, mosqueId:'m', role:'MEMBER', name:'V'});
  await assert.rejects(call({operation:'directory', kind:'members', action:'update', mosqueId:'m', id:'v', value:{name:'V', role:'SUPERADMIN', active:true}}), /Role not permitted/);
  assert.equal(docs.get('users/v').role, 'MEMBER');
});
test('archived funds cannot be used in new transactions', async () => {
  docs.get('mosques/m/funds/f').active = false;
  await assert.rejects(call({operation:'ledger', kind:'expenses', action:'add', mosqueId:'m', value:{accountId:'f', amount:1, title:'Test', date:'2026-01-01'}}), /inactive/);
  assert.equal(docs.get('mosques/m/funds/f').balance, 1000);
});
test('fund metadata updates do not overwrite the current balance', async () => {
  docs.set('users/u', {active:true, mosqueId:'m', role:'TREASURER'});
  await call({operation:'directory', kind:'funds', action:'update', mosqueId:'m', id:'f', value:{name:'Fund', code:'GEN', active:true, balance:1}});
  assert.equal(docs.get('mosques/m/funds/f').balance, 1000);
});

test('legacy callable refuses writes after v2 migration', async () => {
  docs.get('mosques/m').ledgerVersion = 2;
  await assert.rejects(call({operation:'ledger',kind:'expenses',action:'add',mosqueId:'m',value:{accountId:'f',amount:1,title:'Test',date:'2026-01-01'}}), /migrated/);
  assert.equal(docs.get('mosques/m/funds/f').balance, 1000);
});
