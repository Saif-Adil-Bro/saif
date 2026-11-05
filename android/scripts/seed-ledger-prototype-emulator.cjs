'use strict';
// Test-only fixture. Never point this script at a live project.
if (process.env.FIRESTORE_EMULATOR_HOST !== '127.0.0.1:8080' ||
    process.env.FIREBASE_AUTH_EMULATOR_HOST !== '127.0.0.1:9099' || !process.env.GCLOUD_PROJECT) {
  throw new Error('Firestore and Auth emulators are required');
}
const {createRequire} = require('node:module');
const path = require('node:path');
const fromFunctions = createRequire(path.resolve(__dirname, '../functions/package.json'));
const {initializeApp} = fromFunctions('firebase-admin/app');
const {getFirestore} = fromFunctions('firebase-admin/firestore');
const {getAuth} = fromFunctions('firebase-admin/auth');
initializeApp({projectId:process.env.GCLOUD_PROJECT});
async function main() {
  const db=getFirestore(), auth=getAuth();
  await auth.createUser({uid:'ledger-admin',email:'ledger@example.test',password:'test-only-password'});
  await auth.createUser({uid:'ledger-other',email:'other-ledger@example.test',password:'test-only-password'});
  const batch=db.batch();
  batch.set(db.doc('users/ledger-admin'),{active:true,role:'ADMIN',mosqueId:'ledger-prototype'});
  batch.set(db.doc('users/ledger-other'),{active:true,role:'ADMIN',mosqueId:'elsewhere'});
  batch.set(db.doc('mosques/ledger-prototype'),{active:true,ledgerVersion:2,totalBalance:100,totalBalancePoisha:10000});
  batch.set(db.doc('mosques/ledger-prototype/funds/main'),{active:true,mosqueId:'ledger-prototype',balance:100,balancePoisha:10000});
  batch.set(db.doc('mosques/ledger-prototype/funds/target'),{active:true,mosqueId:'ledger-prototype',balance:0,balancePoisha:0});
  batch.set(db.doc('mosques/ledger-prototype/funds/legacy'),{active:true,mosqueId:'ledger-prototype',balance:0});
  batch.set(db.doc('mosques/ledger-prototype/donors/d'),{active:true,mosqueId:'ledger-prototype',name:'Donor',totalDonated:0,totalDonatedPoisha:0});
  await batch.commit();
}
main().catch(error=>{console.error(error);process.exitCode=1});
