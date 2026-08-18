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
