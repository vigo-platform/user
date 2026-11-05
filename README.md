# Vigo User service

Java 21, Spring Boot **4.1.2**, Maven wrapper 3.9.16 copied from API Gateway. Group `ir.vigoapp`, package `ir.vigoapp.user`; HTTP **8081**, gRPC **50051**, private management **8082**. No commits, remote or infrastructure provisioning is performed by this implementation.

## Approved scope / backlog mapping

| Ticket | Implementation | Verification |
|---|---|---|
| SCRUM-40 — Project skeleton, DB migrations, Docker Compose, health metrics | `pom.xml`, wrapper, `domain/`, `V1__users_coaches_outbox.sql`, Dockerfile, compose.yml, actuator/Prometheus | compile/package; PostgreSQL migration IT; static Compose validation |
| SCRUM-41 — Account registration for athletes and coaches | All eight `UserGrpcEndpoint` RPCs and `CredentialService`; gRPC CreateUser creates ATHLETE/ACTIVE; authenticated coach application creates PENDING without changing role | CredentialServiceTest, GrpcEndpointTest, uniqueness/CAS IT |
| SCRUM-42 — RBAC/security filter chain | `SecurityConfig`: actual RS256 validation, issuer/audience/UUID/role, authoritative DB status and role | JwtSecurityTest uses real signed RSA tokens, not mock JWT authentication |
| SCRUM-43 — Admin user list, vetting, suspension | UserController/ProfileService, locked state transitions, self/admin protections | ProfileServiceTest; secured HTTP tests |
| SCRUM-44 — Self profile and public coach profile | readonly email/phone, validation, self views, approved-active public view | ProfileServiceTest, validation and route-order HTTP tests |
| SCRUM-45 — Transactional outbox/Kafka user.events | OutboxWriter/OutboxRelay; aggregate sequences, stable IDs, head-of-line leasing/fencing, broker ACK before mark | rollback, retry/reclaim, concurrent worker, real Kafka IT |
| SCRUM-46 — Unit/integration/security review | `src/test/`, explicit integration profile; security decisions below | exact commands/results below; live infra limitations remain |

**No public registration or auth endpoint.** No login, password policy/hashing/verification, OTP, token issuance/refresh, plans, relations, media, or internal REST APIs. Password change/reset authorization belongs to Auth; User stores the complete encoded hash unchanged. Credential values are never part of REST DTOs, event payloads or application logging. Unknown request fields are rejected, including role/status/email/phone in profile updates.

### Supported HTTP operations

- `GET`, `PUT /api/v1/users/me` — authenticated ACTIVE account.
- `GET /api/v1/users`, `GET /api/v1/users/{user_id}`, `PATCH /api/v1/users/{user_id}/status` — ADMIN.
- `POST /api/v1/coaches/me/profile` — ATHLETE only, creates PENDING.
- `GET`, `PUT /api/v1/coaches/me` — applicant ATHLETE or COACH.
- `GET /api/v1/coaches?page=0&size=20` — public list of approved coaches with active `COACH` accounts, newest first. `page` starts at 0 and `size` accepts 1–100. The response contains `data.content`, `data.page`, `data.size`, `data.total_elements` and `data.total_pages`.
- `GET /api/v1/coaches/{coach_id}` — anonymous, APPROVED + ACTIVE + authoritative COACH only. Never returns review status/rejection reason.
- `GET /api/v1/admin/coaches`, `GET /api/v1/admin/coaches/{coach_id}`, `POST /api/v1/admin/coaches/{coach_id}/approve`, `POST .../reject` — ADMIN.
- Actuator runs on a separate management server. `MANAGEMENT_PORT` defaults to **8082** and `MANAGEMENT_ADDRESS` defaults to `127.0.0.1`. Compose binds the management server to `0.0.0.0` inside the container so Prometheus on the shared network can scrape `user:8082/actuator/prometheus`. Health shows the aggregate status. Readiness checks the readiness state, database, and gRPC server. Liveness checks the liveness state. Docker checks `/actuator/health` on the configured management port. The `logging: "true"` label enables the shared Vector collector.

Static `/me` matchers precede UUID routes. The coach list and individual public coach profiles are accessible without authentication via **GET**. Unimplemented routes/methods are denied by default. JSON uses snake_case and bilingual English/Persian envelopes; actuator endpoints intentionally use their standard formats. User/coach pagination is deterministic, size 1–100, page >=0. Approval/rejection only from PENDING; editing REJECTED re-submits to PENDING, approved edits apply immediately. `min_plan_price=null` and `active_athletes_count=0` are placeholders, not a plan/subscription backend.

