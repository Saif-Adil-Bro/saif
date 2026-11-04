// Prototype rules are isolated from the deployed app rules; this suite cannot touch production.
import { before, beforeEach, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc, writeBatch, runTransaction } from 'firebase/firestore';

let env;
before(async () => {
  if (!process.env.FIRESTORE_EMULATOR_HOST) throw new Error('Use the Firestore emulator');
  env = await initializeTestEnvironment({ projectId: 'demo-mosque-rules',
    firestore: { rules: readFileSync(new URL('./ledger-prototype.rules', import.meta.url), 'utf8') } });
});
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async context => {
    const db = context.firestore();
    await Promise.all([
      setDoc(doc(db, 'users/admin'), { active:true, role:'ADMIN', mosqueId:'a' }),
      setDoc(doc(db, 'users/cashier'), { active:true, role:'CASHIER', mosqueId:'a' }),
      setDoc(doc(db, 'users/member'), { active:true, role:'MEMBER', mosqueId:'a' }),
      setDoc(doc(db, 'users/other'), { active:true, role:'ADMIN', mosqueId:'b' }),
      setDoc(doc(db, 'users/inactive'), { active:false, role:'ADMIN', mosqueId:'a' }),
      setDoc(doc(db, 'mosques/a'), { active:true, totalBalance:100, totalBalancePoisha:10000 }),
      setDoc(doc(db, 'mosques/b'), { active:true, totalBalance:0, totalBalancePoisha:0 }),
      setDoc(doc(db, 'mosques/a/funds/general'), { mosqueId:'a', active:true, name:'General', balance:100, balancePoisha:10000 }),
      setDoc(doc(db, 'mosques/a/funds/other'), { mosqueId:'a', active:true, name:'Other', balance:0, balancePoisha:0 }),
      setDoc(doc(db, 'mosques/a/funds/archived'), { mosqueId:'a', active:false, name:'Archived', balance:0, balancePoisha:0 }),
      setDoc(doc(db, 'mosques/a/donors/d'), { mosqueId:'a', active:true, name:'Donor', totalDonated:0, totalDonatedPoisha:0 }),
      setDoc(doc(db, 'mosques/b/funds/general'), { mosqueId:'b', active:true, name:'B', balance:0, balancePoisha:0 }),
    ]);
  });
});
after(async () => { if (env) await env.cleanup(); });
const dbFor = uid => env.authenticatedContext(uid).firestore();
const money = n => n / 100;
const base = (uid, cents) => ({ mosqueId:'a', amount:money(cents), amountPoisha:cents,
  date:'2026-11-05', note:'', createdBy:uid, createdAt:1 });
const contribution = (uid, cents) => ({ ...base(uid,cents), accountId:'general', donorId:'d', donorName:'Donor', receiptNo:'' });
const expense = (uid, cents) => ({ ...base(uid,cents), accountId:'general', title:'Electricity', category:'', paidTo:'', receiptNo:'', status:'approved', approvedBy:'' });
const transfer = (uid, cents) => ({ ...base(uid,cents), fromAccountId:'general', toAccountId:'other' });
const fundUpdate = (balance, kind, id) => ({ balance:money(balance), balancePoisha:balance, ledgerOpKind:kind, ledgerOpId:id });
const mosqueUpdate = (balance, kind, id) => ({ totalBalance:money(balance), totalBalancePoisha:balance, ledgerOpKind:kind, ledgerOpId:id });
const donorUpdate = (balance, id) => ({ totalDonated:money(balance), totalDonatedPoisha:balance, ledgerOpKind:'contributions', ledgerOpId:id });
function addContribution(db, id, row = contribution('admin',1234)) {
  const b = writeBatch(db); b.set(doc(db, `mosques/a/contributions/${id}`), row);
  b.update(doc(db,'mosques/a/funds/general'), fundUpdate(10000 + row.amountPoisha, 'contributions', id));
  b.update(doc(db,'mosques/a'), mosqueUpdate(10000 + row.amountPoisha, 'contributions', id));
  if (row.donorId) b.update(doc(db,'mosques/a/donors/d'), donorUpdate(row.amountPoisha, id));
  return b.commit();
}
function addExpense(db, id, row = expense('admin',2500)) {
  const b = writeBatch(db); b.set(doc(db, `mosques/a/expenses/${id}`), row);
  b.update(doc(db,'mosques/a/funds/general'), fundUpdate(10000 - row.amountPoisha,'expenses',id));
  b.update(doc(db,'mosques/a'), mosqueUpdate(10000 - row.amountPoisha,'expenses',id));
  return b.commit();
}
function addTransfer(db, id, row = transfer('admin',2500)) {
  const b = writeBatch(db); b.set(doc(db, `mosques/a/transfers/${id}`), row);
  b.update(doc(db,'mosques/a/funds/general'), fundUpdate(10000-row.amountPoisha,'transfers',id));
  b.update(doc(db,'mosques/a/funds/other'), fundUpdate(row.amountPoisha,'transfers',id));
  return b.commit();
}

test('contribution atomically changes fund, mosque and donor, and cannot be replayed', async () => {
  const db=dbFor('admin'); await assertSucceeds(addContribution(db,'c1'));
  assert.equal((await getDoc(doc(db,'mosques/a/funds/general'))).data().balancePoisha,11234);
  assert.equal((await getDoc(doc(db,'mosques/a'))).data().totalBalancePoisha,11234);
  assert.equal((await getDoc(doc(db,'mosques/a/donors/d'))).data().totalDonatedPoisha,1234);
  await assertFails(addContribution(db,'c1'));
  assert.equal((await getDoc(doc(db,'mosques/a'))).data().totalBalancePoisha,11234);
});

