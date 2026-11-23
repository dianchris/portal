import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';

const config = {};
for (const line of (await readFile(new URL('../.env', import.meta.url), 'utf8')).split(/\r?\n/)) {
  const match = /^([A-Z_]+)=(.*)$/.exec(line);
  if (match) config[match[1]] = match[2].replace(/^(['"])(.*)\1$/, '$2');
}
const ui = 'http://127.0.0.1:3000';
const backend = 'http://127.0.0.1:8092';
const id = randomUUID();
const email = 'first-login-' + id + '@example.test';
const initialPassword = 'Portal123!';
const password = 'NewDemoPassword-' + randomUUID();
const basic = value => 'Basic ' + Buffer.from(email + ':' + value).toString('base64');
async function call(origin, path, { auth, body, expected = 200 } = {}) {
  const response = await fetch(origin + path, {
    method: body ? 'POST' : 'GET',
    headers: { 'Content-Type': 'application/json', 'X-Portal-Request': '1', ...(auth && { Authorization: auth }) },
    body: body && JSON.stringify(body),
  });
  assert.equal(response.status, expected, path);
  return response.json().catch(() => ({}));
}
await call(backend, '/internal/v1/registrations', {
  auth: 'Bearer ' + config.PORTAL_REGISTRATION_TOKEN,
  body: { referenceId: 'first-login-' + id, organizationId: 'first-login-' + id,
    companyName: 'First login verification', email, name: 'Sample Project Manager' },
});
assert.equal((await call(ui, '/api/account/status', { auth: basic(initialPassword) })).passwordChangeRequired, true);
await call(ui, '/api/requests/access', { auth: basic(initialPassword), expected: 403 });
await call(ui, '/api/requests?environment=SANDBOX', { auth: basic(initialPassword), expected: 403 });
await call(ui, '/api/account/password', { auth: basic(initialPassword), body: { password: 'short' }, expected: 400 });
await call(ui, '/api/account/password', { auth: basic(initialPassword), body: { password } });
await call(ui, '/api/account/status', { auth: basic(initialPassword), expected: 401 });
assert.equal((await call(ui, '/api/account/status', { auth: basic(password) })).passwordChangeRequired, false);
const access = await call(ui, '/api/requests/access', { auth: basic(password) });
assert.equal(access.organizationId, 'first-login-' + id);
assert.deepEqual(await call(ui, '/api/requests?environment=SANDBOX', { auth: basic(password) }), []);
await call(ui, '/api/account/password', { auth: basic(password), body: { password: 'AnotherPassword123!' }, expected: 409 });
console.log('First-login checks passed: default password limited to password setup, dashboard blocked, password replaced, new sign-in scoped to its organization.');
