import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildMigrationPlan } from './migrate-ledger.mjs';
const fixture = () => ({
  mosque:{id:'m',active:false,ledgerMigration:'frozen',totalBalance:85.25,fundCount:2,donorCount:1},
  funds:[{id:'a',mosqueId:'m',active:true,balance:70.25},{id:'b',mosqueId:'m',active:false,balance:15}],
  donors:[{id:'d',mosqueId:'m',active:true,totalDonated:100.25}],
  contributions:[{id:'c',mosqueId:'m',accountId:'a',donorId:'d',amount:100.25}],
  expenses:[{id:'e',mosqueId:'m',accountId:'a',amount:15}],
  transfers:[{id:'t',mosqueId:'m',fromAccountId:'a',toAccountId:'b',amount:15}],
});
const openings = () => ({openingFunds:{a:0,b:0},openingDonors:{d:0}});
test('plans exact poisha only for a fully reconciled snapshot',()=>{
  assert.deepEqual(buildMigrationPlan(fixture(),openings()),{totalBalancePoisha:8525,
    funds:[{id:'a',poisha:7025},{id:'b',poisha:1500}],donors:[{id:'d',poisha:10025}]});
});
test('requires explicit openings for every fund and donor',()=>{
  assert.throws(()=>buildMigrationPlan(fixture(),{openingFunds:{a:0},openingDonors:{d:0}}),/every funds/);
  assert.throws(()=>buildMigrationPlan(fixture(),{openingFunds:{a:0,b:0},openingDonors:{}}),/every donors/);
  assert.throws(()=>buildMigrationPlan(fixture(),{openingFunds:{a:0,b:0,c:0},openingDonors:{d:0}}),/every funds/);
});
test('does not silently repair discrepancies, float drift, missing tenant or activity',()=>{
  for (const change of [s=>s.funds[0].balance=71, s=>s.funds[0].balance=70.25000000000001,
    s=>s.expenses[0].mosqueId='other',s=>delete s.donors[0].active]) {
    const s=fixture(); change(s); assert.throws(()=>buildMigrationPlan(s,openings()));
  }
});
test('rejects mismatched previously written integer fields, allowing safe resume',()=>{
  const s=fixture(); s.funds[0].balancePoisha=7025; assert.doesNotThrow(()=>buildMigrationPlan(s,openings()));
  s.funds[0].balancePoisha=1; assert.throws(()=>buildMigrationPlan(s,openings()),/Reconciliation failed/);
});
