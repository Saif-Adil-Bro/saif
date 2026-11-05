import { test } from 'node:test';
import assert from 'node:assert/strict';
import { audit } from './audit-ledger.mjs';

const sample = () => ({
  mosque: { id: 'm', totalBalance: 85.25, fundCount: 2, donorCount: 1, memberCount: 1 },
  funds: [{ id: 'a', mosqueId: 'm', balance: 70.25 }, { id: 'b', mosqueId: 'm', balance: 15 }],
  donors: [{ id: 'd', mosqueId: 'm', totalDonated: 100.25 }], members: [{ id: 'u', mosqueId: 'm' }],
  contributions: [{ id: 'c', mosqueId: 'm', accountId: 'a', donorId: 'd', amount: 100.25 }],
  expenses: [{ id: 'e', mosqueId: 'm', accountId: 'a', amount: 15 }],
  transfers: [{ id: 't', mosqueId: 'm', fromAccountId: 'a', toAccountId: 'b', amount: 15 }],
});
test('checks valid contributions, expenses, transfers and counters', () => {
  assert.deepEqual(audit(sample()).issues, []);
});
test('reports stored aggregate, donor and counter mismatches without changing the input', () => {
  const input = sample(); input.funds[0].balance = 99; input.donors[0].totalDonated = 1; input.mosque.memberCount = 2;
  const before = JSON.stringify(input), codes = audit(input).issues.map(x => x.code);
  assert.ok(codes.includes('ledger_mismatch')); assert.ok(codes.includes('fund_total_mismatch'));
  assert.ok(codes.includes('counter_mismatch')); assert.equal(JSON.stringify(input), before);
});
test('marks invalid references and duplicate records as incomplete', () => {
  const input = sample(); input.transfers[0].toAccountId = 'missing'; input.contributions.push({ ...input.contributions[0] });
  const result = audit(input);
  assert.equal(result.complete, false);
  assert.ok(result.issues.some(x => x.code === 'missing_reference'));
  assert.ok(result.issues.some(x => x.code === 'invalid_or_duplicate_id'));
});
test('rejects fractional-poisha amounts and invalid negative stored balances', () => {
  const input = sample(); input.expenses[0].amount = 0.001; input.funds[0].balance = -1;
  const result = audit(input);
  assert.equal(result.complete, false);
  assert.ok(result.issues.some(x => x.code === 'invalid_amount_or_record'));
  assert.ok(result.issues.some(x => x.code === 'negative_stored_total'));
});
test('allows explicitly supplied opening balances', () => {
  const input = sample(); input.openingFunds = { a: 50 }; input.openingDonors = { d: 10 };
  input.funds[0].balance += 50; input.donors[0].totalDonated += 10; input.mosque.totalBalance += 50;
  assert.deepEqual(audit(input).issues, []);
});
test('requires all ledger collections, rather than silently auditing partial data', () => {
  const input = sample(); delete input.expenses; assert.throws(() => audit(input), /expenses/);
});

test('refuses to treat cross-tenant or missing-tenant records as a complete audit', () => {
  const input = sample(); input.funds[0].mosqueId = 'other'; delete input.contributions[0].mosqueId;
  const result = audit(input);
  assert.equal(result.complete, false);
  assert.equal(result.issues.filter(x => x.code === 'wrong_or_missing_tenant').length, 2);
});

test('detects noncanonical stored values and disagreement with optional integer fields', () => {
  const input = sample();
  input.funds[0].balance = 0.30000000000000004;
  input.funds[0].balancePoisha = 30;
  input.contributions[0].amountPoisha = 7;
  input.mosque.totalBalancePoisha = 1;
  const result = audit(input);
  assert.equal(result.complete, false);
  assert.ok(result.issues.some(x => x.code === 'noncanonical_money' && x.entity === 'funds'));
  assert.ok(result.issues.some(x => x.code === 'integer_taka_mismatch' && x.entity === 'contributions'));
  assert.ok(result.issues.some(x => x.code === 'integer_taka_mismatch' && x.entity === 'mosque'));
});
test('accepts consistent optional integer fields without requiring them for legacy data', () => {
  const input = sample();
  input.funds[0].balancePoisha = 7025; input.funds[1].balancePoisha = 1500;
  input.donors[0].totalDonatedPoisha = 10025; input.mosque.totalBalancePoisha = 8525;
  input.contributions[0].amountPoisha = 10025; input.expenses[0].amountPoisha = 1500;
  assert.deepEqual(audit(input).issues, []);
});
test('fails closed on tombstones and correction markers instead of certifying incomplete history', () => {
  for (const patch of [{voided:true}, {voided:null}, {voided:'false'}, {ledgerMutationId:'change'}]) {
    const input = sample(); Object.assign(input.expenses[0], patch);
    const result = audit(input);
    assert.equal(result.complete, false);
    assert.ok(result.issues.some(x => x.code === 'unsupported_correction_history' && x.id === 'e'));
  }
  const input = sample(); input.expenses[0].voided = false;
  assert.deepEqual(audit(input).issues, []);
});
