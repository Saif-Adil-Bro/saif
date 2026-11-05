// Regression tests for the differences in the rules pasted by the user.
// This runs only against the local Firestore emulator.
import {after,test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {initializeTestEnvironment,assertSucceeds,assertFails} from '@firebase/rules-unit-testing';
import {doc,setDoc,writeBatch,increment} from 'firebase/firestore';

if (!process.env.FIRESTORE_EMULATOR_HOST) throw Error('Firestore emulator required');
const original=readFileSync(new URL('../firestore.rules',import.meta.url),'utf8');
const exact="let op = getAfter(opPath(m, k, opId)).data;\n      return validId(opId) && k in ['contributions','expenses']";
const bad="let op = getAftexpenseser(opPath(m, k, opId)).data;\n      return validId(opId) && k in ['contributions','']";
assert.ok(original.includes(exact));
let env;
after(async()=>{await env?.cleanup();});
test('rules compiler rejects the pasted unknown getAftexpenseser function',async()=>{
 const broken=original.replace(exact,bad);
 await assert.rejects(initializeTestEnvironment({projectId:'demo-mosque-rules',firestore:{rules:broken}}));
});
test('with just the empty expense entry, fund creation remains allowed but expense is denied',async()=>{
 const broken=original.replace(exact,exact.replace("['contributions','expenses']","['contributions','']"));
 env=await initializeTestEnvironment({projectId:'demo-mosque-rules',firestore:{rules:broken}});
 await env.clearFirestore();
 await env.withSecurityRulesDisabled(async ctx=>{
  const db=ctx.firestore(),b=writeBatch(db);
  b.set(doc(db,'users/admin'),{active:true,role:'ADMIN',mosqueId:'m'});
  b.set(doc(db,'mosques/m'),{active:true,ledgerVersion:2,fundCount:0,totalBalance:100,totalBalancePoisha:10000});
  b.set(doc(db,'mosques/m/funds/main'),{active:true,mosqueId:'m',balance:100,balancePoisha:10000});
  await b.commit();
 });
 const db=env.authenticatedContext('admin').firestore();
 const b=writeBatch(db);
 b.set(doc(db,'mosques/m/funds/new'),{mosqueId:'m',name:'টেস্ট',code:'TST',nameEn:'Test',description:'Test fund',active:true,balance:0,balancePoisha:0,syncOpId:'new',syncOwner:'admin'});
 b.update(doc(db,'mosques/m'),{fundCount:increment(1),directoryOpId:'new',directoryOpKind:'funds'});
 await assertSucceeds(b.commit());
 const expense=writeBatch(db);
 expense.set(doc(db,'mosques/m/expenses/e'),{mosqueId:'m',amount:10,amountPoisha:1000,accountId:'main',title:'Expense',category:'',paidTo:'',date:'2026-11-05',note:'',receiptNo:'',status:'approved',approvedBy:'',createdBy:'admin',createdAt:1});
 expense.update(doc(db,'mosques/m/funds/main'),{balance:90,balancePoisha:9000,ledgerOpId:'e',ledgerOpKind:'expenses'});
 expense.update(doc(db,'mosques/m'),{totalBalance:90,totalBalancePoisha:9000,ledgerOpId:'e',ledgerOpKind:'expenses'});
 await assertFails(expense.commit());
});
