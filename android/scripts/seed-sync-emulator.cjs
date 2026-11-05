'use strict';
// Only runs against Firebase emulators. Never connect this test script to production.
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
initializeApp({projectId: process.env.GCLOUD_PROJECT});
async function main() {
  const auth = getAuth(), db = getFirestore();
  for (const [uid, email] of [['outbox-a','a@example.test'], ['outbox-b','b@example.test'], ['outbox-inactive','inactive@example.test'], ['outbox-badcounter','badcounter@example.test']]) {
    await auth.createUser({uid, email, password: 'test-only-password'});
  }
  const batch = db.batch();
  for (const id of ['sync-test-mosque','sync-other-mosque']) {
    batch.set(db.doc(`mosques/${id}`), {active: true, address: '', createdAt: 1700000000000, district: '', donorCount: 0, email: '', establishedYear: null, fundCount: 0, memberCount: 1, name: 'Mosque', nameEn: '', phone: '', registrationNo: '', totalBalance: 0.0, upazila: ''});
  }
  batch.set(db.doc('mosques/sync-badcounter'), {active: true, donorCount: 0, memberCount: 1, totalBalance: 0});
  batch.set(db.doc('users/outbox-badcounter'), {active: true, role: 'ADMIN', mosqueId: 'sync-badcounter'});
  batch.set(db.doc('users/outbox-a'), {active: true, role: 'ADMIN', mosqueId: 'sync-test-mosque'});
  batch.set(db.doc('users/outbox-b'), {active: true, role: 'ADMIN', mosqueId: 'sync-other-mosque'});
  batch.set(db.doc('users/outbox-inactive'), {active: false, role: 'ADMIN', mosqueId: 'sync-test-mosque'});
  await batch.commit();
}
main().catch(error => { console.error(error); process.exitCode = 1; });
