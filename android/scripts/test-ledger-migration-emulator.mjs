// Test-only integration, guarded against connecting to non-emulator Firestore.
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {beginMigration,applyMigration} from './migrate-ledger.mjs';
if (process.env.FIRESTORE_EMULATOR_HOST !== '127.0.0.1:8080' || !process.env.GCLOUD_PROJECT) throw Error('Firestore emulator required');
const require=createRequire(new URL('../functions/package.json',import.meta.url));
const {initializeApp}=require('firebase-admin/app');
const {getFirestore}=require('firebase-admin/firestore');
initializeApp({projectId:process.env.GCLOUD_PROJECT});
const db=getFirestore(), parent=db.doc('mosques/migration-test');
const b=db.batch();
b.set(parent,{active:true,totalBalance:80,fundCount:2,donorCount:1});
b.set(parent.collection('funds').doc('a'),{mosqueId:'migration-test',active:true,balance:70});
b.set(parent.collection('funds').doc('b'),{mosqueId:'migration-test',active:false,balance:10});
b.set(parent.collection('donors').doc('d'),{mosqueId:'migration-test',active:true,totalDonated:100});
b.set(parent.collection('contributions').doc('c'),{mosqueId:'migration-test',accountId:'a',donorId:'d',amount:100});
b.set(parent.collection('expenses').doc('e'),{mosqueId:'migration-test',accountId:'a',amount:20});
b.set(parent.collection('transfers').doc('t'),{mosqueId:'migration-test',fromAccountId:'a',toAccountId:'b',amount:10});
await b.commit();
const opening={openingFunds:{a:0,b:0},openingDonors:{d:0}};
await beginMigration(db,'migration-test'); await beginMigration(db,'migration-test');
assert.equal((await parent.get()).data().active,false);
await assert.rejects(applyMigration(db,'migration-test',{openingFunds:{a:0},openingDonors:{d:0}}),/every funds/);
assert.equal((await parent.get()).data().ledgerMigration,'frozen');
let interrupted=false;
const interference={doc:db.doc.bind(db),runTransaction:async fn=>{
  if (!interrupted) { interrupted=true; await parent.collection('funds').doc('a').update({name:'Concurrent admin write'}); }
  return db.runTransaction(fn);
}};
await assert.rejects(applyMigration(interference,'migration-test',opening),/changed during migration/);
assert.equal((await parent.get()).data().active,false);
assert.deepEqual(await applyMigration(db,'migration-test',opening),{funds:2,donors:1});
const m=(await parent.get()).data();
assert.equal(m.ledgerVersion,2); assert.equal(m.totalBalancePoisha,8000); assert.equal(m.active,true);
assert.equal((await parent.collection('funds').doc('a').get()).data().balancePoisha,7000);
assert.equal((await parent.collection('funds').doc('b').get()).data().balancePoisha,1000);
assert.equal((await parent.collection('donors').doc('d').get()).data().totalDonatedPoisha,10000);
await assert.rejects(beginMigration(db,'migration-test'),/cannot enter/);
console.log('Ledger migration emulator checks passed');
