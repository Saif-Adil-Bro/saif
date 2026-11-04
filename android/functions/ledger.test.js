'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {cents, effects} = require('./ledger');
test('money rejects unsafe values and fractional poisha', () => {
  for (const value of [NaN, Infinity, -1, 0, 0.001, 9000000000001]) assert.throws(() => cents(value, true));
  assert.equal(cents(123.45, true), 12345);
});
test('same fund contribution edit uses only the delta and updates donors', () => {
  const old = {accountId:'a', donorId:'d1', amount:100};
  const next = {accountId:'a', donorId:'d2', amount:150};
  const result = effects('contributions', old, next);
  assert.equal(result.funds.get('a'), 5000);
  assert.equal(result.donors.get('d1'), -10000);
  assert.equal(result.donors.get('d2'), 15000);
  assert.equal(result.total, 5000);
});
test('same fund expense edit uses the delta', () => {
  const result = effects('expenses', {accountId:'a', amount:100}, {accountId:'a', amount:150});
  assert.equal(result.funds.get('a'), -5000);
  assert.equal(result.total, -5000);
});
test('transfer reversal and invalid accounts', () => {
  const row = {fromAccountId:'a', toAccountId:'b', amount:25};
  const result = effects('transfers', row, null);
  assert.equal(result.funds.get('a'), 2500);
  assert.equal(result.funds.get('b'), -2500);
  assert.equal(result.total, 0);
  assert.throws(() => effects('transfers', null, {...row, toAccountId:'a'}));
});
