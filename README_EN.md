# Cross-Hospital Imaging Recognition & Fetch Routing Service

Short English overview. The authoritative, detailed documentation is [README.md](README.md) (Chinese).

## What it does

A hospital information department runs this service to fetch an external hospital's
DICOM study into the local PACS, with an append-only audit trail. A clinician
starts a request from an HIS work order; the service accepts it asynchronously
(HTTP 202) and drives a five-state task machine:

```
queued -> querying -> pulling -> stored -> notified
```

A failure at any protocol step terminates as `failed` with a machine-readable
failure code, a dead-letter record, and an alert. Nothing is silently rewritten.

- **Protocols:** DICOM TCP (C-FIND / C-MOVE / C-STORE), WADO-RS HTTP multipart,
  HL7 v2.x over MLLP TCP, PostgreSQL for state and audit.
- **Idempotency:** `(patient_mpi, study_uid)` is the unique anchor. Re-submitting
  an in-flight request returns the existing task id instead of pulling twice.
- **Audit:** append-only rows with chained SHA-256 checksums, enforced by a
  database trigger. Query endpoints never expose the checksum columns.
- **AI is assistive only:** report normalization and recognition-feasibility
  screening produce drafts for a radiologist. `requiresHumanReview` is always
  true. If the model is unreachable, a rule template produces a payload with the
  same JSON Schema and marks `source=rule_fallback`. AI never writes to PACS,
  never advances task state, and never blocks the completion notification.
- **Observability:** structured JSON logs with `traceId` / `spanId` / `who` /
  `taskId`, plus Micrometer metrics on `/actuator/prometheus`.

## Build and run

Requirements: JDK 17+, Maven 3.9+, PostgreSQL 14+. The included `./mvnw` is a
thin wrapper around the locally installed Maven.

```bash
cp .env.example .env          # fill in DATABASE_URL / credentials
./mvnw -DskipTests package
./mvnw spring-boot:run
./mvnw test
```

Credentials are read from environment variables only; no secret is committed.

Smoke checks:

```bash
curl -sS http://127.0.0.1:8080/actuator/health
curl -sS http://127.0.0.1:8080/v3/api-docs        # OpenAPI 3.1
curl -sS http://127.0.0.1:8080/actuator/prometheus
open http://127.0.0.1:8080/swagger-ui.html
```

## Main endpoints

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/imaging/peers` | Register an external DICOM node |
| GET | `/api/imaging/peers` | List enabled nodes |
| POST | `/api/imaging/peers/{aet}/health` | Handshake compatibility RPC |
| POST | `/api/imaging/studies/fetch` | Start an asynchronous fetch (202) |
| GET | `/api/imaging/tasks/{task_id}` | Query task state |
| GET | `/api/imaging/audit` | Read-only audit query |
| GET | `/api/imaging/dlq` | Unresolved dead letters |
| POST | `/api/imaging/dlq/{id}/resolve` | Mark a dead letter resolved |

## Real evidence

`docs/screenshots/` contains three captures from a real run (real PostgreSQL,
real DICOM service class providers, real Spring Boot container): the Swagger UI,
a handshake RPC terminal session, and a full fetch with state transitions and
append-only audit rows. `docs/concepts/visual-target.md` is the visual target
card; it is intentionally **not** identical to the captures. See the
"Concept vs. measured evidence" section in README.md for the honest gap list.

## License

MIT. See [LICENSE](LICENSE).

---

## Follow Us

Scan the QR code to follow our official account for updates and community access:

![Follow Us](qrcode.jpg)