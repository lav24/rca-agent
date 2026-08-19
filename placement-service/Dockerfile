# --- Build stage ---
FROM golang:1.26-bookworm AS build
WORKDIR /app

COPY go.mod go.sum ./
RUN go mod download

COPY *.go ./
RUN CGO_ENABLED=0 go build -o /app/placement-service .

# --- Runtime stage ---
FROM debian:bookworm-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/placement-service .

EXPOSE 8080
ENTRYPOINT ["/app/placement-service"]
