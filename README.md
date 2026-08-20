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

## Context assembly

`context-assembly-service` (Java) is what Alertmanager actually calls when an
alert fires (`POST /webhook/alert`, replacing the earlier echo-container
placeholder). For each firing alert, it queries Prometheus (error rate, p95
latency, active injected fault), Tempo (recent error traces for that service),
and Elasticsearch (recent log lines for that service) for the alert's time
window, assembles everything into one structured JSON bundle, logs it, and
returns it in the HTTP response — this bundle is what an LLM will eventually
reason over in a later phase.

Test it directly without waiting for a real alert:

```bash
curl -X POST http://localhost:8083/webhook/alert \
  -H 'Content-Type: application/json' \
  -d '{
    "status": "firing",
    "alerts": [{
      "status": "firing",
      "labels": {"alertname": "PlacementServiceHighErrorRate", "service": "placement-service", "severity": "critical"},
      "annotations": {"summary": "placement-service error rate above 10%"},
      "startsAt": "'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'",
      "endsAt": "0001-01-01T00:00:00Z",
      "fingerprint": "test123"
    }]
  }'
```

Known scope limits for this phase: trace lookup only searches `status=error`
spans (won't surface anything for a pure-latency incident, since those spans
aren't marked as errors), and there's no retry/backoff if one of the three
backends is temporarily unreachable — a failed sub-query just yields empty/null
data in that section of the bundle rather than failing the whole request.

## LLM reasoning

A self-hosted LiteLLM proxy (`litellm`, port 4000) exposes one model, `rca-llm`,
which routes to a local Llama 3.1 8B model served by Ollama (running natively on
the host, not in Docker, to get Apple Silicon GPU acceleration — the proxy reaches
it via `host.docker.internal`). `context-assembly-service` calls it through
LiteLLM's OpenAI-compatible API, never talking to Ollama directly — swapping the
underlying model later (e.g. to Claude, via a real API key) only means editing
`litellm/config.yaml`, no application code changes.

Prerequisite (run once, outside Docker):
```bash
brew install ollama
ollama serve &          # or: brew services start ollama
ollama pull llama3.1:8b
```

Test reasoning directly against a hand-built bundle:
```bash
curl -X POST http://localhost:8083/reason \
  -H 'Content-Type: application/json' \
  -d '{
    "alert": {"alertname": "PlacementServiceHighErrorRate", "service": "placement-service", "severity": "critical", "summary": "error rate above 10%", "startedAt": "2026-01-01T00:00:00Z"},
    "metrics": {"errorRate": 1.0, "p95LatencySeconds": 0.005, "activeInjectedFault": "error"},
    "traces": [],
    "logs": ["level=error service=placement-service msg=\"injected fault active\" type=error"]
  }' | python3 -m json.tool
```

Or trigger the full pipeline end-to-end (alert fires -> context assembled ->
LLM diagnoses it), same fault-injection trick as before:
```bash
curl -X POST http://localhost:8082/admin/fault -H 'Content-Type: application/json' -d '{"type": "error", "durationSeconds": 60}'
for i in {1..20}; do curl -s "http://localhost:8082/place?route=DEL-BLR" > /dev/null; sleep 2; done
docker-compose logs context-assembly-service | grep "RCA diagnosis produced"
```

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
