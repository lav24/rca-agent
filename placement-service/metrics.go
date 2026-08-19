package main

import (
	"net/http"
	"strconv"
	"time"

	"github.com/prometheus/client_golang/prometheus"
)

var httpDuration = prometheus.NewHistogramVec(
	prometheus.HistogramOpts{
		Name:    "http_request_duration_seconds",
		Help:    "HTTP request duration in seconds, labeled by path/method/status",
		Buckets: prometheus.DefBuckets,
	},
	[]string{"path", "method", "status"},
)

func init() {
	prometheus.MustRegister(httpDuration)
}

// statusRecorder wraps http.ResponseWriter to capture the status code that
// gets written, since the standard library doesn't expose it after the fact.
type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(status int) {
	r.status = status
	r.ResponseWriter.WriteHeader(status)
}

func withMetrics(path string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		start := time.Now()
		next.ServeHTTP(rec, r)
		httpDuration.
			WithLabelValues(path, r.Method, strconv.Itoa(rec.status)).
			Observe(time.Since(start).Seconds())
	})
}
