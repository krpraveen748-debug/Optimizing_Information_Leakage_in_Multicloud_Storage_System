# Optimizing Information Leakage in Multicloud Storage System

## Abstract
CloudVault is an educational Java 21/Spring Boot implementation of secure multicloud chunk storage. It combines AES-256-GCM encryption, content-addressed deduplication, MinHash/Bloom/Jaccard similarity analytics, three simulated providers, placement strategies, an admin console, reports and activity logs.

## Problem statement
Storing repeated or highly similar data on separate providers can expose relationships and increase storage cost. This project studies these concerns with local simulated providers and measured, reproducible placement experiments.

## Objectives
- Preserve encrypted file confidentiality and integrity.
- Remove duplicate plaintext chunk objects.
- Compare provider fingerprint overlap.
- Compare round-robin, seeded-random and educational leakage-aware placement.
- Provide owner-scoped user operations and ADMIN-only global operations.
- Produce real database-backed analytics and CSV reports.

## Existing system and proposed system
The existing system is a conventional local/Spring storage prototype. The proposed system adds encrypted content addressing, deduplication metadata, similarity metadata, three independent provider directories, measured placement plans and operator dashboards.

## Architecture
Browser -> Spring Security/CSRF -> REST controllers -> transactional services -> JPA/JDBC -> MySQL or isolated H2 tests. Ciphertext and metadata are stored by provider; decryption occurs only after owner authorization.

## Technology
Java 21, Spring Boot 3.4, Spring Security, Spring Data JPA/JDBC, Maven, MySQL, Flyway, H2 tests, HTML/CSS/JavaScript, Bootstrap 5 and Chart.js.

## Directory structure
- `src/main/java/com/praveen/multicloud/controller` – REST endpoints
- `service` – encryption, chunking, similarity and analytics
- `entity` / `repository` – persistence model
- `security` – sessions, users and authorization
- `src/main/resources/db/migration` – additive Flyway migrations
- `src/main/resources/static` – dashboards and JavaScript
- `src/test/java` – unit and integration tests
- `storage/cloud1`, `storage/cloud2`, `storage/cloud3` – simulated providers

## Database and migrations
Hibernate uses `validate`; MySQL migrations are managed by Flyway. Migrations V1–V7 create and add existing project tables without destructive schema updates. V8 adds nullable per-file placement strategy and activity filename/status metadata; V9 adds expiring account-recovery tokens. Existing user, file and activity records are retained.

Do not run migrations against MySQL without approval. Test suites use isolated H2 databases and do not execute MySQL migration scripts.

## Installation
```bash
java -version
mvn -version
```
Java 21 and Maven are required. Create a MySQL database and separate application/migration accounts. Never place credentials in source control.

## Configuration
Set database credentials:
```bash
export DB_USERNAME=multicloud
export DB_PASSWORD='replace-with-local-secret'
export DB_MIGRATION_USERNAME=multicloud_migrator
export DB_MIGRATION_PASSWORD='replace-with-local-secret'
```

Configure encryption in the same terminal that starts Spring Boot. If this installation has
already encrypted files, restore the original key ring; do not generate a replacement. For a
new installation with no previously encrypted files, create a local key once and reuse it:
```bash
KEY_FILE="$HOME/.config/multicloud-storage/encryption-v1.key"
mkdir -p "$(dirname "$KEY_FILE")"
chmod 700 "$(dirname "$KEY_FILE")"
if [ ! -s "$KEY_FILE" ]; then
  (umask 077; openssl rand -base64 32 > "$KEY_FILE")
fi
export APP_ENCRYPTION_ACTIVE_KEY_ID=v1
export APP_ENCRYPTION_KEY_RING="v1=$(cat "$KEY_FILE")"
```
The ring format is `key-id=base64-key;older-id=base64-key`. Retain old keys until their ciphertext is no longer needed.
Keep the active key and key ring in the environment used to launch Spring Boot. Do not regenerate the key after storing files; retain old keys in the ring to decrypt existing data.

Password reset links require an SMTP account. Configure the mail server and public application
base URL in the same terminal that starts the application:
```bash
export MAIL_HOST=smtp.example.com
export MAIL_PORT=587
export MAIL_USERNAME='your-smtp-username'
export MAIL_PASSWORD='your-smtp-password'
export APP_RECOVERY_MAIL_FROM='no-reply@example.com'
export APP_RECOVERY_BASE_URL='https://your-application.example.com'
```
Do not put SMTP credentials in source control. Recovery links expire after 15 minutes by default,
are stored only as SHA-256 token hashes, and can be used once. Configure HTTPS for non-local
deployments. Forgot Email verifies the account username, registered email and password.

Optional provider directories:
```bash
export APP_STORAGE_CLOUD1="$HOME/multicloud-data/cloud1"
export APP_STORAGE_CLOUD2="$HOME/multicloud-data/cloud2"
export APP_STORAGE_CLOUD3="$HOME/multicloud-data/cloud3"
```
The three directories must be distinct and writable.

Similarity defaults:
- Permutations: 128
- Seed: 20261119
- Shingle size: 8 bytes
- Bloom bits: 1048576
- Bloom hash count: 7
- Leakage threshold: 0.75

