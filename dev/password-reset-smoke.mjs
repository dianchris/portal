import assert from 'node:assert/strict';
import {readFileSync,writeFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const root=fileURLToPath(new URL('..',import.meta.url));
const base=process.env.PORTAL_API_URL || 'http://127.0.0.1:8092';
const ui=process.env.PORTAL_UI_URL || 'http://127.0.0.1:3000';
const configuration=Object.fromEntries(readFileSync(root+'/.env','utf8').split(/\r?\n/)
  .filter(line=>line && !line.startsWith('#') && line.includes('=')).map(line=>[line.slice(0,line.indexOf('=')),line.slice(line.indexOf('=')+1)]));
const service={Authorization:'Bearer '+configuration.PORTAL_REGISTRATION_TOKEN};
const publicHeaders={'X-Portal-Request':'1'};
const id=randomUUID();
const organizationId='reset-smoke-'+id;
const registrationReference='partner-invitation-'+organizationId;
const email='reset-'+id+'@example.test';
const oldPassword='Original test password 123!';
const newPassword='Replacement test password 123!';
const token=result=>new URLSearchParams(new URL(result.resetUrl).hash.slice(1)).get('token');
const auth=password=>({Authorization:'Basic '+Buffer.from(email+':'+password).toString('base64')});
async function call(origin,path,expected,body,headers={}) {
  const response=await fetch(origin+path,{
    method:body?'POST':'GET',signal:AbortSignal.timeout(10000),
    headers:{'Content-Type':'application/json','X-Portal-Request':'1',...headers},body:body && JSON.stringify(body)
  });
  const result=await response.json().catch(()=>({}));
  assert.equal(response.status,expected,path+' '+JSON.stringify(result));
  return result;
}
const registration=await call(base,'/internal/v1/registrations',200,{
  referenceId:registrationReference,organizationId,companyName:'Password reset verification',email,name:'Reset Test Manager'
},service);
const body={referenceId:'password-reset-'+randomUUID(),registrationReference,organizationId,email};
await call(base,'/internal/v1/password-resets',401,body);
await call(base,'/internal/v1/password-resets',409,body,service);
await call(ui,'/api/account/password',200,{password:oldPassword},auth('Portal123!'));
const [first,replay]=await Promise.all([call(base,'/internal/v1/password-resets',200,body,service),
  call(base,'/internal/v1/password-resets',200,body,service)]);
assert.deepEqual(first,replay);
assert.equal(first.email,email);
assert.equal(first.userId,registration.userId);
assert.equal(first.status,'PENDING');
assert(Date.parse(first.expiresAt)>Date.now()+3500000);
await call(base,'/internal/v1/password-resets',409,{...body,email:'wrong@example.test'},service);
await call(base,'/internal/v1/password-resets',409,{...body,referenceId:'wrong-'+id,organizationId:'other-org'},service);
await call(base,'/internal/v1/password-resets',404,{...body,referenceId:'unknown-'+id,registrationReference:'missing-'+id},service);
await call(ui,'/api/account/status',200,undefined,auth(oldPassword));
await call(ui,'/api/password-resets/inspect',200,{token:token(first)},publicHeaders);
await call(ui,'/api/password-resets/inspect',404,{token:'a'.repeat(43)},publicHeaders);
await call(ui,'/api/password-resets/complete',400,{token:token(first),password:'short'},publicHeaders);
await call(ui,'/api/password-resets/complete',400,{token:token(first),password:'界'.repeat(30)},publicHeaders);
await call(ui,'/api/password-resets/complete',400,{token:token(first),password:oldPassword},publicHeaders);
await call(ui,'/api/account/status',200,undefined,auth(oldPassword));
const otherBody={...body,referenceId:'password-reset-'+randomUUID()};
const other=await call(base,'/internal/v1/password-resets',200,otherBody,service);
const competing=await Promise.all([1,2].map(()=>fetch(ui+'/api/password-resets/complete',{
  method:'POST',headers:{'Content-Type':'application/json',...publicHeaders},
  body:JSON.stringify({token:token(first),password:newPassword})
})));
assert.deepEqual(competing.map(result=>result.status).sort(),[200,409]);
await call(ui,'/api/password-resets/inspect',409,{token:token(first)},publicHeaders);
await call(ui,'/api/password-resets/inspect',410,{token:token(other)},publicHeaders);
await call(ui,'/api/password-resets/complete',410,{token:token(other),password:'Another replacement 123!'},publicHeaders);
await call(base,'/internal/v1/password-resets',409,body,service);
const complete=await call(base,'/internal/v1/password-resets/'+body.referenceId,200,undefined,service);
assert.equal(complete.status,'COMPLETED'); assert(complete.completedAt); assert(!('resetUrl' in complete));
assert.equal((await call(base,'/internal/v1/password-resets/'+otherBody.referenceId,200,undefined,service)).status,'EXPIRED');
await call(ui,'/api/account/status',401,undefined,auth(oldPassword));
assert.equal((await call(ui,'/api/account/status',200,undefined,auth(newPassword))).passwordChangeRequired,false);
const membership=await call(ui,'/api/requests/access',200,undefined,auth(newPassword));
assert.equal(membership.organizationId,organizationId);
assert.equal(membership.productionAccess,true);
assert.equal(membership.defaultEnvironment,'PRODUCTION');
const expiryBody={...body,referenceId:'expiry-reset-'+randomUUID()};
const expired=await call(base,'/internal/v1/password-resets',200,expiryBody,service);
execFileSync('docker',['compose','exec','-T','portal-database','psql','-U','portal','-d','portal','-v','ON_ERROR_STOP=1','-c',
  "UPDATE portal_accounts.password_resets SET expires_at=now()-interval '1 second' WHERE reference_id='"+expiryBody.referenceId+"'"],{cwd:root});
await call(base,'/internal/v1/password-resets',410,expiryBody,service);
await call(ui,'/api/password-resets/inspect',410,{token:token(expired)},publicHeaders);
await call(ui,'/api/password-resets/complete',410,{token:token(expired),password:'Another replacement 123!'},publicHeaders);
const pendingBody={...body,referenceId:'persist-reset-'+randomUUID()};
const pending=await call(base,'/internal/v1/password-resets',200,pendingBody,service);
writeFileSync('/tmp/portal-reset-verification.json',JSON.stringify({pendingBody,pending,email,newPassword,organizationId}),{mode:0o600});
console.log('Verified authenticated issuance, active identity checks, exact replay, unchanged password before use, one-time concurrent completion, superseded/expired links, new sign-in and preserved organization permissions.');
