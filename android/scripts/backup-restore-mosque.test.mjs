import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  computeBackupDigest,
  verifyBackupAndReceiptChain,
  generateReconciliationWorksheet,
  exportMosqueBackup,
  restoreMosqueBackup,
} from './backup-restore-mosque.mjs';

function sampleV2Backup() {
  const beforeExpense = {
    mosqueId: 'm',
    accountId: 'a',
    title: 'Rent',
    category: '',
    paidTo: '',
    amount: 20,
    amountPoisha: 2000,
    date: '2026-11-05',
    note: '',
    receiptNo: '',
    status: 'approved',
    approvedBy: '',
    createdBy: 'admin',
    createdAt: 1,
  };
  const afterExpense = {
    ...beforeExpense,
    amount: 15,
    amountPoisha: 1500,
    ledgerMutationId: 'mut-1',
  };
  const beforeVoided = {
    mosqueId: 'm',
    accountId: 'a',
    title: 'Voided item',
    category: '',
    paidTo: '',
    amount: 10,
    amountPoisha: 1000,
    date: '2026-11-05',
    note: '',
    receiptNo: '',
    status: 'approved',
    approvedBy: '',
    createdBy: 'admin',
    createdAt: 2,
  };
  const afterVoided = {
    ...beforeVoided,
    voided: true,
    ledgerMutationId: 'mut-2',
  };
  const snapshot = {
    version: 1,
    mosque: {
      id: 'm',
      name: 'মসজিদ',
      active: true,
      ledgerVersion: 2,
      totalBalance: 85.25,
      totalBalancePoisha: 8525,
      fundCount: 2,
      donorCount: 1,
      memberCount: 1,
    },
    members: [{ id: 'admin', mosqueId: 'm', role: 'ADMIN', active: true }],
    funds: [
      { id: 'a', mosqueId: 'm', name: 'General', active: true, balance: 70.25, balancePoisha: 7025 },
      { id: 'b', mosqueId: 'm', name: 'Reserve', active: true, balance: 15, balancePoisha: 1500 },
    ],
    donors: [{ id: 'd', mosqueId: 'm', name: 'Donor', active: true, totalDonated: 100.25, totalDonatedPoisha: 10025 }],
    contributions: [
      { id: 'c', mosqueId: 'm', accountId: 'a', donorId: 'd', amount: 100.25, amountPoisha: 10025 },
    ],
    expenses: [
      { id: 'e', ...afterExpense },
      { id: 'v', ...afterVoided },
    ],
    transfers: [
      { id: 't', mosqueId: 'm', fromAccountId: 'a', toAccountId: 'b', amount: 15, amountPoisha: 1500 },
    ],
    ledgerMutations: [
      {
        id: 'mut-1',
        uid: 'admin',
        kind: 'expenses',
        targetId: 'e',
        action: 'update',
        oldAmountPoisha: 2000,
        newAmountPoisha: 1500,
        before: beforeExpense,
        after: afterExpense,
      },
      {
        id: 'mut-2',
        uid: 'admin',
        kind: 'expenses',
        targetId: 'v',
        action: 'delete',
        oldAmountPoisha: 1000,
        newAmountPoisha: 0,
        before: beforeVoided,
        after: afterVoided,
      },
    ],
    syncReceipts: [],
    invitations: [],
    memberClaims: [],
  };
  snapshot.sha256 = computeBackupDigest(snapshot);
  return snapshot;
}

test('verifies a complete v2 backup with mutation receipt chains, tombstones, and sha256 digest', () => {
  const backup = sampleV2Backup();
  const result = verifyBackupAndReceiptChain(backup);
  assert.equal(result.complete, true);
  assert.equal(result.mutationsVerified, 2);
  assert.deepEqual(result.issues, []);
});

test('detects tampered sha256, missing mutation receipts, broken chains, and forged after snapshots', () => {
  const tampered = sampleV2Backup();
  tampered.funds[0].balance = 999;
  const r1 = verifyBackupAndReceiptChain(tampered);
  assert.equal(r1.complete, false);
  assert.ok(r1.issues.some(i => i.code === 'checksum_mismatch'));

  const missingReceipt = sampleV2Backup();
  missingReceipt.ledgerMutations = [];
  delete missingReceipt.sha256;
  const r2 = verifyBackupAndReceiptChain(missingReceipt);
  assert.equal(r2.complete, false);
  assert.ok(r2.issues.some(i => i.code === 'missing_mutation_receipt'));

  const brokenChain = sampleV2Backup();
  brokenChain.ledgerMutations[0].before.ledgerMutationId = 'non-existent-prev';
  delete brokenChain.sha256;
  const r3 = verifyBackupAndReceiptChain(brokenChain);
  assert.equal(r3.complete, false);
  assert.ok(r3.issues.some(i => i.code === 'broken_mutation_chain'));
});

test('generates an accountant reconciliation worksheet with implied opening balances', () => {
  const backup = sampleV2Backup();
  backup.funds[0].balance = 120.25;
  backup.funds[0].balancePoisha = 12025;
  backup.mosque.totalBalance = 135.25;
  backup.mosque.totalBalancePoisha = 13525;
  delete backup.sha256;

  const sheet = generateReconciliationWorksheet(backup);
  assert.equal(sheet.openingsTemplate.openingFunds.a, 50);
  assert.equal(sheet.openingsTemplate.openingFunds.b, 0);
  assert.equal(sheet.openingsTemplate.openingDonors.d, 0);

  const verifiedWithOpenings = verifyBackupAndReceiptChain(backup, sheet.openingsTemplate);
  assert.equal(verifiedWithOpenings.complete, true);
});

test('exports and restores a tenant backup in-memory with fail-closed verification', async () => {
  const store = new Map();
  const fakeDb = {
    doc(path) {
      return {
        path,
        id: path.split('/').pop(),
        async get() {
          const data = store.get(path);
          return { exists: data !== undefined, id: path.split('/').pop(), data: () => data };
        },
        collection(sub) {
          const prefix = `${path}/${sub}/`;
          return {
            doc(id) {
              return fakeDb.doc(`${prefix}${id}`);
            },
            async get() {
              const docs = [];
              for (const [k, v] of store.entries()) {
                if (k.startsWith(prefix) && !k.slice(prefix.length).includes('/')) {
                  docs.push({ id: k.slice(prefix.length), data: () => v });
                }
              }
              return { docs };
            },
          };
        },
      };
    },
    collection(name) {
      return {
        where(field, op, val) {
          return {
            async get() {
              const prefix = `${name}/`;
              const docs = [];
              for (const [k, v] of store.entries()) {
                if (k.startsWith(prefix) && v[field] === val) {
                  docs.push({ id: k.slice(prefix.length), data: () => v });
                }
              }
              return { docs };
            },
          };
        },
      };
    },
    batch() {
      const ops = [];
      return {
        set(ref, data) {
          ops.push([ref.path, data]);
        },
        async commit() {
          for (const [k, v] of ops) store.set(k, v);
        },
      };
    },
  };

  const initial = sampleV2Backup();
  await restoreMosqueBackup(fakeDb, initial, { allowOverwrite: true });
  const exported = await exportMosqueBackup(fakeDb, 'm');
  assert.equal(verifyBackupAndReceiptChain(exported).complete, true);
  await assert.rejects(restoreMosqueBackup(fakeDb, exported, { allowOverwrite: false }), /already exists/);
});