test('expense and transfer apply correct integer deltas without overdrawing', async () => {
  const db=dbFor('admin'); await assertSucceeds(addExpense(db,'e1'));
  assert.equal((await getDoc(doc(db,'mosques/a'))).data().totalBalancePoisha,7500);
});

test('transfer changes exactly two funds and does not change mosque total', async () => {
  const db=dbFor('admin'); await assertSucceeds(addTransfer(db,'t1'));
  assert.equal((await getDoc(doc(db,'mosques/a/funds/general'))).data().balancePoisha,7500);
  assert.equal((await getDoc(doc(db,'mosques/a/funds/other'))).data().balancePoisha,2500);
  assert.equal((await getDoc(doc(db,'mosques/a'))).data().totalBalancePoisha,10000);
  await assertFails(addTransfer(db,'t1'));
});

test('no isolated ledger or balance manipulation, and batches fail atomically', async () => {
  const db=dbFor('admin');
  await assertFails(updateDoc(doc(db,'mosques/a/funds/general'), fundUpdate(999999,'contributions','fake')));
  await assertFails(updateDoc(doc(db,'mosques/a'), mosqueUpdate(999999,'contributions','fake')));
  await assertFails(updateDoc(doc(db,'mosques/a/donors/d'), donorUpdate(999999,'fake')));
  await assertFails(setDoc(doc(db,'mosques/a/contributions/orphan'), contribution('admin',100)));
  const b=writeBatch(db); b.set(doc(db,'mosques/a/contributions/incomplete'),contribution('admin',100));
  b.update(doc(db,'mosques/a/funds/general'),fundUpdate(10100,'contributions','incomplete'));
  b.update(doc(db,'mosques/a'),mosqueUpdate(10100,'contributions','incomplete'));
  await assertFails(b.commit());
  assert.equal((await getDoc(doc(db,'mosques/a/funds/general'))).data().balancePoisha,10000);
});

test('unauthorized, inactive, cross-tenant, spoofed and cashier transfers are denied', async () => {
  await assertFails(addContribution(dbFor('member'),'x',contribution('member',100)));
  await assertFails(addContribution(dbFor('inactive'),'x',contribution('inactive',100)));
  await assertFails(addContribution(dbFor('other'),'x',contribution('other',100)));
  await assertFails(addContribution(dbFor('cashier'),'x',contribution('admin',100)));
  await assertFails(addTransfer(dbFor('cashier'),'x',transfer('cashier',100)));
  await assertSucceeds(addContribution(dbFor('cashier'),'ok',contribution('cashier',100)));
});

test('invalid amounts, forged donors, inactive funds and extra fields fail', async () => {
  const db=dbFor('admin');
  for (const row of [
    { ...contribution('admin',100), amountPoisha:0, amount:0 },
    { ...contribution('admin',100), amountPoisha:1.5, amount:0.015 },
    { ...contribution('admin',100), amount:999 },
    { ...contribution('admin',100), donorName:'Impersonated' },
    { ...contribution('admin',100), accountId:'archived' },
    { ...contribution('admin',100), extra:'not allowed' },
    { ...contribution('admin',100), date:'2026-99-99' },
  ]) await assertFails(addContribution(db,`bad-${Math.random().toString(36).slice(2)}`,row));
  await assertFails(addExpense(db,'overdraft',expense('admin',15000)));
  await assertFails(addTransfer(db,'same',{ ...transfer('admin',100), toAccountId:'general' }));
});

test('concurrent expense transactions cannot both spend the same balance', async () => {
  const a=dbFor('admin'), b=dbFor('cashier');
  async function spend(db, uid, id) {
    return runTransaction(db, async tx => {
      const fund=doc(db,'mosques/a/funds/general'), mosque=doc(db,'mosques/a');
      const [f,m]=await Promise.all([tx.get(fund),tx.get(mosque)]);
      if (f.data().balancePoisha < 8000 || m.data().totalBalancePoisha < 8000) throw new Error('insufficient balance');
      tx.set(doc(db,`mosques/a/expenses/${id}`), expense(uid,8000));
      tx.update(fund,fundUpdate(f.data().balancePoisha-8000,'expenses',id));
      tx.update(mosque,mosqueUpdate(m.data().totalBalancePoisha-8000,'expenses',id));
    });
  }
  const result=await Promise.allSettled([spend(a,'admin','race-a'),spend(b,'cashier','race-b')]);
  assert.equal(result.filter(x=>x.status==='fulfilled').length,1);
  assert.equal((await getDoc(doc(a,'mosques/a/funds/general'))).data().balancePoisha,2000);
  assert.equal((await getDoc(doc(a,'mosques/a'))).data().totalBalancePoisha,2000);
});

test('prototype entries are append-only: update and delete are forbidden', async () => {
  const db=dbFor('admin'); await assertSucceeds(addExpense(db,'e1'));
  await assertFails(updateDoc(doc(db,'mosques/a/expenses/e1'), { amountPoisha:1, amount:0.01 }));
  const b=writeBatch(db); b.delete(doc(db,'mosques/a/expenses/e1')); await assertFails(b.commit());
});