Placement defaults:
- Overlap weight: 1.0
- Balance weight: 0.25
- Random seed: 20261119

## Running
After approving MySQL schema changes:
```bash
mvn clean test
mvn spring-boot:run
```
If the server is already running, stop it first and restart it from this configured terminal;
environment variables cannot be added to an already-running JVM.
Open `http://localhost:8080`. No default administrator is created. Provision a test/real administrator securely outside public registration.

Test only:
```bash
mvn test
```

## User workflow
Register and sign in. Save the generated username shown after registration; it is used to start
account recovery. The user workspace has two pages: **Main** and **My Activity**.
Main follows File Upload → File Processing → Similarity Analysis → Storage Placement → Final Results.
The upload control accepts one file per request because the existing backend API accepts one
`MultipartFile`; it shows browser transfer progress and displays processing results only after
the server confirms success. Similarity compares two different files uploaded to the account.
Placement details come from the file's persisted chunk/provider records; provider names refer to
local simulations. Download and verify reconstruction or delete a file from Final Results.
My Activity shows actual recorded account events with search, filtering and pagination.

## Administrator workflow
Sign in with an explicitly provisioned administrator. Open `/admin.html` to view dashboard
statistics, users, activity, placement results and CSV reports. Use the Cloud Leakage link to
view global provider overlap analysis; that page remains administrator-only.

## API overview
- `POST /api/auth/register`
- `POST /api/auth/login`
- `GET /api/auth/csrf`
- `POST /api/auth/logout`
- `GET/PUT /api/auth/profile`
- `POST /api/recovery/email/username`
- `POST /api/recovery/email/verify`
- `POST /api/recovery/password/request`
- `POST /api/recovery/password/validate`
- `POST /api/recovery/password/reset`
- `POST /api/files/upload?strategy=...`
- `GET /api/files`
- `GET /api/files/{id}`
- `GET /api/files/{id}/download`
- `DELETE /api/files/{id}`
- `POST /api/similarity/compare`
- `GET /api/dashboard/activity?limit=100`
- `POST /api/placement/preview`
- `GET /api/placement/distribution`
- `POST /api/admin/placement/experiments`
- `GET /api/admin/dashboard`
- `GET /api/admin/analytics/storage`
- `GET /api/admin/analytics/leakage`
- `GET /api/admin/users/search`
- `PATCH /api/admin/users/{id}/status`
- `POST /api/admin/leakage/analysis`
- `/api/admin/reports/*.csv`

All state-changing requests require CSRF. All ADMIN endpoints require `ROLE_ADMIN`. File reads and changes require ownership. File details include actual per-chunk provider, encryption and deduplication status; activity records include event ID, filename when applicable, status and technical details.

## Cryptographic design
Each unique plaintext chunk is encrypted using AES-256-GCM with a fresh 12-byte nonce and 128-bit tag. Associated data binds hash, size and key ID. The nonce and key ID are stored by file-chunk reference. Downloads authenticate then decrypt and verify the plaintext hash and file checksum.

## Similarity and leakage
- **MinHash:** an educational seeded affine-permutation approximation over sampled byte shingles.
- **Bloom Filter:** an in-memory request-scoped candidate filter; it may produce false positives, not false negatives for the inserted set.
- **Jaccard:** exact set Jaccard `|A ∩ B| / |A ∪ B|` for provider chunk-hash sets.

These are not exact StoreSim/BFS MinHash/SPClustering implementations.

## Placement
1. Round-robin assigns unique chunks cyclically.
2. Seeded random uses reproducible `Random(seed)`.
3. Leakage-aware greedily scores overlap and provider load.

Score:
`overlapWeight × overlapPenalty + balanceWeight × loadPenalty`

All strategies place each unique input chunk once. Duplicate hashes are deduplicated once. Measured results vary with the identical input dataset.

## Performance methodology
The `Phase10PerformanceTest` uses a deterministic seed and 300 random 4096-byte chunks. It records actual nanosecond values for round-robin, random and leakage-aware placement plus exact Jaccard calculation. It does not record invented throughput or research-paper improvements.

Run the benchmark component:
```bash
mvn -Dtest=Phase10PerformanceTest test
```

## Testing
```bash
mvn clean test
```
Tests cover authentication, authorization, ownership, encryption, chunking, deduplication, reconstruction, MinHash, Bloom, Jaccard, placement and provider analytics. H2 test databases are isolated and do not execute MySQL migrations.

## Screenshots
Add demonstration screenshots for registration, user dashboard, admin dashboard, charts, reports and account management before submission.

## Security limitations
This project uses local simulated providers and is not a production cloud trust boundary. Protect encryption keys and database credentials; do not expose raw keys, IVs or internal storage paths. Security operators should conduct a deployment-specific review.

## Research limitations and future work
Fingerprint overlap is not proof or measurement of every information-leakage channel. The MinHash/Bloom/Jaccard implementation is educational, and placement does not claim exact StoreSim, BFS or SPClustering research results. Future work may add persistent experiment/report storage, key rotation, malware/content scanning, real provider adapters, distributed storage, stronger log auditing and an external benchmark harness.
