// Test-only invitation/onboarding experiment. Never points at a live project.
import {before,beforeEach,after,test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {initializeTestEnvironment,assertFails,assertSucceeds} from '@firebase/rules-unit-testing';
import {Timestamp,doc,getDoc,setDoc,updateDoc,writeBatch,increment} from 'firebase/firestore';
let env;
before(async()=>{if(!process.env.FIRESTORE_EMULATOR_HOST) throw Error('Emulator required');
 env=await initializeTestEnvironment({projectId:'demo-mosque-rules',firestore:{rules:readFileSync(new URL('./member-invite-prototype.rules',import.meta.url),'utf8')}})});
beforeEach(async()=>{await env.clearFirestore();await env.withSecurityRulesDisabled(async ctx=>{
 const b=writeBatch(ctx.firestore());
 for(const [id,role,m] of [['admin','ADMIN','m'],['secretary','SECRETARY','m'],['cashier','CASHIER','m'],['other','ADMIN','other']])
  b.set(doc(ctx.firestore(),`users/${id}`),{active:true,role,mosqueId:m});
 for(const m of ['m','other']) b.set(doc(ctx.firestore(),`mosques/${m}`),{active:true,memberCount:1});
 await b.commit();
})});
after(async()=>{await env?.cleanup()});
const actor=(uid,email='')=>env.authenticatedContext(uid,email?{email,email_verified:true}:{}).firestore();
const invite=(email='member@example.test',role='MEMBER',creator='admin')=>({mosqueId:'m',email,name:'Member',phone:'',role,designation:'',createdBy:creator,status:'pending',expiresAt:Timestamp.fromMillis(Date.now()+86400000)});
const inviteRef=(db,id='code')=>doc(db,`mosques/m/invitations/${id}`);
const profile=(email='member@example.test',role='MEMBER',id='code')=>({name:'Member',email,phone:'',role,mosqueId:'m',designation:'',active:true,createdAt:1,inviteId:id});
function redeem(db,uid,email='member@example.test',id='code',role='MEMBER',mod={}){
 const b=writeBatch(db);
 b.set(doc(db,`users/${uid}`),{...profile(email,role,id),...mod});
 b.set(doc(db,`mosques/m/memberClaims/${email}`),{uid,inviteId:id});
 b.update(inviteRef(db,id),{status:'accepted',acceptedBy:uid});
 b.update(doc(db,'mosques/m'),{memberCount:increment(1),memberOpId:uid});
 return b.commit();
}
test('verified invite redemption atomically creates profile, claim and member count',async()=>{
 await assertSucceeds(setDoc(inviteRef(actor('admin')),invite()));
 const db=actor('member','member@example.test');
 await assertSucceeds(redeem(db,'member'));
 assert.equal((await getDoc(doc(db,'users/member'))).data().role,'MEMBER');
 assert.equal((await getDoc(doc(db,'mosques/m'))).data().memberCount,2);
 assert.equal((await getDoc(inviteRef(db))).data().status,'accepted');
 await assertFails(redeem(db,'member'));
 assert.equal((await getDoc(doc(db,'mosques/m'))).data().memberCount,2);
});
test('only authorized roles may invite; admins cannot be created by secretary',async()=>{
 await assertFails(setDoc(inviteRef(actor('cashier')),invite('member@example.test','MEMBER','cashier')));
 await assertFails(setDoc(inviteRef(actor('other')),invite('member@example.test','MEMBER','other')));
 await assertFails(setDoc(inviteRef(actor('secretary')),invite('member@example.test','ADMIN','secretary')));
 await assertFails(setDoc(inviteRef(actor('admin')),invite('member@example.test','SUPERADMIN')));
 await assertFails(setDoc(inviteRef(actor('admin')),invite('member@example.test','MEMBER','other')));
 await assertSucceeds(setDoc(inviteRef(actor('secretary')),invite('member@example.test','MEMBER','secretary')));
});
test('email verification, exact invited email and matching profile fields are mandatory',async()=>{
 await assertSucceeds(setDoc(inviteRef(actor('admin')),invite()));
 await assertFails(redeem(env.authenticatedContext('unverified',{email:'member@example.test',email_verified:false}).firestore(),'unverified'));
 await assertFails(redeem(actor('wrong','wrong@example.test'),'wrong'));
 await assertFails(redeem(actor('case','Member@example.test'),'case'));
 await assertFails(redeem(actor('attacker','member@example.test'),'attacker','member@example.test','code','ADMIN'));
 await assertFails(redeem(actor('attacker','member@example.test'),'attacker','member@example.test','code','MEMBER',{name:'Forged'}));
 await assertFails(setDoc(doc(actor('attacker','member@example.test'),'users/attacker'),profile()));
 await assertFails(updateDoc(doc(actor('attacker','member@example.test'),'mosques/m'),{memberCount:increment(1),memberOpId:'attacker'}));
});
test('expired, revoked or inactive-tenant invitations cannot be redeemed',async()=>{
 await env.withSecurityRulesDisabled(async ctx=>{
  await setDoc(inviteRef(ctx.firestore(),'expired'),{...invite(),expiresAt:Timestamp.fromMillis(Date.now()-1000)});
 });
 await assertFails(redeem(actor('member','member@example.test'),'member','member@example.test','expired'));
 await assertSucceeds(setDoc(inviteRef(actor('admin'),'revoked'),invite()));
 await assertSucceeds(updateDoc(inviteRef(actor('admin'),'revoked'),{status:'revoked'}));
 await assertFails(redeem(actor('member','member@example.test'),'member','member@example.test','revoked'));
 await assertSucceeds(setDoc(inviteRef(actor('admin'),'inactive'),invite()));
 await env.withSecurityRulesDisabled(ctx=>updateDoc(doc(ctx.firestore(),'mosques/m'),{active:false}));
 await assertFails(redeem(actor('member','member@example.test'),'member','member@example.test','inactive'));
});
test('concurrent invitations for the same email cannot create duplicate members',async()=>{
 const admin=actor('admin');
 await assertSucceeds(setDoc(inviteRef(admin,'first'),invite()));
 await assertSucceeds(setDoc(inviteRef(admin,'second'),invite()));
 const results=await Promise.allSettled([
  redeem(actor('one','member@example.test'),'one','member@example.test','first'),
  redeem(actor('two','member@example.test'),'two','member@example.test','second')]);
 assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
 assert.equal((await getDoc(doc(admin,'mosques/m'))).data().memberCount,2);
});
test('profile creation cannot reassign an existing user or change their role',async()=>{
 await assertSucceeds(setDoc(inviteRef(actor('admin')),invite()));
 await assertFails(redeem(actor('admin','member@example.test'),'admin'));
 assert.equal((await getDoc(doc(actor('admin'),'users/admin'))).data().role,'ADMIN');
});

test('permission changes invalidate outstanding invitations',async()=>{
 await assertSucceeds(setDoc(inviteRef(actor('admin')),invite()));
 await env.withSecurityRulesDisabled(ctx=>updateDoc(doc(ctx.firestore(),'users/admin'),{active:false}));
 await assertFails(redeem(actor('member','member@example.test'),'member'));
});
test('a forged privileged invitation cannot bypass issuer role validation',async()=>{
 await env.withSecurityRulesDisabled(ctx=>setDoc(inviteRef(ctx.firestore()),invite('member@example.test','SUPERADMIN','admin')));
 await assertFails(redeem(actor('member','member@example.test'),'member','member@example.test','code','SUPERADMIN'));
});
test('invitation details are private and creation validates expiration and normalized email',async()=>{
 const admin=actor('admin');
 await assertFails(setDoc(inviteRef(admin,'invalid-expiry'),{...invite(),expiresAt:Timestamp.fromMillis(Date.now()+8*86400000)}));
 await assertFails(setDoc(inviteRef(admin,'invalid-email'),invite('Member@example.test')));
 await assertSucceeds(setDoc(inviteRef(admin),invite()));
 await assertFails(getDoc(inviteRef(actor('wrong','wrong@example.test'))));
 await assertFails(getDoc(inviteRef(env.authenticatedContext('unverified',{email:'member@example.test',email_verified:false}).firestore())));
 await assertSucceeds(getDoc(inviteRef(actor('member','member@example.test'))));
});
test('missing claim, invite, profile or counter write cannot partially redeem',async()=>{
 await assertSucceeds(setDoc(inviteRef(actor('admin')),invite()));
 const db=actor('member','member@example.test');
 for(const missing of ['profile','claim','invite','counter']) {
  const b=writeBatch(db);
  if(missing!=='profile') b.set(doc(db,'users/member'),profile());
  if(missing!=='claim') b.set(doc(db,'mosques/m/memberClaims/member@example.test'),{uid:'member',inviteId:'code'});
  if(missing!=='invite') b.update(inviteRef(db),{status:'accepted',acceptedBy:'member'});
  if(missing!=='counter') b.update(doc(db,'mosques/m'),{memberCount:increment(1),memberOpId:'member'});
  await assertFails(b.commit());
 }
 await assertSucceeds(redeem(db,'member'));
});
