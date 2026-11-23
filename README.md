# API Portal

Partner-facing request submission and tracking, rebuilt from the reference portal. The workspace includes Sandbox and Production views, application and request lists, a four-step submission form, and a request detail view with Backstage status and partner-visible comments.

The UI pins Next.js 16.0.8 and React 19.2.1 as requested. The backend uses Spring Boot 3.5.8 and Java 21. Backstage owns approvals, provisioning, credential delivery, and request persistence. The Portal pulls current status on page entry and every 15 seconds while a tracking view is open.

## Run with Docker Desktop

From the directory containing `compose.yaml`, copy `.env.example` to `.env` if you do not already have one. Set `PORTAL_PASSWORD`, `PORTAL_SANDBOX_PASSWORD`, `BACKSTAGE_TOKEN`, `PORTAL_DATABASE_PASSWORD`, and `PORTAL_REGISTRATION_TOKEN`. Match the Backstage tokens to its configuration. Set `PORTAL_INVITATION_KEY` to a stable Base64-encoded 32-byte random key (generate with `openssl rand -base64 32`). Set `PORTAL_PUBLIC_URL` to the browser URL used in invitation emails, then run:

```sh
docker compose up --build -d frontend backend portal-database
docker compose ps
```

Open [the Portal](http://127.0.0.1:3000) and sign in as `partner` for Sandbox and Production, or `partner-sandbox` for Sandbox only. Their passwords are `PORTAL_PASSWORD` and `PORTAL_SANDBOX_PASSWORD` in `.env`, respectively. If port 3000 is in use, set `PORTAL_UI_PORT=3001` in `.env` and open port 3001. Docker builds both apps, so the host needs only Docker Desktop with Compose. The frontend is published on localhost port 3000. The Portal backend exposes port 8092 on localhost for Backstage registration calls. Portal accounts and memberships persist in its own PostgreSQL database in the `portal-database` volume, under schema `portal_accounts`. Use `BACKSTAGE_URL=http://host.docker.internal:8091` for the local Backstage service.

```sh
docker compose logs -f
docker compose down
```

The stop command preserves the Portal database. To use an existing Backstage, set `BACKSTAGE_URL` and its service token in `.env` and run `docker compose up --build -d frontend backend`. Use an address the backend container can reach, such as `http://host.docker.internal:8091` for a host-published service. The existing Backstage must implement the partner endpoints in [the contract](CONTRACT.md).

## Run locally

Use Node.js 24 or later, JDK 21, and Maven 3.9. Start the independent Backstage service and the Portal PostgreSQL database, then export the Portal settings from your local .env. Point `PORTAL_DATABASE_URL` at a reachable PostgreSQL database and `BACKSTAGE_URL` at the real Backstage backend.

Run `mvn spring-boot:run` from `backend/`. In a separate terminal, run `npm ci` and `npm run dev` from `frontend/`. Next.js defaults to the local backend at `127.0.0.1:8080`; set `PORTAL_BACKEND_URL` if needed. Docker builds restore all required dependencies automatically.

The retired standalone Backstage simulator has been removed from this project. Portal uses the independent Backstage service through the HTTP contracts. The checks under `dev/` use Node.js built-ins and the running services.

## Integration decisions

- [Backstage contract](CONTRACT.md) defines the proposed request creation and read endpoints. Confirm the live endpoint paths, status mapping, and API scope catalog with the Backstage team.
- Development sign-in supports the two configured demo users. Invited Project Managers sign in with their email and BCrypt-hashed password stored in the Portal database. Membership determines the organization for every Backstage request. The browser keeps Basic credentials in memory for the current page session. Partner SSO and session renewal remain follow-on work.
- Production uses a customer-managed public key, based on the reference. Backstage owns the delivery channel for issued credentials. The source description ends before naming that channel.
- The exact Next.js pin carries published security advisories. This build uses the Pages Router and is intended for local development. Review patched versions before deployment. See the [Next.js security advisory](https://nextjs.org/blog/security-update-2025-12-11).


## Partner invitation and activation

Backstage Checker approval registers the project manager using `POST /internal/v1/registrations`, then sends the returned invitation link using the internal email REST API. The project manager opens `/onboarding#token=...`, confirms their invited email and creates a password. After activation they sign in at `/login` using that email. Backstage-registered Project Managers can view and submit Sandbox and Production requests and open in Production on each sign-in.

The invitation lasts 24 hours. Pending accounts cannot sign in. Passwords use BCrypt. Tokens have 256 bits of randomness, are looked up by SHA-256 hash, and their replay copy is encrypted with AES-GCM. Keep `PORTAL_INVITATION_KEY` and the Portal database together across restarts. The token is removed from the page address after reading it.

Registrations are atomic and idempotent by reference. Identical pending registration retries return the same user, link, and expiry. Changed identity fields return 409. Once accepted, registration replay returns 409. Expired registrations return 410. Activation can acknowledge a repeated identical password submission after a lost response, while refusing attempts to replace the password.

This initial account flow supports one organization per email. A second registration reference for an existing email returns 409, including cross-organization attempts. Expired invitations require administrator follow-up. Reissue, account recovery, and multi-organization enrollment need a separate explicit workflow.

Backstage checks invitation status before retrying an email and polls registration progress every 15 seconds. It records activation or expiry in Riwayat onboarding. For an invalid or expired cached link, the worker records a failure and awaits administrator follow-up. Email delivery still requires `EMAIL_API_URL` and any authentication configured in Backstage.

Run the Portal onboarding regression check against the live local Backstage connection:

```sh
node --env-file=.env dev/onboarding-smoke.mjs
```

The check creates unique fictional accounts and a Sandbox request, exercises registration retries, activation, Production access and organization isolation. Integration testing used a temporary email fixture on the private Docker network.

## Local first-login simulation

New users registered from Backstage can sign in at http://localhost:3000/login with their project manager email and the initial sample password `Portal123!`. The login screen requires a new password of at least 12 characters. After saving it, sign in again with the new password to open the dashboard. Accounts awaiting this change can access only the account status/password endpoints. Request and dashboard APIs deny access until the password is replaced.

The current copied sample emails are `manager@example.test` and `projectmanager@example.test`. Existing active accounts keep their passwords and memberships. Repeated Backstage emails map to a single Portal account. For this simulation, invitations already pending in Portal also receive first-login setup when they have no password.

The initial password can be configured through `PORTAL_INITIAL_PASSWORD` on the backend. The UI currently displays the sample default above. This shared initial credential is for the local simulation. Portal stores hashes and persists the password-change state in its own database.

`GET /api/account/status` reports whether password change is required. `POST /api/account/password` accepts JSON `{"password":"<new password>"}`, requires authenticated first-login credentials and `X-Portal-Request: 1`, activates the account and consumes its pending invitation. It cannot reset an already active account.

Run `node dev/first-login-smoke.mjs` against the running Docker stack to verify default-password restrictions, new-password activation and organization scope using a separate fictional account. The script reads service configuration from the local .env without printing secrets.


## Successful Portal login status

After an active invited user signs in, the Portal queues a successful-login event for Backstage. A first-time account must finish password creation and sign in with its new password before this event is recorded. Regular workspace refreshes do not create login events.

The Portal stores events in `portal_accounts.login_outbox` and retries failed delivery, retaining the same event ID and payload across retries and restarts. Backstage receives `POST /integration/v1/portal-login-events` using the existing service bearer token. Its receiver owns verification, duplicate handling and the Riwayat entry containing the email and login time. See the [integration contract](CONTRACT.md).


## Login page samples

Set `PORTAL_DEMO_ACCOUNTS_ENABLED=true` for the local sample account picker. Selecting a card fills the credentials, and the user then presses Sign in. `sample-production` uses `DemoProduction123!` and can switch to Production. `sample-sandbox` uses `DemoSandbox123!` and has Sandbox access. These demo users share the configured demo organization and are excluded from invited-account login notifications. The default setting is false, which disables the sample logins and returns an empty picker list.


## Password reset

An internal Backstage Maker requests the reset and a Checker approves it. Backstage then asks the Portal to issue a one-hour reset link and sends it to the registered address using the email REST API. The recipient opens the link, enters a new password twice and returns to sign-in. The reset page includes the public menu and footer.

The Portal verifies the approved request's registration, organization and email. It retains the existing password until the link is completed. Completing a link replaces the password and expires other outstanding links for that account. Used and expired links display an error and direct the user to sign-in or administrator follow-up. Backstage can read reset progress to show completion in Riwayat.

Run `node dev/password-reset-smoke.mjs` against the local Docker stack to check service authentication, identity matching, issuance replay, password replacement, concurrent token use, expiry and organization permissions. This check creates unique fictional accounts and expires only its own test reset record. It uses the Portal database and sends no email.


## Registration source and Production default

Persisted users have a server-owned `registration_source` of `BACKSTAGE` or `SELF_ONBOARDING`. Current internal registrations and existing registered accounts are tagged `BACKSTAGE`. Backstage-registered memberships receive Production access automatically. Startup aligns existing Backstage memberships to that policy, and new registrations receive it immediately.

The sign-in response supplies `registrationSource` and `defaultEnvironment`. Backstage users open in Production after each successful workspace sign-in and may switch environments afterward. Future self-onboarding users default to Sandbox. Self-onboarding account creation remains future work.
