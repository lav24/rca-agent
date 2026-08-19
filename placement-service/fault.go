package main

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
)

type faultType string

const (
	faultNone    faultType = "none"
	faultLatency faultType = "latency"
	faultError   faultType = "error"
	faultBadData faultType = "bad_data"
)

const injectedLatency = 3 * time.Second

var faultGauge = prometheus.NewGaugeVec(
	prometheus.GaugeOpts{
		Name: "fault_injection_active",
		Help: "1 if this fault type is currently injected, 0 otherwise",
	},
	[]string{"type"},
)

func init() {
	prometheus.MustRegister(faultGauge)
	for _, t := range []faultType{faultLatency, faultError, faultBadData} {
		faultGauge.WithLabelValues(string(t)).Set(0)
	}
}

type faultState struct {
	mu        sync.Mutex
	active    faultType
	since     time.Time
	expiresAt time.Time
	timer     *time.Timer
}

var state = &faultState{active: faultNone}

func (s *faultState) activate(t faultType, duration time.Duration) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.timer != nil {
		s.timer.Stop()
	}

	s.active = t
	s.since = time.Now()
	s.expiresAt = time.Now().Add(duration)
	s.setGauges(t)

	s.timer = time.AfterFunc(duration, s.clear)
}

func (s *faultState) clear() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.active = faultNone
	s.since = time.Time{}
	s.expiresAt = time.Time{}
	s.setGauges(faultNone)
}

func (s *faultState) setGauges(active faultType) {
	for _, t := range []faultType{faultLatency, faultError, faultBadData} {
		if t == active {
			faultGauge.WithLabelValues(string(t)).Set(1)
		} else {
			faultGauge.WithLabelValues(string(t)).Set(0)
		}
	}
}

func (s *faultState) current() (faultType, time.Time, time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.active, s.since, s.expiresAt
}

type faultRequest struct {
	Type            string `json:"type"`
	DurationSeconds int    `json:"durationSeconds"`
}

func faultAdminHandler(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		writeFaultStatus(w)
	case http.MethodPost:
		var req faultRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "invalid request body", http.StatusBadRequest)
			return
		}
		t := faultType(strings.ToLower(req.Type))
		switch t {
		case faultNone:
			state.clear()
		case faultLatency, faultError, faultBadData:
			duration := time.Duration(req.DurationSeconds) * time.Second
			if duration <= 0 {
				duration = 60 * time.Second
			}
			state.activate(t, duration)
		default:
			http.Error(w, "unknown fault type: "+req.Type, http.StatusBadRequest)
			return
		}
		log.Printf(`{"level":"warn","service":"placement-service","msg":"fault admin change","active":"%s"}`, t)
		writeFaultStatus(w)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func writeFaultStatus(w http.ResponseWriter) {
	active, since, expiresAt := state.current()
	resp := map[string]any{"active": string(active)}
	if active != faultNone {
		resp["since"] = since.UTC().Format(time.RFC3339)
		resp["expiresAt"] = expiresAt.UTC().Format(time.RFC3339)
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(resp)
}
