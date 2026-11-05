import {before,beforeEach,after,test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {initializeTestEnvironment,assertFails,assertSucceeds} from '@firebase/rules-unit-testing';
import {doc,getDoc,setDoc,updateDoc,writeBatch} from 'firebase/firestore';
let env;
before(async()=>{if(!process.env.FIRESTORE_EMULATOR_HOST) throw Error('Emulator required'); env=await initializeTestEnvironment({projectId:'demo-mosque-rules',firestore:{rules:readFileSync(new URL('../firestore.rules',import.meta.url),'utf8')}})});
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
