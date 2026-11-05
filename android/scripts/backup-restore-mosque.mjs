#!/usr/bin/env node
// Full tenant backup, receipt-chain verification, accountant reconciliation worksheet,
// and fail-closed restore / rollback tool for Mosque Hisab.
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const require = createRequire(import.meta.url);
const { cents, effects } = require('../functions/ledger.js');

export const SUBCOLLECTIONS = [
  'funds',
  'donors',
  'contributions',
  'expenses',
  'transfers',
  'syncReceipts',
  'ledgerMutations',
  'invitations',
  'memberClaims',
];

const RESTORE_ACK = '--i-verified-backup-and-target-project';

function validId(id) {
  return typeof id === 'string' && id.length > 0 && id.length <= 1500 && !id.includes('/');
}

function canonicalize(value) {
  if (value === null || typeof value !== 'object') return value;
  if (typeof value.toDate === 'function' && Number.isInteger(value.seconds) && Number.isInteger(value.nanoseconds)) {
    return { __type: 'timestamp', seconds: value.seconds, nanoseconds: value.nanoseconds };
  }
  if (Array.isArray(value)) return value.map(canonicalize);
  const out = {};
  for (const key of Object.keys(value).sort()) {
    if (value[key] !== undefined) out[key] = canonicalize(value[key]);
  }
  return out;
}

export function computeBackupDigest(payload) {
  const copy = { ...payload };
  delete copy.sha256;
  return createHash('sha256').update(JSON.stringify(canonicalize(copy))).digest('hex');
}

/**
 * Verify a tenant snapshot including optional v2 `ledgerMutations` receipt chains,
 * tombstoned (`voided: true`) entries, opening balances, and aggregate poisha/taka totals.
 */
