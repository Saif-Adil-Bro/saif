#!/usr/bin/env node
// Read-only audit of an explicitly exported tenant snapshot. Never writes Firestore.
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { cents, effects } = require('../functions/ledger.js');

export function audit(snapshot) {
  if (!snapshot || typeof snapshot !== 'object' || !snapshot.mosque || typeof snapshot.mosque !== 'object') {
    throw new Error('Expected a snapshot containing mosque, funds, donors, contributions, expenses and transfers');
  }
  const mosqueId = snapshot.mosque.id;
  if (typeof mosqueId !== 'string' || !mosqueId || mosqueId.includes('/')) throw new Error('Expected mosque.id');
  const issues = [];
  function problem(code, entity, id = '') { issues.push({ code, entity, id }); }
  function checkRepresentation(row, poishaField, value, n, entity, id = '') {
    if (value !== n / 100) { problem('noncanonical_money', entity, id); valid = false; }
    if (Object.hasOwn(row, poishaField) && (!Number.isSafeInteger(row[poishaField]) || row[poishaField] !== n)) {
      problem('integer_taka_mismatch', entity, id); valid = false;
    }
  }
  function list(key) {
    if (!Array.isArray(snapshot[key])) throw new Error(`Expected array: ${key}`);
    return snapshot[key];
  }
  const fundRows = list('funds'), donorRows = list('donors');
  const ledger = ['contributions', 'expenses', 'transfers'].map(kind => [kind, list(kind)]);
  const fundMap = new Map(), donorMap = new Map();
  let valid = true;
  function addRows(rows, map, kind) {
    for (const row of rows) {
      if (!row || typeof row.id !== 'string' || !row.id || row.id.includes('/') || map.has(row.id)) {
        problem('invalid_or_duplicate_id', kind, typeof row?.id === 'string' ? row.id : ''); valid = false; continue;
      }
      if (row.mosqueId !== mosqueId) { problem('wrong_or_missing_tenant', kind, row.id); valid = false; }
      map.set(row.id, row);
    }
  }
  addRows(fundRows, fundMap, 'funds'); addRows(donorRows, donorMap, 'donors');
  const fundExpected = new Map([...fundMap.keys()].map(id => [id, 0]));
  const donorExpected = new Map([...donorMap.keys()].map(id => [id, 0]));
  function applyOpening(name, target) {
    const openings = snapshot[name] ?? {};
    if (!openings || typeof openings !== 'object' || Array.isArray(openings)) throw new Error(`Expected object: ${name}`);
    for (const [id, value] of Object.entries(openings)) {
      if (!target.has(id)) { problem('unknown_opening_id', name, id); valid = false; continue; }
      try {
        const n = cents(value);
        if (n < 0) throw new Error('negative');
        target.set(id, n);
      } catch { problem('invalid_opening', name, id); valid = false; }
    }
  }
  applyOpening('openingFunds', fundExpected); applyOpening('openingDonors', donorExpected);
  function add(map, id, delta) {
    const next = map.get(id) + delta;
    if (!Number.isSafeInteger(next)) throw new Error('overflow');
    map.set(id, next);
  }
  for (const [kind, rows] of ledger) {
    const seen = new Set();
    for (const row of rows) {
      const id = typeof row?.id === 'string' ? row.id : '';
      if (!id || id.includes('/') || seen.has(id)) { problem('invalid_or_duplicate_id', kind, id); valid = false; continue; }
      seen.add(id);
      if (row.mosqueId !== mosqueId) { problem('wrong_or_missing_tenant', kind, id); valid = false; continue; }
      // Correction receipts/tombstones cannot be reconciled by the legacy audit.
      // Fail closed rather than reporting a misleading clean result or using it
      // to activate migration without validating the complete mutation history.
      if (Object.hasOwn(row, 'ledgerMutationId') || (Object.hasOwn(row, 'voided') && row.voided !== false)) {
        problem('unsupported_correction_history', kind, id); valid = false; continue;
      }
      try {
        const delta = effects(kind, null, row);
        const amountPoisha = cents(row.amount, true);
        checkRepresentation(row, 'amountPoisha', row.amount, amountPoisha, kind, id);
        if ([...delta.funds.keys()].some(k => !fundMap.has(k)) || [...delta.donors.keys()].some(k => !donorMap.has(k))) {
          problem('missing_reference', kind, id); valid = false; continue;
        }
        // Check all operations before mutating any aggregate.
        for (const [key, n] of delta.funds) if (!Number.isSafeInteger(fundExpected.get(key) + n)) throw new Error('overflow');
        for (const [key, n] of delta.donors) if (!Number.isSafeInteger(donorExpected.get(key) + n)) throw new Error('overflow');
        for (const [key, n] of delta.funds) add(fundExpected, key, n);
        for (const [key, n] of delta.donors) add(donorExpected, key, n);
      } catch { problem('invalid_amount_or_record', kind, id); valid = false; }
    }
  }
  let actualFundTotal = 0;
  function compare(rows, expected, field, entity) {
    for (const [id, row] of rows) {
      try {
        const actual = cents(row[field]);
        const poishaField = field === 'balance' ? 'balancePoisha' : 'totalDonatedPoisha';
        checkRepresentation(row, poishaField, row[field], actual, entity, id);
        if (actual < 0) problem('negative_stored_total', entity, id);
        if (valid && actual !== expected.get(id)) problem('ledger_mismatch', entity, id);
        if (entity === 'funds') {
          actualFundTotal += actual;
          if (!Number.isSafeInteger(actualFundTotal)) throw new Error('overflow');
        }
      } catch { problem('invalid_stored_total', entity, id); valid = false; }
    }
  }
  compare(fundMap, fundExpected, 'balance', 'funds');
  compare(donorMap, donorExpected, 'totalDonated', 'donors');
  try {
    const mosqueTotal = cents(snapshot.mosque.totalBalance);
    checkRepresentation(snapshot.mosque, 'totalBalancePoisha', snapshot.mosque.totalBalance, mosqueTotal, 'mosque');
    if (mosqueTotal < 0) problem('negative_stored_total', 'mosque');
    if (valid && mosqueTotal !== actualFundTotal) problem('fund_total_mismatch', 'mosque');
  } catch { problem('invalid_stored_total', 'mosque'); valid = false; }
  if (snapshot.members !== undefined) {
    if (!Array.isArray(snapshot.members)) throw new Error('Expected array: members');
    addRows(snapshot.members, new Map(), 'members');
  }
  for (const [field, rows] of [['fundCount', fundRows], ['donorCount', donorRows], ['memberCount', snapshot.members]]) {
    if (rows === undefined && field === 'memberCount') continue;
    if (!Array.isArray(rows) || !Number.isSafeInteger(snapshot.mosque[field]) || snapshot.mosque[field] !== rows.length) problem('counter_mismatch', 'mosque', field);
  }
  return {
    complete: valid,
    assumptions: ['Missing opening balances are assumed to be zero. A clean audit requires a complete, consistent snapshot and correct opening balances.'],
    issues,
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    if (process.argv.length !== 3) throw new Error('Usage: node audit-ledger.mjs <local-snapshot.json>');
    const result = audit(JSON.parse(readFileSync(process.argv[2], 'utf8')));
    console.log(JSON.stringify(result, null, 2));
    if (result.issues.length || !result.complete) process.exitCode = 1;
  } catch (error) { console.error(error.message); process.exitCode = 2; }
}
