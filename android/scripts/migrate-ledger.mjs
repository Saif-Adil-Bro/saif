#!/usr/bin/env node
// WIP maintenance tool. Use only after rules/client rollout review and backup.
// Admin SDK bypasses rules; all other privileged writers must be stopped by the operator.
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { audit } from './audit-ledger.mjs';
const require = createRequire(import.meta.url);
const { cents } = require('../functions/ledger.js');
const ACK = '--i-stopped-all-privileged-writers-and-backed-up';
const collections = ['funds','donors','contributions','expenses','transfers'];
function validId(id) { return typeof id === 'string' && id.length > 0 && !id.includes('/'); }
function frozen(data) { return data?.active === false && data?.ledgerMigration === 'frozen' && typeof data?.ledgerMigrationWasActive === 'boolean' && data?.ledgerVersion !== 2; }

/** Opening values must be explicitly supplied for every fund/donor, including zero. */
export function buildMigrationPlan(snapshot, openings) {
  if (!openings || typeof openings !== 'object' || Array.isArray(openings)) throw Error('Openings are required');
  for (const [collection, key] of [['funds','openingFunds'],['donors','openingDonors']]) {
    const entries = openings[key];
    if (!entries || typeof entries !== 'object' || Array.isArray(entries)) throw Error(`Missing ${key}`);
    const ids = new Set(snapshot[collection].map(row => row.id));
    if (Object.keys(entries).length !== ids.size || [...ids].some(id => !Object.hasOwn(entries,id)))
      throw Error(`${key} must explicitly cover every ${collection} ID`);
    for (const row of snapshot[collection]) if (typeof row.active !== 'boolean') throw Error(`${collection}/${row.id} requires an explicit active flag`);
  }
  const result = audit({...snapshot, openingFunds:openings.openingFunds, openingDonors:openings.openingDonors});
  if (!result.complete || result.issues.length) throw Error(`Reconciliation failed: ${JSON.stringify(result.issues)}`);
  return {
    totalBalancePoisha:cents(snapshot.mosque.totalBalance),
    funds:snapshot.funds.map(row=>({id:row.id, poisha:cents(row.balance)})),
    donors:snapshot.donors.map(row=>({id:row.id, poisha:cents(row.totalDonated)})),
  };
}

/** Mark a tenant inactive under a server-only lock. Resume a previously frozen run. */
export async function beginMigration(db, mosqueId) {
  if (!validId(mosqueId)) throw Error('Invalid mosque ID');
  const mosque=db.doc(`mosques/${mosqueId}`);
  return db.runTransaction(async tx=>{
    const snapshot=await tx.get(mosque), data=snapshot.data();
    if (!snapshot.exists) throw Error('Mosque not found');
    if (frozen(data)) return;
    if (data.ledgerVersion === 2 || data.ledgerMigration || typeof data.active !== 'boolean') throw Error('Mosque cannot enter migration');
    tx.update(mosque,{active:false,ledgerMigration:'frozen',ledgerMigrationWasActive:data.active});
  });
}
async function readSnapshot(db, mosqueId) {
  const mosqueRef=db.doc(`mosques/${mosqueId}`), mosque=await mosqueRef.get();
  if (!mosque.exists || !frozen(mosque.data())) throw Error('Mosque is not frozen');
  const querySnapshots=await Promise.all(collections.map(k=>mosqueRef.collection(k).get()));
  const data={mosque:{...mosque.data(),id:mosqueId}}, refs={};
  collections.forEach((name,i)=>{
    refs[name]=querySnapshots[i].docs;
    data[name]=refs[name].map(s=>({...s.data(),id:s.id}));
  });
  return {data,refs,mosque};
}
/** Restartable, fail-closed migration. Never recomputes balances or repairs history. */
export async function applyMigration(db, mosqueId, openings) {
  if (!validId(mosqueId)) throw Error('Invalid mosque ID');
  const first=await readSnapshot(db,mosqueId), plan=buildMigrationPlan(first.data,openings);
  const mosqueRef=db.doc(`mosques/${mosqueId}`);
  for (const [kind, field, values] of [['funds','balancePoisha',plan.funds],['donors','totalDonatedPoisha',plan.donors]]) {
    const docs=new Map(first.refs[kind].map(s=>[s.id,s]));
    for (const {id,poisha} of values) {
      const initial=docs.get(id);
      if (initial.data()[field] === poisha) continue;
      await db.runTransaction(async tx=>{
        const parent=await tx.get(mosqueRef), current=await tx.get(initial.ref);
        if (!frozen(parent.data()) || !current.exists || !current.updateTime.isEqual(initial.updateTime)) throw Error('Tenant or record changed during migration');
        tx.update(initial.ref,{[field]:poisha});
      });
    }
  }
  const verify=await readSnapshot(db,mosqueId), verified=buildMigrationPlan(verify.data,openings);
  for (const row of verified.funds) if (verify.data.funds.find(d=>d.id===row.id).balancePoisha !== row.poisha) throw Error('Fund verification failed');
  for (const row of verified.donors) if (verify.data.donors.find(d=>d.id===row.id).totalDonatedPoisha !== row.poisha) throw Error('Donor verification failed');
  await db.runTransaction(async tx=>{
    const current=await tx.get(mosqueRef), data=current.data();
    if (!frozen(data) || !current.updateTime.isEqual(verify.mosque.updateTime) || cents(data.totalBalance) !== verified.totalBalancePoisha) throw Error('Mosque changed during migration');
    tx.update(mosqueRef,{totalBalancePoisha:verified.totalBalancePoisha,ledgerVersion:2,ledgerMigration:'complete',active:data.ledgerMigrationWasActive});
  });
  return {funds:verified.funds.length,donors:verified.donors.length};
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const args=process.argv.slice(2), command=args[0];
    const value=flag=>{const i=args.indexOf(flag); return i >= 0 ? args[i+1] : undefined;};
    const project=value('--project'), mosque=value('--mosque');
    if (!['begin','apply'].includes(command) || !project || !validId(mosque) || !args.includes(ACK))
      throw Error(`Usage: node migrate-ledger.mjs <begin|apply> --project PROJECT --mosque MOSQUE ${ACK} [--openings FILE]`);
    if (process.env.GCLOUD_PROJECT && process.env.GCLOUD_PROJECT !== project) throw Error('Project does not match GCLOUD_PROJECT');
    if (command === 'apply' && !value('--openings')) throw Error('Explicit opening balances file required');
    const fromFunctions=createRequire(new URL('../functions/package.json',import.meta.url));
    const {initializeApp,applicationDefault}=fromFunctions('firebase-admin/app');
    const {getFirestore}=fromFunctions('firebase-admin/firestore');
    const app=initializeApp({projectId:project,credential:applicationDefault()});
    const db=getFirestore(app);
    if (command === 'begin') { await beginMigration(db,mosque); console.log('Tenant frozen. Reconcile data and run apply.'); }
    else { const result=await applyMigration(db,mosque,JSON.parse(readFileSync(value('--openings'),'utf8'))); console.log(JSON.stringify(result)); }
  } catch (error) { console.error(error.message); process.exitCode=1; }
}
