# rca-agent (Phase 1)

A self-built, OSS-only observability stack that mirrors a production microservices
shape (edge → service → downstream service, metrics/traces/logs pipelines) — the
foundation for an AI-assisted RCA/incident-triage agent.

This phase is just the "production system": two instrumented services and a full
observability stack. Later phases add fault injection, an OTel-sourced context
assembly layer, and an LLM (via a self-hosted LiteLLM proxy) that drafts RCAs.

## Services

- **pricing-service** (Java 21 / Spring Boot) — `GET /price?route=X`, exposes
  Prometheus metrics at `/actuator/prometheus`, traced via the OpenTelemetry Java
  agent.
- **placement-service** (Go) — `GET /place?route=X`, calls pricing-service,
  exposes Prometheus metrics at `/metrics`, traced via the OpenTelemetry Go SDK.

## Observability stack

- **otel-collector** — receives OTLP traces from both services, forwards to Tempo.
- **tempo** — trace storage/query backend.
- **prometheus** — scrapes metrics directly from both services.
- **grafana** — Prometheus + Tempo datasources pre-provisioned, anonymous admin
  access for local dev.
- **elasticsearch + kibana + filebeat** — filebeat tails container logs directly
  off the Docker host and ships them to Elasticsearch.

## Prerequisites (already installed)

- Colima + Docker + Docker Compose
- Go 1.26
- Java 21 (SDKMAN) + Maven

## Run it

```bash
docker compose up --build -d
```

Generate some traffic:

```bash
curl "http://localhost:8082/place?route=DEL-BLR"
```

## Fault injection

Both services expose a runtime-toggleable fault-injection admin API (no restart
needed) — this is what gives the eventual RCA agent something real to diagnose.

```bash
# Activate a fault
curl -X POST http://localhost:8081/admin/fault \
  -H 'Content-Type: application/json' \
  -d '{"type": "latency", "durationSeconds": 60}'

# Check what's currently active (also the eval harness's ground-truth source)
curl http://localhost:8081/admin/fault

# Clear it early
curl -X POST http://localhost:8081/admin/fault \
  -H 'Content-Type: application/json' \
  -d '{"type": "none"}'
```

Same API shape on placement-service at `:8082/admin/fault`. Fault types:

| type | effect | detectable via |
|---|---|---|
| `latency` | adds a fixed delay, response still succeeds | traces / latency metrics |
| `error` | returns HTTP 500 immediately | error-rate metrics / logs |
| `bad_data` | returns a fast, valid `200` with corrupted data (`price: -1` on pricing-service, empty `operatorId` on placement-service) | not visible in metrics or logs at all — only in the actual response payload |

Each active fault type is also exposed as a Prometheus gauge:
`fault_injection_active{type="latency|error|bad_data"}`.

## Alerting

Prometheus evaluates symptom-based alert rules (`observability/prometheus/alert-rules.yml`)
against real HTTP metrics from both services — error rate and p95 latency, not the
fault-injection flag itself, so alerts fire the same way a real incident would.
Firing alerts get pushed to Alertmanager, which routes them (via `observability/alertmanager/alertmanager.yml`)
to a webhook — currently a throwaway echo container standing in for what becomes
the context-assembly service in a later phase.

To see it fire end-to-end:

```bash
# Push placement-service's error rate over the 10% threshold for 30+ seconds
curl -X POST http://localhost:8082/admin/fault -H 'Content-Type: application/json' -d '{"type": "error", "durationSeconds": 60}'
for i in {1..20}; do curl -s "http://localhost:8082/place?route=DEL-BLR" > /dev/null; sleep 2; done
```

Then check:
- **http://localhost:9090/alerts** — the rule should move from `Pending` to `Firing`
- Alertmanager UI: **http://localhost:9093**
- The actual delivered payload: `docker-compose logs webhook-receiver`

Note: `bad_data` deliberately has no alert rule — it's a silent correctness bug,
invisible to error-rate/latency monitoring by design.

## Where to look

| Tool | URL |
|---|---|
| Grafana | http://localhost:3000 |
| Prometheus targets | http://localhost:9090/targets |
| Kibana | http://localhost:5601 |
| Tempo (via Grafana Explore, or API) | http://localhost:3200 |
| Elasticsearch | http://localhost:9200 |

## Tear down

```bash
docker compose down          # stop + remove containers
docker compose down -v       # also wipe volumes (ES data, Tempo blocks)
```