export function verifyBackupAndReceiptChain(snapshot, openings = {}) {
  if (!snapshot || typeof snapshot !== 'object' || !snapshot.mosque || typeof snapshot.mosque !== 'object') {
    throw new Error('Expected a snapshot containing mosque, funds, donors, contributions, expenses and transfers');
  }
  const mosqueId = snapshot.mosque.id;
  if (!validId(mosqueId)) throw new Error('Expected valid mosque.id');

  const issues = [];
  let valid = true;
  function problem(code, entity, id = '', detail = '') {
    issues.push(detail ? { code, entity, id, detail } : { code, entity, id });
    valid = false;
  }

  if (typeof snapshot.sha256 === 'string') {
    const expectedHash = computeBackupDigest(snapshot);
    if (snapshot.sha256 !== expectedHash) {
      problem('checksum_mismatch', 'backup', mosqueId);
    }
  }

  function list(key, required = true) {
    if (snapshot[key] === undefined && !required) return [];
    if (!Array.isArray(snapshot[key])) throw new Error(`Expected array: ${key}`);
    return snapshot[key];
  }

  const fundRows = list('funds');
  const donorRows = list('donors');
  const contributions = list('contributions');
  const expenses = list('expenses');
  const transfers = list('transfers');
  const mutations = list('ledgerMutations', false);
  const members = snapshot.members !== undefined ? list('members', false) : undefined;

  const isV2 = snapshot.mosque.ledgerVersion === 2;
  const fundMap = new Map();
  const donorMap = new Map();

  for (const [rows, map, kind] of [
    [fundRows, fundMap, 'funds'],
    [donorRows, donorMap, 'donors'],
  ]) {
    for (const row of rows) {
      if (!row || !validId(row.id) || map.has(row.id)) {
        problem('invalid_or_duplicate_id', kind, typeof row?.id === 'string' ? row.id : '');
        continue;
      }
      if (row.mosqueId !== mosqueId) problem('wrong_or_missing_tenant', kind, row.id);
      map.set(row.id, row);
    }
  }

  // Index and verify ledgerMutations receipt chain
  const mutationMap = new Map();
  const mutationsByTarget = new Map();
  for (const m of mutations) {
    if (!m || !validId(m.id) || mutationMap.has(m.id)) {
      problem('invalid_or_duplicate_id', 'ledgerMutations', typeof m?.id === 'string' ? m.id : '');
      continue;
    }
    if (!['contributions', 'expenses', 'transfers'].includes(m.kind) || !['update', 'delete'].includes(m.action) || !validId(m.targetId)) {
      problem('invalid_mutation_receipt', 'ledgerMutations', m.id);
      continue;
    }
    if (!m.before || typeof m.before !== 'object' || !m.after || typeof m.after !== 'object') {
      problem('invalid_mutation_receipt', 'ledgerMutations', m.id);
      continue;
    }
    if (m.after.ledgerMutationId !== m.id || m.before.mosqueId !== mosqueId || m.after.mosqueId !== mosqueId) {
      problem('invalid_mutation_receipt', 'ledgerMutations', m.id);
      continue;
    }
    mutationMap.set(m.id, m);
    const key = `${m.kind}/${m.targetId}`;
    if (!mutationsByTarget.has(key)) mutationsByTarget.set(key, []);
    mutationsByTarget.get(key).push(m);
  }

  // Verify linear chain per target without forks
  for (const [targetKey, listForTarget] of mutationsByTarget.entries()) {
    const prevIds = new Set();
    for (const m of listForTarget) {
      const prevId = m.before.ledgerMutationId ?? '';
      if (prevId && !mutationMap.has(prevId)) {
        problem('broken_mutation_chain', 'ledgerMutations', m.id, `Missing predecessor ${prevId} for ${targetKey}`);
      }
      if (prevIds.has(prevId)) {
        problem('forked_mutation_chain', 'ledgerMutations', m.id, `Duplicate predecessor ${prevId || 'initial'} for ${targetKey}`);
      }
      prevIds.add(prevId);
    }
  }

  const fundExpected = new Map([...fundMap.keys()].map(id => [id, 0]));
  const donorExpected = new Map([...donorMap.keys()].map(id => [id, 0]));

  const openingFunds = openings.openingFunds ?? snapshot.openingFunds ?? {};
  const openingDonors = openings.openingDonors ?? snapshot.openingDonors ?? {};
  for (const [name, source, target] of [
    ['openingFunds', openingFunds, fundExpected],
    ['openingDonors', openingDonors, donorExpected],
  ]) {
    if (!source || typeof source !== 'object' || Array.isArray(source)) throw new Error(`Expected object: ${name}`);
    for (const [id, val] of Object.entries(source)) {
      if (!target.has(id)) {
        problem('unknown_opening_id', name, id);
        continue;
      }
      try {
        const n = cents(val);
        if (n < 0) throw new Error('negative');
        target.set(id, n);
      } catch {
        problem('invalid_opening', name, id);
      }
    }
  }

  function checkRepresentation(row, poishaField, value, n, entity, id = '') {
    if (value !== n / 100) problem('noncanonical_money', entity, id);
    if (isV2 && !Object.hasOwn(row, poishaField)) problem('missing_v2_poisha_field', entity, id);
    if (Object.hasOwn(row, poishaField) && (!Number.isSafeInteger(row[poishaField]) || row[poishaField] !== n)) {
      problem('integer_taka_mismatch', entity, id);
    }
  }

  for (const [kind, rows] of [
    ['contributions', contributions],
    ['expenses', expenses],
    ['transfers', transfers],
  ]) {
    const seen = new Set();
    for (const row of rows) {
      const id = typeof row?.id === 'string' ? row.id : '';
      if (!validId(id) || seen.has(id)) {
        problem('invalid_or_duplicate_id', kind, id);
        continue;
      }
      seen.add(id);
      if (row.mosqueId !== mosqueId) {
        problem('wrong_or_missing_tenant', kind, id);
        continue;
      }

      const hasMutation = Object.hasOwn(row, 'ledgerMutationId') && row.ledgerMutationId !== '';
      const isVoided = Object.hasOwn(row, 'voided') && row.voided !== false;
      if (isVoided && row.voided !== true) {
        problem('invalid_voided_flag', kind, id);
        continue;
      }
      if ((hasMutation || isVoided) && !mutationMap.has(row.ledgerMutationId)) {
        problem('missing_mutation_receipt', kind, id);
        continue;
      }
      if (hasMutation) {
        const receipt = mutationMap.get(row.ledgerMutationId);
        if (receipt.kind !== kind || receipt.targetId !== id) {
          problem('mismatched_mutation_receipt', kind, id);
          continue;
        }
        const storedCopy = { ...row };
        delete storedCopy.id;
        if (JSON.stringify(canonicalize(receipt.after)) !== JSON.stringify(canonicalize(storedCopy))) {
          problem('receipt_after_mismatch', kind, id);
          continue;
        }
      }

      if (isVoided) {
        // Tombstoned records remain for audit trail and contribute 0 to active balances
        continue;
      }

      try {
        const delta = effects(kind, null, row);
        const amountPoisha = cents(row.amount, true);
        checkRepresentation(row, 'amountPoisha', row.amount, amountPoisha, kind, id);
        if ([...delta.funds.keys()].some(k => !fundMap.has(k)) || [...delta.donors.keys()].some(k => !donorMap.has(k))) {
          problem('missing_reference', kind, id);
          continue;
        }
        for (const [k, n] of delta.funds) fundExpected.set(k, fundExpected.get(k) + n);
        for (const [k, n] of delta.donors) donorExpected.set(k, donorExpected.get(k) + n);
      } catch {
        problem('invalid_amount_or_record', kind, id);
      }
    }
  }

  let actualFundTotal = 0;
  for (const [id, row] of fundMap) {
    try {
      const actual = cents(row.balance);
      checkRepresentation(row, 'balancePoisha', row.balance, actual, 'funds', id);
      if (actual < 0) problem('negative_stored_total', 'funds', id);
      if (valid && actual !== fundExpected.get(id)) problem('ledger_mismatch', 'funds', id);
      actualFundTotal += actual;
    } catch {
      problem('invalid_stored_total', 'funds', id);
    }
  }

  for (const [id, row] of donorMap) {
    try {
      const actual = cents(row.totalDonated);
      checkRepresentation(row, 'totalDonatedPoisha', row.totalDonated, actual, 'donors', id);
      if (actual < 0) problem('negative_stored_total', 'donors', id);
      if (valid && actual !== donorExpected.get(id)) problem('ledger_mismatch', 'donors', id);
    } catch {
      problem('invalid_stored_total', 'donors', id);
    }
  }

  try {
    const mosqueTotal = cents(snapshot.mosque.totalBalance);
    checkRepresentation(snapshot.mosque, 'totalBalancePoisha', snapshot.mosque.totalBalance, mosqueTotal, 'mosque', mosqueId);
    if (mosqueTotal < 0) problem('negative_stored_total', 'mosque', mosqueId);
    if (valid && mosqueTotal !== actualFundTotal) problem('fund_total_mismatch', 'mosque', mosqueId);
  } catch {
    problem('invalid_stored_total', 'mosque', mosqueId);
  }

  if (members !== undefined) {
    const seenMembers = new Set();
    for (const m of members) {
      if (!m || !validId(m.id) || seenMembers.has(m.id)) {
        problem('invalid_or_duplicate_id', 'members', typeof m?.id === 'string' ? m.id : '');
      }
      seenMembers.add(m?.id);
      if (m?.mosqueId !== mosqueId) problem('wrong_or_missing_tenant', 'members', m?.id ?? '');
    }
  }

  for (const [field, rows] of [
    ['fundCount', fundRows],
    ['donorCount', donorRows],
    ['memberCount', members],
  ]) {
    if (rows === undefined && field === 'memberCount') continue;
    if (!Array.isArray(rows) || !Number.isSafeInteger(snapshot.mosque[field]) || snapshot.mosque[field] !== rows.length) {
      problem('counter_mismatch', 'mosque', field);
    }
  }

  return {
    complete: valid && issues.length === 0,
    ledgerVersion: snapshot.mosque.ledgerVersion ?? 1,
    mutationsVerified: mutationMap.size,
    issues,
  };
}

