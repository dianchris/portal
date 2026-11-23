# Portal and Backstage contract

This partner-facing API connects Portal to the independent Backstage service.

| Operation | Backstage endpoint |
| --- | --- |
| Create a request | `POST /partner/v1/organizations/{organizationId}/requests` |
| List requests in an environment | `GET /partner/v1/organizations/{organizationId}/requests?environment=SANDBOX` |
| Pull current request details and comments | `GET /partner/v1/organizations/{organizationId}/requests/{requestId}` |

The Spring service authenticates with a server-side bearer token. It derives the organization from the authenticated partner account. Backstage must enforce organization scope and return only comments intended for partners.

Creation accepts JSON with `applicationName`, `description`, `useCase`, `contactName`, `contactEmail`, optional `callbackUrl`, `environment`, `scopes`, `credentialMethod`, and an optional PEM `publicKey`. Use `SANDBOX` or `PRODUCTION`, and `CLIENT_SECRET` or `PUBLIC_KEY`. The provisional scopes are `ACCOUNTS_READ`, `PAYMENTS_CREATE`, and `PAYMENTS_READ`.

Creation carries an `Idempotency-Key` UUID. Backstage should persist the key alongside the request, replay the result for the same organization and payload, and respond with 409 if the key is reused for a different payload. The UI retains the key for retries of the same form payload.

Each record returns `id` (UUID), `applicationName`, `description`, `environment`, `scopes`, `credentialMethod`, `status`, `updatedAt` (ISO timestamp), and `comments`. Each comment has `author`, `createdAt`, and `text`. Backstage supplies one of `SUBMITTED`, `IN_REVIEW`, `CHANGES_REQUESTED`, `APPROVED`, `REJECTED`, or `ACTIVE`. The Portal renders those states as received.

The Portal exposes only create, list, and detail. Its response model allows the fields above and drops extra upstream fields, including any credential fields. The live Backstage endpoint must keep secrets out of all responses and free-text comments.


## Account registration and progress

Backstage calls `POST /internal/v1/registrations` on the Portal API port 8092 using bearer `PORTAL_REGISTRATION_TOKEN`. Its body contains `referenceId`, `organizationId`, `companyName`, `email`, and `name`. Backstage uses `partner-invitation-<partner-id>` as the reference and the project manager contact as the recipient. Successful registration returns `userId`, `status: "INVITED"`, `invitationUrl`, and `expiresAt`.

The same reference and normalized identity fields replay the exact result while the invitation is pending and valid. A changed identity, an already accepted reference, or an email with another registration returns 409. Expired references return 410. No automatic reissue occurs on retry.

Backstage can pull `GET /internal/v1/registrations/{referenceId}` with the same bearer authentication. It returns `userId`, `status` (`INVITED`, `ACTIVE`, or `EXPIRED`), `expiresAt`, and `acceptedAt`. It contains no invitation link. Backstage validates the returned user ID against its stored registration before emailing and recording lifecycle progress.

The browser posts its token to `/api/onboarding/inspect` and posts `token`, `email`, and `password` to `/api/onboarding/accept`. Both require JSON and `X-Portal-Request: 1`. The token is delivered in the URL fragment and never placed in a query string. Activation requires the invited email and a 12–64 character password fitting within BCrypt's 72-byte UTF-8 limit.

Activated users receive the `PROJECT_MANAGER` membership with Sandbox and Production submission rights. The client cannot choose its organization or grant Production access. Account activation and registration replay use database transactions and a database lock to keep concurrent identity and token operations consistent.


## Successful login notifications

After an active registered user signs in and resolves workspace access, the UI posts to `/api/account/login-success` with Basic credentials, `X-Portal-Request: 1`, and an `Idempotency-Key` UUID. The backend derives the user and organization from the authenticated membership, generates the timestamp and stores the event transactionally. Initial password setup cannot record a workspace login. Configuration demo accounts without a registration are excluded.

