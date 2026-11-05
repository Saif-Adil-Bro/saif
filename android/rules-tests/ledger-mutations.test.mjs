import {before,beforeEach,after,test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {initializeTestEnvironment,assertFails,assertSucceeds} from '@firebase/rules-unit-testing';
import {doc,getDoc,setDoc,updateDoc,writeBatch} from 'firebase/firestore';
let env;
before(async()=>{if(!process.env.FIRESTORE_EMULATOR_HOST) throw Error('Emulator required'); env=await initializeTestEnvironment({projectId:'demo-mosque-rules',firestore:{rules:readFileSync(new URL('./ledger-corrections-prototype.rules',import.meta.url),'utf8')}})});
beforeEach(async()=>{await env.clearFirestore();await env.withSecurityRulesDisabled(async ctx=>{
 const db=ctx.firestore(), b=writeBatch(db);
 const put=(path,data)=>b.set(doc(db,path),data);
 put('users/admin',{active:true,role:'ADMIN',mosqueId:'m'}); put('users/cashier',{active:true,role:'CASHIER',mosqueId:'m'}); put('users/member',{active:true,role:'MEMBER',mosqueId:'m'}); put('users/other',{active:true,role:'ADMIN',mosqueId:'other'});
 put('mosques/m',{active:true,ledgerVersion:2,totalBalance:100,totalBalancePoisha:10000});
 put('mosques/m/funds/a',{mosqueId:'m',active:true,balance:80,balancePoisha:8000});
 put('mosques/m/funds/b',{mosqueId:'m',active:true,balance:20,balancePoisha:2000});
 put('mosques/m/donors/d',{mosqueId:'m',active:true,name:'Donor',totalDonated:50,totalDonatedPoisha:5000});
 put('mosques/m/donors/e',{mosqueId:'m',active:true,name:'Other',totalDonated:0,totalDonatedPoisha:0});
 put('mosques/m/contributions/c',{mosqueId:'m',accountId:'a',donorId:'d',donorName:'Donor',amount:50,amountPoisha:5000,date:'2026-11-05',note:'',receiptNo:'',createdBy:'admin',createdAt:1});
 put('mosques/m/expenses/x',{mosqueId:'m',accountId:'a',title:'Rent',category:'',paidTo:'',amount:10,amountPoisha:1000,date:'2026-11-05',note:'',receiptNo:'',status:'approved',approvedBy:'',createdBy:'admin',createdAt:1});
 put('mosques/m/transfers/t',{mosqueId:'m',fromAccountId:'a',toAccountId:'b',amount:20,amountPoisha:2000,date:'2026-11-05',note:'',createdBy:'admin',createdAt:1});
 await b.commit();} )});
after(async()=>env?.cleanup());
const dbFor=uid=>env.authenticatedContext(uid).firestore();
const field=(n,id)=>({balance:n/100,balancePoisha:n,ledgerOpKind:'ledgerMutations',ledgerOpId:id});
const total=(n,id)=>({totalBalance:n/100,totalBalancePoisha:n,ledgerOpKind:'ledgerMutations',ledgerOpId:id});
const donor=(n,id)=>({totalDonated:n/100,totalDonatedPoisha:n,ledgerOpKind:'ledgerMutations',ledgerOpId:id});
async function mutate(db,kind,target,id,action,after,updates,override={}) {
 const ref=doc(db,`mosques/m/${kind}/${target}`), before=(await getDoc(ref)).data();
 const next={...before,...after,ledgerMutationId:id};
 const op={uid:db.app.options.testUid||'admin',kind,targetId:target,action,oldAmountPoisha:Math.round(before.amount*100),newAmountPoisha:action==='update'?Math.round(next.amount*100):0,before,after:next,...override};
 const b=writeBatch(db); b.set(doc(db,`mosques/m/ledgerMutations/${id}`),op); b.update(ref,next);
 for (const [path,fields] of updates) b.update(doc(db,`mosques/m${path}`),fields);
 return b.commit();
}
test('edit contribution moves both fund and donor totals atomically',async()=>{
 const db=dbFor('admin');
 await assertSucceeds(mutate(db,'contributions','c','op','update',{accountId:'b',donorId:'e',donorName:'Other',amount:40,amountPoisha:4000},[
  ['/funds/a',field(3000,'op')],['/funds/b',field(6000,'op')],['/donors/d',donor(0,'op')],['/donors/e',donor(4000,'op')],['',total(9000,'op')]
 ]));
 assert.equal((await getDoc(doc(db,'mosques/m'))).data().totalBalancePoisha,9000);
 assert.equal((await getDoc(doc(db,'mosques/m/contributions/c'))).data().amountPoisha,4000);
});
test('delete expense retains a tombstone and restores money',async()=>{
 const db=dbFor('admin'); await assertSucceeds(mutate(db,'expenses','x','op','delete',{voided:true},[['/funds/a',field(9000,'op')],['',total(11000,'op')]]));
 assert.equal((await getDoc(doc(db,'mosques/m/expenses/x'))).data().voided,true);
 await assertFails(mutate(db,'expenses','x','again','delete',{voided:true},[['/funds/a',field(10000,'again')],['',total(12000,'again')]]));
});
test('delete transfer reverses both funds without changing mosque total',async()=>{
 const db=dbFor('admin'); await assertSucceeds(mutate(db,'transfers','t','op','delete',{voided:true},[['/funds/a',field(10000,'op')],['/funds/b',field(0,'op')]]));
 assert.equal((await getDoc(doc(db,'mosques/m'))).data().totalBalancePoisha,10000);
});
test('partial, stale, forged, unauthorized and overdrawn corrections fail',async()=>{
 const db=dbFor('admin');
 await assertFails(updateDoc(doc(db,'mosques/m/expenses/x'),{voided:true,ledgerMutationId:'alone'}));
 await assertFails(mutate(db,'expenses','x','partial','delete',{voided:true},[['/funds/a',field(9000,'partial')]]));
 await assertFails(mutate(db,'expenses','x','forged','update',{title:'Changed',amount:10,amountPoisha:1000},[['/funds/a',field(8000,'forged')]],{uid:'someone'}));
 await assertFails(mutate(dbFor('member'),'expenses','x','member','delete',{voided:true},[['/funds/a',field(9000,'member')],['',total(11000,'member')]],{uid:'member'}));
 await assertFails(mutate(dbFor('cashier'),'transfers','t','cashier','delete',{voided:true},[['/funds/a',field(10000,'cashier')],['/funds/b',field(0,'cashier')]],{uid:'cashier'}));
 await assertFails(mutate(db,'contributions','c','overdraw','delete',{voided:true},[['/funds/a',field(-100,'overdraw')],['/donors/d',donor(0,'overdraw')],['',total(5000,'overdraw')]]));
 assert.equal((await getDoc(doc(db,'mosques/m/funds/a'))).data().balancePoisha,8000);
});
test('replay, stale snapshots, cross-tenant actor and deleted record cannot mutate balances',async()=>{
 const db=dbFor('admin'); const ref=doc(db,'mosques/m/expenses/x'); const old=(await getDoc(ref)).data();
 await assertSucceeds(mutate(db,'expenses','x','first','update',{title:'Electricity'},[['/funds/a',field(8000,'first')]]));
 await assertFails(mutate(db,'expenses','x','second','update',{title:'Stale'},[['/funds/a',field(8000,'second')]],{before:old}));
 await assertFails(mutate(db,'expenses','x','first','update',{title:'Replay'},[['/funds/a',field(8000,'first')]]));
 await assertFails(mutate(dbFor('other'),'expenses','x','cross','delete',{voided:true},[['/funds/a',field(9000,'cross')],['',total(11000,'cross')]],{uid:'other'}));
 assert.equal((await getDoc(ref)).data().title,'Electricity');
});
test('reversing a transfer or contribution cannot spend money already used',async()=>{
 const db=dbFor('admin');
 await env.withSecurityRulesDisabled(async ctx => {
  const b=writeBatch(ctx.firestore());
  b.update(doc(ctx.firestore(),'mosques/m/funds/b'),{balance:5,balancePoisha:500});
  b.update(doc(ctx.firestore(),'mosques/m/funds/a'),{balance:30,balancePoisha:3000});
  b.update(doc(ctx.firestore(),'mosques/m'),{totalBalance:35,totalBalancePoisha:3500});
  await b.commit();
 });
 await assertFails(mutate(db,'transfers','t','tdelete','delete',{voided:true},[['/funds/a',field(5000,'tdelete')],['/funds/b',field(-1500,'tdelete')]]));
 await assertFails(mutate(db,'contributions','c','cdelete','delete',{voided:true},[['/funds/a',field(-2000,'cdelete')],['/donors/d',donor(0,'cdelete')],['',total(-1500,'cdelete')]]));
});
test('receipt cannot be forged without the exact linked record and totals',async()=>{
 const db=dbFor('admin'); const old=(await getDoc(doc(db,'mosques/m/expenses/x'))).data();
 await assertFails(setDoc(doc(db,'mosques/m/ledgerMutations/alone'),{uid:'admin',kind:'expenses',targetId:'x',action:'delete',oldAmountPoisha:1000,newAmountPoisha:0,before:old,after:{...old,voided:true,ledgerMutationId:'alone'}}));
 await assertFails(updateDoc(doc(db,'mosques/m/funds/a'),field(9000,'alone')));
});
test('allows correcting an archived old account but refuses assigning a new archived account',async()=>{
 const db=dbFor('admin');
 await env.withSecurityRulesDisabled(async ctx=>{
  const b=writeBatch(ctx.firestore()); b.update(doc(ctx.firestore(),'mosques/m/funds/a'),{active:false});
  b.update(doc(ctx.firestore(),'mosques/m/donors/d'),{active:false}); await b.commit();
 });
 await assertSucceeds(mutate(db,'contributions','c','archive-old','delete',{voided:true},[
   ['/funds/a',field(3000,'archive-old')],['/donors/d',donor(0,'archive-old')],['',total(5000,'archive-old')]
 ]));
 await assertSucceeds(mutate(db,'expenses','x','move','update',{accountId:'b'},[['/funds/a',field(4000,'move')],['/funds/b',field(1000,'move')]]));
 await assertFails(mutate(db,'expenses','x','archive-new','update',{accountId:'a'},[['/funds/a',field(3000,'archive-new')],['/funds/b',field(2000,'archive-new')]]));
});
test('malformed historical self-transfer cannot inflate a fund',async()=>{
 const db=dbFor('admin');
 await env.withSecurityRulesDisabled(async ctx=>updateDoc(doc(ctx.firestore(),'mosques/m/transfers/t'),{toAccountId:'a'}));
 await assertFails(mutate(db,'transfers','t','self','delete',{voided:true},[['/funds/a',field(10000,'self')]]));
 assert.equal((await getDoc(doc(db,'mosques/m/funds/a'))).data().balancePoisha,8000);
});
test('simultaneous corrections using the same snapshot cannot both commit',async()=>{
 const db=dbFor('admin'), target=doc(db,'mosques/m/expenses/x');
 const before=(await getDoc(target)).data();
 function attempt(id,title){
  const after={...before,title,ledgerMutationId:id}; const b=writeBatch(db);
  b.set(doc(db,`mosques/m/ledgerMutations/${id}`),{uid:'admin',kind:'expenses',targetId:'x',action:'update',oldAmountPoisha:1000,newAmountPoisha:1000,before,after});
  b.update(target,after); b.update(doc(db,'mosques/m/funds/a'),field(8000,id)); return b.commit();
 }
 const results=await Promise.allSettled([attempt('one','One'),attempt('two','Two')]);
 assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
 assert.equal(results.filter(r=>r.status==='rejected').length,1);
});
test('a linked receipt does not permit wrong but nonnegative aggregate deltas',async()=>{
 const db=dbFor('admin');
 await assertFails(mutate(db,'contributions','c','wrong','update',{amount:60,amountPoisha:6000},[
  ['/funds/a',field(9500,'wrong')],['/donors/d',donor(6000,'wrong')],['',total(11000,'wrong')]
 ]));
 assert.equal((await getDoc(doc(db,'mosques/m/funds/a'))).data().balancePoisha,8000);
});