/**
 * Build a reconciliation worksheet for the mosque accountant.
 * Computes the net ledger movement per fund and donor and the implied opening
 * balances required to match stored totals, so the accountant can verify and sign off.
 */
export function generateReconciliationWorksheet(snapshot) {
  if (!snapshot?.mosque?.id) throw new Error('Snapshot with mosque.id is required');
  const mosqueId = snapshot.mosque.id;
  const fundMovements = new Map(snapshot.funds.map(f => [f.id, 0]));
  const donorMovements = new Map(snapshot.donors.map(d => [d.id, 0]));

  for (const [kind, rows] of [
    ['contributions', snapshot.contributions ?? []],
    ['expenses', snapshot.expenses ?? []],
    ['transfers', snapshot.transfers ?? []],
  ]) {
    for (const row of rows) {
      if (row.voided === true) continue;
      const delta = effects(kind, null, row);
      for (const [k, n] of delta.funds) {
        if (fundMovements.has(k)) fundMovements.set(k, fundMovements.get(k) + n);
      }
      for (const [k, n] of delta.donors) {
        if (donorMovements.has(k)) donorMovements.set(k, donorMovements.get(k) + n);
      }
    }
  }

  const openingFunds = {};
  const fundDetails = snapshot.funds.map(f => {
    const storedPoisha = cents(f.balance);
    const movementPoisha = fundMovements.get(f.id) ?? 0;
    const impliedOpeningPoisha = storedPoisha - movementPoisha;
    openingFunds[f.id] = impliedOpeningPoisha / 100;
    return {
      id: f.id,
      name: f.name,
      active: f.active,
      storedBalanceTaka: storedPoisha / 100,
      netLedgerMovementTaka: movementPoisha / 100,
      impliedOpeningBalanceTaka: impliedOpeningPoisha / 100,
      requiresInvestigation: impliedOpeningPoisha < 0,
    };
  });

  const openingDonors = {};
  const donorDetails = snapshot.donors.map(d => {
    const storedPoisha = cents(d.totalDonated);
    const movementPoisha = donorMovements.get(d.id) ?? 0;
    const impliedOpeningPoisha = storedPoisha - movementPoisha;
    openingDonors[d.id] = impliedOpeningPoisha / 100;
    return {
      id: d.id,
      name: d.name,
      active: d.active,
      storedTotalDonatedTaka: storedPoisha / 100,
      netLedgerContributionsTaka: movementPoisha / 100,
      impliedOpeningDonatedTaka: impliedOpeningPoisha / 100,
      requiresInvestigation: impliedOpeningPoisha < 0,
    };
  });

  return {
    mosqueId,
    mosqueName: snapshot.mosque.name ?? '',
    generatedAt: new Date().toISOString(),
    openingsTemplate: { openingFunds, openingDonors },
    fundDetails,
    donorDetails,
    signOff: {
      accountantName: '',
      designation: '',
      verifiedDate: '',
      confirmedAllPrivilegedWritersStopped: false,
    },
  };
}