The Portal worker sends `POST /integration/v1/portal-login-events` to Backstage using its existing bearer `BACKSTAGE_TOKEN`. The JSON body contains `eventId`, `type: "PORTAL_LOGIN_SUCCEEDED"`, `referenceId`, `userId`, `organizationId`, `email`, and `occurredAt`. The same event ID is also sent as `Idempotency-Key`.

Backstage must verify the identity against the partner registration, deduplicate the event ID, and durably append the login timestamp and email to Riwayat before acknowledging with HTTP 2xx. The Portal retries failures with backoff up to five minutes between attempts and reuses the exact payload. A response lost after Backstage commits therefore requires receiver deduplication. Events survive Portal restarts. Repeated actual sign-ins create separate events. Out-of-order events must preserve the newest last-login timestamp.


## Approved password resets

Backstage calls `POST /internal/v1/password-resets` with the registration service bearer token after Checker approval. The body contains `referenceId` (`password-reset-<request UUID>`), `registrationReference` (`partner-invitation-<partner UUID>`), `organizationId`, and `email`. The Portal verifies the exact registration, organization and normalized email and requires an active account whose initial password setup is complete.

HTTP 200 returns `resetId`, `userId`, `status: "PENDING"`, `resetUrl`, `expiresAt`, and `email`. Links use `/reset-password#token=<opaque>` and expire after one hour. The Portal stores a token hash for lookup and an AES-GCM encrypted replay copy. A repeated identical pending request returns the exact original response. Changed identity fields and completed references return 409. Expired references return 410. A new approved reset request needs a new reference ID.

`GET /internal/v1/password-resets/{referenceId}` uses the same bearer token and returns `resetId`, `userId`, `status` (`PENDING`, `COMPLETED`, `EXPIRED`), `expiresAt`, and nullable `completedAt`. It excludes the link. Backstage should check this endpoint before sending or retrying email and record completion separately from email acceptance.

The browser posts `{token}` to `/api/password-resets/inspect` to obtain `companyName`, `email`, and `expiresAt`. It posts `{token,password}` to `/api/password-resets/complete`, which returns `{status:"COMPLETED"}`. Both use JSON and `X-Portal-Request: 1`. These token endpoints accept no user-selected organization or replacement email. Passwords must have 12–64 characters, fit within 72 UTF-8 bytes, and differ from the current and generic initial passwords.

Issuance leaves the current password usable. Completion atomically changes the BCrypt hash, marks the token completed, and expires every other unfinished reset for that user. Repeated completion returns 409 and superseded links return 410. Account activation, membership, role and Production permissions are preserved. Basic authentication checks the password on each request, so the old password stops working immediately. An open workspace returns to sign-in when its next request receives 401, and the reset page directs the user to sign in with the new password.


## Application onboarding state

`GET /api/requests/onboarding` returns `{hasApplications: boolean}` for the signed-in user's organization. It checks Sandbox and Production application lists through Backstage and exposes only the combined boolean. This permits a Sandbox-only user to see whether their organization has started application onboarding without exposing Production records or enabling Production access. Any existing application request, including one awaiting review, counts as having started. Upstream failures return an error rather than reporting an empty organization.

The dashboard shows the unfinished-onboarding prompt only after a successful check confirms both environments are empty. The call to action opens an application in the currently selected environment. The dashboard refreshes this state with its normal polling and reevaluates it after application submission.


## Registration source and initial environment

`portal_accounts.users.registration_source` persists `BACKSTAGE` or `SELF_ONBOARDING`. Existing persisted accounts came through internal registration and are tagged `BACKSTAGE`. The current trusted internal registration endpoint always writes `BACKSTAGE`; the browser cannot select this value. Self-onboarding account creation is future work.

`GET /api/requests/access` returns `registrationSource` and `defaultEnvironment`. Backstage-registered memberships receive Production access and default to Production at each sign-in. A future self-onboarding account defaults to Sandbox. New Backstage registrations enable Production immediately. Startup also enables it for existing Backstage-registered memberships. Self-onboarding memberships are excluded from that policy. Configured demo accounts use source `DEMO` in the response and default to the environment their demo permissions allow.

Users can switch environments after signing in. A fresh sign-in selects the server-defined default again. Application creation from the onboarding prompt uses the selected environment.
