import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFileSync } from 'node:fs';

const api = process.env.PORTAL_API_URL || 'http://127.0.0.1:8092';
const origin = process.env.PORTAL_UI_URL || 'http://127.0.0.1:3000';
const service = {Authorization: 'Bearer ' + process.env.PORTAL_REGISTRATION_TOKEN};
const suffix = randomUUID();
const input = {
  referenceId: 'portal-smoke-' + suffix, organizationId: 'portal-smoke-' + suffix,
  companyName: 'Onboarding test company', email: 'pm-' + suffix + '@example.test', name: 'Test Project Manager'
};
const password = 'Portal smoke account 123!';
const basic = email => ({Authorization: 'Basic ' + Buffer.from(email + ':' + password).toString('base64')});
async function call(base, path, expected, options = {}) {
  const response = await fetch(base + path, { ...options, signal: AbortSignal.timeout(10000) });
  const result = await response.json().catch(() => ({}));
  assert.equal(response.status, expected, JSON.stringify(result));
  return result;
}
const post = (body, headers = {}) => ({method: 'POST', headers: {'Content-Type': 'application/json', ...headers}, body: JSON.stringify(body)});
const register = (body = input, expected = 200) => call(api, '/internal/v1/registrations', expected, post(body, service));
await call(api, '/internal/v1/registrations', 401, post(input));
const [registration, replay] = await Promise.all([register(), register()]);
assert.deepEqual(registration, replay);
assert.equal(registration.status, 'INVITED');
assert(!('token' in registration));
await register({...input, name: 'Changed identity'}, 409);
await register({...input, referenceId: 'another-' + suffix, organizationId: 'other-org'}, 409);
await call(origin, '/api/requests/access', 401, {headers: basic(input.email)});
const token = new URLSearchParams(new URL(registration.invitationUrl).hash.slice(1)).get('token');
const header = {'X-Portal-Request': '1'};
const inspection = await call(origin, '/api/onboarding/inspect', 200, post({token}, header));
assert.equal(inspection.companyName, input.companyName);
await call(origin, '/api/onboarding/accept', 400, post({token, email: 'wrong@example.test', password}, header));
const accepted = {token, email: input.email, password};
await call(origin, '/api/onboarding/accept', 200, post(accepted, header));
await call(origin, '/api/onboarding/accept', 200, post(accepted, header));
await call(origin, '/api/onboarding/accept', 409, post({...accepted, password: 'A different password 123!'}, header));
await call(origin, '/api/onboarding/inspect', 409, post({token}, header));
await register(input, 409);
const progress = await call(api, '/internal/v1/registrations/' + input.referenceId, 200, {headers: service});
assert.equal(progress.status, 'ACTIVE');
assert(progress.acceptedAt);
assert(!('invitationUrl' in progress));
const access = await call(origin, '/api/requests/access', 200, {headers: basic(input.email)});
assert.equal(access.organizationId, input.organizationId);
assert.equal(access.role, 'PROJECT_MANAGER');
assert.equal(access.productionAccess, true);
assert.equal(access.registrationSource, 'BACKSTAGE');
assert.equal(access.defaultEnvironment, 'PRODUCTION');
await call(origin, '/api/requests?environment=PRODUCTION', 200, {headers: basic(input.email)});
const submission = {
  applicationName: 'Test manager Sandbox application', description: 'Onboarding verification', useCase: 'Verify organization scope',
  contactName: input.name, contactEmail: input.email, environment: 'SANDBOX', scopes: ['ACCOUNTS_READ'], credentialMethod: 'CLIENT_SECRET'
};
const createHeaders = {...header, ...basic(input.email), 'Idempotency-Key': randomUUID()};
const app = await call(origin, '/api/requests', 201, post(submission, createHeaders));
await call(origin, '/api/requests', 400, post({...submission, environment: 'PRODUCTION'}, createHeaders));
const other = {...input, referenceId: 'other-' + suffix, organizationId: 'other-' + suffix, email: 'other-' + suffix + '@example.test'};
const otherRegistration = await register(other);
const otherToken = new URLSearchParams(new URL(otherRegistration.invitationUrl).hash.slice(1)).get('token');
await call(origin, '/api/onboarding/accept', 200, post({token: otherToken, email: other.email, password}, header));
await call(origin, '/api/requests/' + app.id, 404, {headers: basic(other.email)});
assert.deepEqual(await call(origin, '/api/requests?environment=SANDBOX', 200, {headers: basic(other.email)}), []);
writeFileSync('/tmp/portal-onboarding-check.json', JSON.stringify({input, registration, appId: app.id}), {mode: 0o600});
console.log('Verified registration replay, identity conflicts, pending login, activation replay, Project Manager Sandbox submission, Production access, credential validation and organization isolation.');