/** Export a complete tenant snapshot from Firestore with a SHA-256 integrity digest. */
export async function exportMosqueBackup(db, mosqueId) {
  if (!validId(mosqueId)) throw new Error('Invalid mosque ID');
  const mosqueRef = db.doc(`mosques/${mosqueId}`);
  const [mosqueSnap, membersSnap, ...subSnaps] = await Promise.all([
    mosqueRef.get(),
    db.collection('users').where('mosqueId', '==', mosqueId).get(),
    ...SUBCOLLECTIONS.map(name => mosqueRef.collection(name).get()),
  ]);
  if (!mosqueSnap.exists) throw new Error(`Mosque not found: ${mosqueId}`);

  const payload = {
    version: 1,
    exportedAt: new Date().toISOString(),
    mosque: canonicalize({ ...mosqueSnap.data(), id: mosqueSnap.id }),
    members: membersSnap.docs
      .map(d => canonicalize({ ...d.data(), id: d.id }))
      .sort((a, b) => a.id.localeCompare(b.id)),
  };
  SUBCOLLECTIONS.forEach((name, idx) => {
    payload[name] = subSnaps[idx].docs
      .map(d => canonicalize({ ...d.data(), id: d.id }))
      .sort((a, b) => a.id.localeCompare(b.id));
  });
  payload.sha256 = computeBackupDigest(payload);
  return payload;
}

function reviveTimestamps(value, TimestampCtor) {
  if (value === null || typeof value !== 'object') return value;
  if (value.__type === 'timestamp' && Number.isInteger(value.seconds) && Number.isInteger(value.nanoseconds)) {
    return TimestampCtor ? new TimestampCtor(value.seconds, value.nanoseconds) : value;
  }
  if (Array.isArray(value)) return value.map(v => reviveTimestamps(v, TimestampCtor));
  const out = {};
  for (const [k, v] of Object.entries(value)) out[k] = reviveTimestamps(v, TimestampCtor);
  return out;
}

/**
 * Restore a verified tenant backup (for disaster recovery or migration rollback).
 * Fails closed unless the backup passes integrity and receipt-chain verification.
 */