## Contract decisions / intentional overrides

Read alongside sibling `api-contracts/rest/user.yaml`, `grpc/users.proto`, `events.md`, `docs/user-storage-scope.md` and `auth/docs/user-grpc.md`.

1. Each account has a positive BIGINT `id` used by **users-grpc-contract:3.1.0** as `user_id`, and a unique UUID `public_id` used by REST and as the Kafka key and aggregate ID.
2. User validates signed JWTs directly. Auth access tokens (`typ=at+jwt`, `token_use=access`) carry the numeric user ID in `sub` and a `roles` array. User also accepts the existing UUID subject and singular `role` format. Both formats resolve to the account's public UUID for REST operations. Privileges require a signed role that matches the current database role. Every protected request checks that the account exists and is ACTIVE.
3. Account/profile creation is synchronous and atomic through Auth→User CreateUser. User emits UserRegistered on user.events after creating the account/profile. Auth handles login and session revocation.
4. Canonical email is stripped and lowercased with Locale.ROOT. Phones are stripped **E.164**, consistently for registration/check/login; local `09...` input is rejected. REST's previous `09...` representation is superseded by E.164 nullable phone. Optional missing email is null (phone-only account allowed). REST phone is null until verified; registration sets email_verified=true only when email is supplied, and phone_verified=false. Approved D5 requires restricted Auth CreateUser to attest that the supplied email already passed OTP verification before signup; User does not verify OTP itself. There is no verification API in this scope. PostgreSQL lower-email unique index and phone unique index enforce race-safe uniqueness without CITEXT. Existence checks remain advisory.
5. Credential version starts at 1. A single conditional UPDATE swaps hash/version/timestamp only for the expected version; a lost race is gRPC ABORTED, missing account NOT_FOUND. `CREDENTIALS_MAX_AGE` is ISO-8601 duration (`P90D`, for example), defaults **PT0S = disabled**; expired is explicitly computed in every UserAuthInfo response, independently of account status. No password content transformation is performed.
6. REST exposes only ACTIVE/SUSPENDED account states; proto's pending/deactivated states are not created. Birth date/gender/avatar are profile fields; avatar/result-image references are validated opaque relative Storage URLs, not fetched or uploaded here.

## Build and tests

Prerequisites are JDK 21 (or newer targeting release 21) and Maven Central access. To download the published `users-grpc-contract:3.1.0`, configure GitHub Packages credentials under server ID `github-vigo-contracts` in `~/.m2/settings.xml`. A locally installed contract also works.

```sh
bash ./mvnw -B -ntp test
bash ./mvnw -B -ntp package
# Explicit integration suite; needs an already running Docker-compatible engine:
bash ./mvnw -B -ntp -Pintegration verify
```

On Windows, use `mvnw.cmd` from this directory. Unit and security tests use synthetic local RSA keys and in-process gRPC. Testcontainers integration tests use PostgreSQL and Kafka through the integration Maven profile and are skipped when Docker is unavailable. Never start a Docker daemon as part of tests.

With JDK 21, `bash ./mvnw -B -ntp -Pintegration clean verify` built the JAR and passed 27 unit and security tests. The six PostgreSQL and Kafka integration tests were skipped because Docker was unavailable.

## Docker build and publishing

The Docker build downloads the published gRPC contract from GitHub Packages. Configure the `github-vigo-contracts` server in your Maven settings and pass the file as a BuildKit secret.

```sh
docker build --secret id=maven_settings,src="$HOME/.m2/settings.xml" -t logtaha/vigo-user-service .
```

`docker compose build` uses `$HOME/.m2/settings.xml` by default. Set `MAVEN_SETTINGS_FILE` to use another settings file.

The `Docker Build & Push` workflow publishes to `logtaha/vigo-user-service` on Docker Hub. The Git tag `build-and-push` or a manual run with an empty tag publishes `sha-<short-sha>`. The Git tag `build-and-push-<version>` or a manual run with a tag publishes both that tag and `latest`.

Configure `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN` and `MAVEN_TOKEN` as GitHub Actions secrets. Set `MAVEN_USERNAME` as a secret or variable. Use a classic GitHub personal access token with `read:packages` for `MAVEN_TOKEN` and give its owner access to the contracts package.

## Configuration and local run

