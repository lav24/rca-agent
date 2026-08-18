package main

import (
	"context"
	"encoding/json"
	"io"
	"log"
	"math/rand"
	"net/http"
	"os"
	"strconv"
	"time"

	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

var pricingServiceURL string

type placeResponse struct {
	Route      string  `json:"route"`
	Price      float64 `json:"price"`
	Currency   string  `json:"currency"`
	OperatorID string  `json:"operatorId"`
	PlacedAt   string  `json:"placedAt"`
}

func initTracer(ctx context.Context) func() {
	endpoint := os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
	if endpoint == "" {
		endpoint = "otel-collector:4317"
	}

	conn, err := grpc.NewClient(endpoint, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		log.Fatalf("failed to connect to otel collector: %v", err)
	}

	exp, err := otlptracegrpc.New(ctx, otlptracegrpc.WithGRPCConn(conn))
	if err != nil {
		log.Fatalf("failed to create otlp exporter: %v", err)
	}

	res := resource.NewWithAttributes(
		semconv.SchemaURL,
		semconv.ServiceName("placement-service"),
	)

	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exp),
		sdktrace.WithResource(res),
	)
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.TraceContext{})

	return func() {
		_ = tp.Shutdown(ctx)
	}
}

func placeHandler(w http.ResponseWriter, r *http.Request) {
	route := r.URL.Query().Get("route")
	if route == "" {
		route = "UNKNOWN"
	}

	client := http.Client{Transport: otelhttp.NewTransport(http.DefaultTransport)}
	req, _ := http.NewRequestWithContext(r.Context(), http.MethodGet, pricingServiceURL+"/price?route="+route, nil)
	resp, err := client.Do(req)
	if err != nil {
		log.Printf(`{"level":"error","service":"placement-service","route":"%s","error":"%s"}`, route, err)
		http.Error(w, "pricing service unavailable", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		http.Error(w, "failed reading pricing response", http.StatusBadGateway)
		return
	}

	var priceData struct {
		Route    string  `json:"route"`
		Price    float64 `json:"price"`
		Currency string  `json:"currency"`
	}
	if err := json.Unmarshal(body, &priceData); err != nil {
		http.Error(w, "invalid pricing response", http.StatusBadGateway)
		return
	}

	result := placeResponse{
		Route:      priceData.Route,
		Price:      priceData.Price,
		Currency:   priceData.Currency,
		OperatorID: "OP-" + strconv.Itoa(rand.Intn(9000)+1000),
		PlacedAt:   time.Now().UTC().Format(time.RFC3339),
	}

	log.Printf(`{"level":"info","service":"placement-service","route":"%s","operatorId":"%s","price":%.2f}`,
		result.Route, result.OperatorID, result.Price)

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(result)
}

func healthHandler(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("ok"))
}

func main() {
	pricingServiceURL = os.Getenv("PRICING_SERVICE_URL")
	if pricingServiceURL == "" {
		pricingServiceURL = "http://pricing-service:8080"
	}

	ctx := context.Background()
	shutdown := initTracer(ctx)
	defer shutdown()

	mux := http.NewServeMux()
	mux.Handle("/place", otelhttp.NewHandler(http.HandlerFunc(placeHandler), "place"))
	mux.HandleFunc("/health", healthHandler)
	mux.Handle("/metrics", promhttp.Handler())

	log.Println(`{"level":"info","service":"placement-service","msg":"starting on :8080"}`)
	if err := http.ListenAndServe(":8080", mux); err != nil {
		log.Fatal(err)
	}
}