export async function restoreMosqueBackup(db, backup, options = {}) {
  const { openings = {}, TimestampCtor = null, allowOverwrite = false } = options;
  const verification = verifyBackupAndReceiptChain(backup, openings);
  if (!verification.complete || verification.issues.length > 0) {
    throw new Error(`Backup verification failed before restore: ${JSON.stringify(verification.issues)}`);
  }
  const mosqueId = backup.mosque.id;
  const mosqueRef = db.doc(`mosques/${mosqueId}`);
  const existing = await mosqueRef.get();
  if (existing.exists && !allowOverwrite) {
    throw new Error(`Target mosque ${mosqueId} already exists; pass allowOverwrite=true to overwrite/rollback`);
  }

  const operations = [];
  const mosqueData = { ...reviveTimestamps(backup.mosque, TimestampCtor) };
  delete mosqueData.id;
  operations.push({ ref: mosqueRef, data: mosqueData });

  for (const member of backup.members ?? []) {
    const data = { ...reviveTimestamps(member, TimestampCtor) };
    const id = data.id;
    delete data.id;
    operations.push({ ref: db.doc(`users/${id}`), data });
  }

  for (const col of SUBCOLLECTIONS) {
    for (const row of backup[col] ?? []) {
      const data = { ...reviveTimestamps(row, TimestampCtor) };
      const id = data.id;
      delete data.id;
      operations.push({ ref: mosqueRef.collection(col).doc(id), data });
    }
  }

  // Commit in chunks of 400 to respect Firestore's 500-document batch limit
  for (let i = 0; i < operations.length; i += 400) {
    const batch = db.batch();
    for (const op of operations.slice(i, i + 400)) {
      batch.set(op.ref, op.data);
    }
    await batch.commit();
  }

  return {
    mosqueId,
    restoredDocuments: operations.length,
    ledgerVersion: backup.mosque.ledgerVersion ?? 1,
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const args = process.argv.slice(2);
    const command = args[0];
    const value = flag => {
      const i = args.indexOf(flag);
      return i >= 0 ? args[i + 1] : undefined;
    };
    if (command === 'verify') {
      const file = value('--file') ?? args[1];
      if (!file) throw new Error('Usage: node backup-restore-mosque.mjs verify --file <backup.json> [--openings <openings.json>]');
      const snapshot = JSON.parse(readFileSync(file, 'utf8'));
      const openings = value('--openings') ? JSON.parse(readFileSync(value('--openings'), 'utf8')) : {};
      const result = verifyBackupAndReceiptChain(snapshot, openings);
      console.log(JSON.stringify(result, null, 2));
      if (!result.complete) process.exitCode = 1;
    } else if (command === 'worksheet') {
      const file = value('--file') ?? args[1];
      const out = value('--out');
      if (!file) throw new Error('Usage: node backup-restore-mosque.mjs worksheet --file <backup.json> [--out <worksheet.json>]');
      const snapshot = JSON.parse(readFileSync(file, 'utf8'));
      const sheet = generateReconciliationWorksheet(snapshot);
      const formatted = JSON.stringify(sheet, null, 2);
      if (out) writeFileSync(out, formatted);
      else console.log(formatted);
    } else if (command === 'export' || command === 'restore') {
      const project = value('--project');
      const file = value('--file');
      if (!project || !file) {
        throw new Error(`Usage: node backup-restore-mosque.mjs ${command} --project <PROJECT> --file <FILE> [--mosque <ID>] [${RESTORE_ACK}]`);
      }
      if (process.env.GCLOUD_PROJECT && process.env.GCLOUD_PROJECT !== project) {
        throw new Error('Project does not match GCLOUD_PROJECT');
      }
      const fromFunctions = createRequire(new URL('../functions/package.json', import.meta.url));
      const { initializeApp, applicationDefault } = fromFunctions('firebase-admin/app');
      const { getFirestore, Timestamp } = fromFunctions('firebase-admin/firestore');
      const app = initializeApp({ projectId: project, credential: applicationDefault() });
      const db = getFirestore(app);
      if (command === 'export') {
        const mosque = value('--mosque');
        if (!validId(mosque)) throw new Error('Expected --mosque <ID>');
        const backup = await exportMosqueBackup(db, mosque);
        writeFileSync(file, JSON.stringify(backup, null, 2));
        console.log(JSON.stringify({ exported: mosque, sha256: backup.sha256 }));
      } else {
        if (!args.includes(RESTORE_ACK)) throw new Error(`Restore requires ${RESTORE_ACK}`);
        const backup = JSON.parse(readFileSync(file, 'utf8'));
        const openings = value('--openings') ? JSON.parse(readFileSync(value('--openings'), 'utf8')) : {};
        const res = await restoreMosqueBackup(db, backup, {
          openings,
          TimestampCtor: Timestamp,
          allowOverwrite: args.includes('--overwrite'),
        });
        console.log(JSON.stringify(res, null, 2));
      }
    } else {
      throw new Error('Commands: export | verify | worksheet | restore');
    }
  } catch (err) {
    console.error(err.message);
    process.exitCode = 1;
  }
}
