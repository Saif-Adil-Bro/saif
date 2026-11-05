'use strict';
// Isolated Auth/Firestore fixture for the non-deployable correction experiment.
if (process.env.FIRESTORE_EMULATOR_HOST !== '127.0.0.1:8080' ||
    process.env.FIREBASE_AUTH_EMULATOR_HOST !== '127.0.0.1:9099' || !process.env.GCLOUD_PROJECT) throw Error('Emulators required');
const {createRequire}=require('node:module');
const path=require('node:path');
const load=createRequire(path.resolve(__dirname,'../functions/package.json'));
const {initializeApp}=load('firebase-admin/app');
const {getFirestore}=load('firebase-admin/firestore');
const {getAuth}=load('firebase-admin/auth');
initializeApp({projectId:process.env.GCLOUD_PROJECT});
async function main(){
 const db=getFirestore(), auth=getAuth();
 await auth.createUser({uid:'correction-admin',email:'correction@example.test',password:'test-only-password'});
 await auth.createUser({uid:'correction-other',email:'correction-other@example.test',password:'test-only-password'});
 const b=db.batch(), put=(p,v)=>b.set(db.doc(p),v);
 put('users/correction-admin',{active:true,role:'ADMIN',mosqueId:'correction'});
 put('users/correction-other',{active:true,role:'ADMIN',mosqueId:'elsewhere'});
 put('mosques/correction',{active:true,ledgerVersion:2,totalBalance:100,totalBalancePoisha:10000});
 put('mosques/correction/funds/a',{mosqueId:'correction',active:true,balance:80,balancePoisha:8000});
 put('mosques/correction/funds/b',{mosqueId:'correction',active:true,balance:20,balancePoisha:2000});
 put('mosques/correction/donors/d',{mosqueId:'correction',active:true,name:'Donor',totalDonated:50,totalDonatedPoisha:5000});
 put('mosques/correction/donors/e',{mosqueId:'correction',active:true,name:'Other',totalDonated:0,totalDonatedPoisha:0});
 put('mosques/correction/contributions/c',{mosqueId:'correction',accountId:'a',donorId:'d',donorName:'Donor',amount:50,amountPoisha:5000,date:'2026-11-05',note:'',receiptNo:'',createdBy:'correction-admin',createdAt:1});
 for(const id of ['x','y']) put(`mosques/correction/expenses/${id}`,{mosqueId:'correction',accountId:'a',title:'Rent',category:'',paidTo:'',amount:10,amountPoisha:1000,date:'2026-11-05',note:'',receiptNo:'',status:'approved',approvedBy:'',createdBy:'correction-admin',createdAt:1});
 await b.commit();
}
main().catch(error=>{console.error(error);process.exitCode=1});
