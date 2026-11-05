// seed-demo.mjs — seed the demo accounts + demo mosque into YOUR Firebase project
// so that the demo login buttons in the app work.
//
// What it creates (idempotent — safe to re-run):
//   Auth users:  superadmin@demo.local / demo12345  (role SUPERADMIN, mosqueId null)
//                admin@demo.local      / demo12345  (role ADMIN, mosqueId demo-mosque-0001)
//   Firestore:   users/{uid} profile docs for both users
//                mosques/demo-mosque-0001 (+ its 5 starter funds GEN/CHD/CON/WAK/UTL)
//
// Usage:
//   1) Firebase Console > Project settings > Service accounts > "Generate new private key"
//      (downloads serviceAccountKey.json). Do NOT commit this file!
//   2) cd android/scripts && npm install
//   3) node seed-demo.mjs --service-account /path/to/serviceAccountKey.json
//      (or: GOOGLE_APPLICATION_CREDENTIALS=/path/to/serviceAccountKey.json npm run seed:demo)
//
// Requires Node.js 18+. Uses the Admin SDK, which bypasses security rules.

import { readFileSync } from 'node:fs';

const DEMO_PASSWORD = 'demo12345';
const DEMO_MOSQUE_ID = 'demo-mosque-0001';
const FUNDS = [
  {
    "code": "GEN",
    "name": "সাধারণ",
    "nameEn": "General"
  },
  {
    "code": "CHD",
    "name": "চাঁদা",
    "nameEn": "Subscription"
  },
  {
    "code": "CON",
    "name": "নির্মাণ",
    "nameEn": "Construction"
  },
  {
    "code": "WAK",
    "name": "দান ও কল্যাণ",
    "nameEn": "Donation & welfare"
  },
  {
    "code": "UTL",
    "name": "ইউটিলিটি",
    "nameEn": "Utility"
  }
];
const APP_NAME_BN = "মসজিদ হিসাব";

function argValue(flag) {
  const i = process.argv.indexOf(flag);
  return i >= 0 ? process.argv[i + 1] : null;
}

if (!process.argv.includes('--unsafe-demo-only')) {
  console.error('This script creates a PUBLICLY KNOWN superadmin password. Use ONLY in an isolated demo Firebase project.');
  console.error('Pass --unsafe-demo-only to acknowledge the risk. Never run this against production.');
  process.exit(1);
}

const saPath = argValue('--service-account') || process.env.GOOGLE_APPLICATION_CREDENTIALS;
if (!saPath) {
  console.error('ERROR: pass --service-account <path> or set GOOGLE_APPLICATION_CREDENTIALS');
  console.error('(run "npm install" in this folder first if firebase-admin is missing)');
  process.exit(1);
}

const { default: admin } = await import('firebase-admin');
admin.initializeApp({ credential: admin.credential.cert(JSON.parse(readFileSync(saPath, 'utf8'))) });
const auth = admin.auth();
const db = admin.firestore();

async function ensureAuthUser(email, password, displayName) {
  try {
    const u = await auth.getUserByEmail(email);
    await auth.updateUser(u.uid, { password, displayName, disabled: false });
    console.log(`  auth user exists, password reset: ${email} (uid=${u.uid})`);
    return u.uid;
  } catch (e) {
    if (e.code === 'auth/user-not-found') {
      const u = await auth.createUser({ email, password, displayName, disabled: false });
      console.log(`  auth user created: ${email} (uid=${u.uid})`);
      return u.uid;
    }
    throw e;
  }
}

async function main() {
  console.log('Seeding demo data into project:', admin.app().options.projectId);

  // 1) Auth accounts
  const superUid = await ensureAuthUser('superadmin@demo.local', DEMO_PASSWORD, 'Demo Superadmin');
  const adminUid = await ensureAuthUser('admin@demo.local', DEMO_PASSWORD, 'Demo Mosque Admin');

  // 2) users/{uid} profiles (document id MUST equal the Auth uid)
  const now = Date.now();
  await db.collection('users').doc(superUid).set({
    name: 'Demo Superadmin', email: 'superadmin@demo.local', phone: '',
    role: 'SUPERADMIN', mosqueId: null, designation: '', active: true, createdAt: now,
  }, { merge: true });
  await db.collection('users').doc(adminUid).set({
    name: 'Demo Mosque Admin', email: 'admin@demo.local', phone: '',
    role: 'ADMIN', mosqueId: DEMO_MOSQUE_ID, designation: 'Admin', active: true, createdAt: now,
  }, { merge: true });
  console.log('  users/{uid} profiles written');

  // 3) demo mosque + starter funds
  await db.collection('mosques').doc(DEMO_MOSQUE_ID).set({
    name: APP_NAME_BN + ' (Demo)', nameEn: 'Demo Mosque',
    address: 'Dhaka, Bangladesh', district: 'Dhaka', upazila: '',
    phone: '', email: '', establishedYear: 2020, registrationNo: 'DEMO-0001',
    active: true, totalBalance: 0, fundCount: FUNDS.length, donorCount: 0, memberCount: 1,
    createdAt: now,
  }, { merge: true });
  for (const f of FUNDS) {
    await db.collection('mosques').doc(DEMO_MOSQUE_ID).collection('funds').doc(f.code).set({
      mosqueId: DEMO_MOSQUE_ID, code: f.code, name: f.name, nameEn: f.nameEn,
      balance: 0, active: true, description: '',
    }, { merge: true });
  }
  console.log(`  mosques/${DEMO_MOSQUE_ID} + ${FUNDS.length} funds written`);

  console.log('\nDONE. Demo login:');
  console.log('  superadmin@demo.local / ' + DEMO_PASSWORD + '  -> Platform console');
  console.log('  admin@demo.local      / ' + DEMO_PASSWORD + '  -> Mosque dashboard');
  console.log('Remember to deploy security rules: firebase deploy --only firestore:rules,firestore:indexes');
}

main().then(() => process.exit(0)).catch((e) => { console.error(e); process.exit(1); });