Spring does **not** automatically import `.env`. Copy `.env.example` to untracked `.env` and export values into the process/IDE, or let Compose pass them. Do not commit credentials, tokens or keys. Required: SPRING_DATASOURCE_URL/USERNAME/PASSWORD, KAFKA_BOOTSTRAP_SERVERS, AUTH_JWKS_URI, JWT_ISSUER, JWT_AUDIENCE. No defaults or validation bypass for issuer/audience/JWKS. Explicit local sample issuer/audience are in .env.example; configure the actual Auth issuer to match. Host process JWKS commonly uses `http://localhost:8080/.well-known/jwks.json`; Compose uses `http://auth:8080/...` and must match actual Auth listen configuration.

- Provision database **vigo_user_service** before startup; Flyway creates tables, Hibernate validates, open-in-view off, timestamps timestamptz. Dev may share the shared PostgreSQL credentials. Production should have a dedicated service login/schema owner and a separate migration identity, least-privilege SQL grants, encrypted connections/backups; do not share superuser credentials.
- Shared Kafka already provisioned by the parent project: host `localhost:19092`, containers `kafka:9092`, topic `user.events`. Compose joins external configurable `${APP_NETWORK_NAME:-vigo-app}`; it does not create the network or shared DB/Kafka.
- The image runs as the nonroot `vigo` user and checks health on the management port. Compose uses a read-only filesystem with a `/tmp` tmpfs, drops all capabilities, and publishes the HTTP and gRPC ports on loopback.
- Host process: export variables, set explicitly selected gRPC mode below, then `./mvnw spring-boot:run`. No app/services were started for this implementation beyond synthetic tests.
- Host logging uses the Auth terminal format and sends JSON to Vector at `localhost:5044`. Set `LOGGING_VECTOR_DESTINATION` to override the address or set it to an empty value for console-only output. The Docker image writes JSON to stdout for Vector's `logging=true` collection. Vector forwards both sources to Elasticsearch.

### gRPC transport / service authentication

Default is **fail closed**: without valid TLS certificate/key/trust and exact Auth URI identity, server startup fails; plaintext flag defaults false. All eight RPCs are Auth-only. Production uses grpc-netty-shaded TLS, REQUIRED client certificate, CA chain verification, certificate validity, and exact URI SAN `GRPC_AUTH_SERVICE_IDENTITY` (example `spiffe://vigo.local/service/auth`). No CN fallback, metadata/header trust, reflection endpoint or anonymous health RPC. Configure:

```
GRPC_TLS_CERTIFICATE=/app/.keys/user.crt
GRPC_TLS_PRIVATE_KEY=/app/.keys/user.key
GRPC_TLS_TRUST_CERTIFICATE=/app/.keys/service-ca.crt
GRPC_AUTH_SERVICE_IDENTITY=spiffe://vigo.local/service/auth
GRPC_BIND_ADDRESS=0.0.0.0
```

Provision certificates through your internal CA: server leaf needs DNS SAN matching the hostname Auth dials plus serverAuth EKU; Auth client leaf needs clientAuth EKU and the **exact URI SAN** above; use only an appropriately restricted service CA trust bundle. Store/mount PEM private keys read-only accessible to the nonroot `vigo` user. Never use Auth's JWT signing private key for service TLS. Renew/redeploy before expiry; no hot certificate reload is implemented. For a local disposable CA, OpenSSL `req -x509` plus `x509 -req -CA ... -CAkey ... -extfile ...` can issue leaves with SAN/EKU extensions, but production keys must come from managed service PKI. The service never generates keys automatically.

**Current Auth plaintext client is incompatible with production mTLS.** To exercise same-host dev only, set BOTH `SPRING_PROFILES_ACTIVE=dev` and `GRPC_DEV_PLAINTEXT_ENABLED=true`; outside dev, the flag fails startup. Prefer loopback `GRPC_BIND_ADDRESS=127.0.0.1` on host. Compose binds inside the container to 0.0.0.0 to support Auth containers, even though host port maps loopback. **Every peer on the shared Docker network can then read/update credentials**; this is an explicit insecure development exception, not a production security boundary. Do not enable on an untrusted network. Production requires upgrading Auth to mTLS client support before integration.

Server is Spring SmartLifecycle-managed with graceful 15-second shutdown and forced cleanup fallback. There is no custom unmanaged executor.

### First administrator bootstrap

No registration field, public admin creation route, or hardcoded account elevates privileges. Create a normal ATHLETE account through authorized Auth→User gRPC. An authorized database operator may perform a one-time controlled bootstrap, with application writes paused and the public UUID checked out of band:

```sql
BEGIN;
SELECT public_id, role, status FROM users WHERE public_id = :'verified_public_uuid' FOR UPDATE;
-- Operator verifies exactly the intended ACTIVE ATHLETE; bind the UUID in the SQL client.
UPDATE users SET role='ADMIN', updated_at=now(), version=version+1
 WHERE public_id=:'verified_public_uuid' AND role='ATHLETE' AND status='ACTIVE';
-- Verify exactly one affected row and record approval/audit evidence before COMMIT.
COMMIT;
```

This exceptional operational bootstrap bypasses the application outbox and must be audited/reconciled separately with Auth. Do not use it as routine role management. Existing token remains ATHLETE; obtain a fresh ADMIN token from Auth. There is no development JWT bypass or seeded password.

## Outbox and operational semantics

Each domain change and outbox row commits in one PostgreSQL transaction. Changes lock the user aggregate; each emitted event gets a unique positive monotonically increasing aggregate_version (two distinct values for approval then role change). Envelope exactly matches `api-contracts/events.md`: event_id, event_type, event_version1, occurred_at, producer user-service, aggregate_id public UUID, aggregate_version, data. Kafka key is that same UUID.

Relays claim only each aggregate's oldest unpublished head with `FOR UPDATE SKIP LOCKED`, including backoff/lease exclusion; later events cannot bypass an older leased/retrying row. Claims expire after60s; tokens fence stale workers. During send, a DB row lock is retained until the producer reports final broker outcome; reclaim skips locked heads, even after lease expiry. Terminal acknowledgement is awaited before marking published. Failure increments attempts and delays5s without recording sensitive error text. acks=all, idempotence enabled, one in-flight request per producer connection. Crash after ACK/before DB mark may redeliver the same stable event_id: **at-least-once, not exactly-once**. Consumers must deduplicate IDs and enforce aggregate versions, including historical duplicates. Do not change partition counts without an ordering migration plan.

Metrics: `user_outbox_backlog`, `user_outbox_lag_seconds`, `user_outbox_retries_total` (Micrometer Prometheus naming). No last_error payload/exception storage. Retain/monitor published rows operationally; no cleanup deletes unpublished events. The relay deliberately holds one DB connection/row lock while awaiting broker delivery: size the pool and monitor broker outages. Keep producer delivery timeout bounded. External Kafka ACL/TLS/SASL and broker topic durability are deployment requirements; shared dev plaintext broker is not production hardening.

## Bruno / gRPC tooling

In Bruno, use **Open Collection** and select `api-collection/service/`. The **User** collection contains 13 REST requests plus health and private Prometheus. Bruno 4.3.0 successfully parsed all 15 requests, the collection settings, and the sample environment. The response examples are synthetic fixtures.

For protected requests, copy `api-collection/service/.env.example` to an untracked `.env` in the collection directory and set `USER_BEARER_TOKEN`. Replace the sample UUIDs with real account IDs as needed. For Prometheus, use the private management endpoint on port 8082.

`GrpcEndpointTest` exercises all eight gRPC methods in process. To call them manually, point `grpcurl` at the sibling proto file.

```sh
# Authorized mTLS: add -cacert <service-ca.crt> -cert <auth-client.crt> -key <auth-client.key>.
# Dev ONLY: add -plaintext instead; never put real password hashes into saved shell history.
grpcurl -import-path ../api-contracts/grpc -proto users.proto -d '{"user_id":1}' localhost:50051 vigo.user.v1.UserService/GetUserById
```

Equivalent request fields: GetUserLoginInfoByEmail/CheckEmailExists `{email}`, GetUserLoginInfoByPhone/CheckPhoneExists `{phone}`, GetUserLoginInfoById/GetUserById `{user_id}`, CreateUser `{email?,phone?,first_name,last_name,password_hash}`, ChangeUserPassword `{user_id,new_password_hash,expected_credential_version}`. For credential mutations feed synthetic JSON on stdin with `-d @` from a protected temporary source rather than command history; never log real responses. GetUserById contains no hash; only three login RPCs return it to Auth.

## Remaining deployment dependencies

Auth must adopt contract3.1.0 UUID subjects, mTLS client support for production, user status/session behavior and event handling. Gateway routes are not changed here. Docker-dependent database/Kafka behavior and actual PKI handshakes require infrastructure verification; do not interpret skipped integration tests or a built jar as deployment approval. No git commit/push/remote or other repository modification was made by this service implementation.
