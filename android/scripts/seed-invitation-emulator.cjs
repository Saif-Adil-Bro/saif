'use strict';
// Never use this fixture against live Firebase.
if (process.env.FIRESTORE_EMULATOR_HOST !== '127.0.0.1:8080' ||
    process.env.FIREBASE_AUTH_EMULATOR_HOST !== '127.0.0.1:9099' || !process.env.GCLOUD_PROJECT) throw Error('Emulators required');
const {createRequire}=require('node:module');
const path=require('node:path');
const load=createRequire(path.resolve(__dirname,'../functions/package.json'));
const {initializeApp}=load('firebase-admin/app');
const {getFirestore}=load('firebase-admin/firestore');
const {getAuth}=load('firebase-admin/auth');
initializeApp({projectId:process.env.GCLOUD_PROJECT});
async function main() {
 const auth=getAuth(), db=getFirestore();
 for (const [uid,email,verified] of [
  ['inv-admin','inv-admin@example.test',true], ['inv-member','inv-member@example.test',true],
  ['inv-wrong','inv-wrong@example.test',true], ['inv-unverified','inv-unverified@example.test',false],
  ['inv-existing','inv-existing@example.test',true], ['inv-revoked','inv-revoked@example.test',true]
 ]) await auth.createUser({uid,email,password:'test-only-password',emailVerified:verified});
 const b=db.batch();
 b.set(db.doc('users/inv-admin'),{active:true,role:'ADMIN',mosqueId:'invite-test'});
 b.set(db.doc('users/inv-existing'),{active:true,role:'MEMBER',mosqueId:'other',email:'inv-existing@example.test'});
 b.set(db.doc('mosques/invite-test'),{active:true,memberCount:1});
 b.set(db.doc('mosques/other'),{active:true,memberCount:1});
 await b.commit();
}
main().catch(error=>{console.error(error);process.exitCode=1});
