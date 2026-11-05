'use strict';

// Stored balances remain compatible with existing clients. All arithmetic uses
// integer poisha, and rejects non-finite, fractional-poisha and unsafe values.
function cents(value, positive = false) {
  if (typeof value !== 'number' || !Number.isFinite(value) || Math.abs(value) > 9000000000000) throw Error('Invalid amount');
  const n = Math.round(value * 100);
  if (!Number.isSafeInteger(n) || Math.abs(value * 100 - n) > 0.00001 || (positive && n <= 0)) throw Error('Invalid amount');
  return n;
}
function addDelta(map, id, amount) {
  if (!id || typeof id !== 'string' || id.includes('/')) throw Error('Invalid account');
  map.set(id, (map.get(id) || 0) + amount);
}
function effects(kind, old, next) {
  const funds = new Map(), donors = new Map();
  let total = 0;
  function apply(row, sign) {
    if (!row) return;
    const amount = cents(row.amount, true) * sign;
    if (kind === 'contributions') {
      addDelta(funds, row.accountId, amount);
      if (row.donorId) addDelta(donors, row.donorId, amount);
      total += amount;
    } else if (kind === 'expenses') {
      addDelta(funds, row.accountId, -amount);
      total -= amount;
    } else if (kind === 'transfers') {
      if (row.fromAccountId === row.toAccountId) throw Error('Accounts must differ');
      addDelta(funds, row.fromAccountId, -amount);
      addDelta(funds, row.toAccountId, amount);
    } else throw Error('Invalid transaction type');
  }
  apply(old, -1); apply(next, 1);
  return {funds, donors, total};
}
module.exports = { cents, effects };
