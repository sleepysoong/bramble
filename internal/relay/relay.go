package relay

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const MaxFileBytes = 20 << 20

type Request struct {
	ID        string    `json:"id"`
	Kind      string    `json:"kind"`
	Prompt    string    `json:"prompt"`
	ExpiresAt time.Time `json:"expires_at"`
}

type Result struct {
	Text  string `json:"text,omitempty"`
	Path  string `json:"path,omitempty"`
	Error string `json:"error,omitempty"`
}

type task struct {
	request Request
	created time.Time
	leased  time.Time
	done    chan struct{}
	result  Result
	closed  bool
}

type Server struct {
	token   string
	dataDir string
	mu      sync.Mutex
	tasks   map[string]*task
	wake    chan struct{}
}

func New(token, dataDir string) (*Server, error) {
	if len(token) < 32 {
		return nil, errors.New("token must have at least 32 characters")
	}
	var err error
	dataDir, err = filepath.Abs(dataDir)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(dataDir, 0700); err != nil {
		return nil, err
	}
	if err := os.Chmod(dataDir, 0700); err != nil {
		return nil, err
	}
	return &Server{token: token, dataDir: dataDir, tasks: make(map[string]*task), wake: make(chan struct{}, 1)}, nil
}

func RandomToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Origin") != "" {
		http.Error(w, "browser origin rejected", http.StatusForbidden)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	if subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+s.token)) != 1 {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	switch {
	case r.Method == http.MethodGet && r.URL.Path == "/v1/health":
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	case r.Method == http.MethodPost && r.URL.Path == "/v1/requests":
		s.create(w, r)
	case r.Method == http.MethodGet && r.URL.Path == "/v1/device/next":
		s.next(w, r)
	case strings.HasPrefix(r.URL.Path, "/v1/requests/"):
		s.taskRoute(w, r)
	default:
		http.NotFound(w, r)
	}
}

func (s *Server) create(w http.ResponseWriter, r *http.Request) {
	var input Request
	if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&input); err != nil || (input.Kind != "clipboard" && input.Kind != "file") {
		http.Error(w, "expected clipboard or file request", http.StatusBadRequest)
		return
	}
	if len(input.Prompt) > 500 {
		http.Error(w, "prompt too long", http.StatusBadRequest)
		return
	}
	id, err := RandomToken()
	if err != nil {
		http.Error(w, "random source failed", http.StatusInternalServerError)
		return
	}
	input.ID = id[:32]
	now := time.Now()
	input.ExpiresAt = now.Add(2 * time.Minute).UTC()
	s.mu.Lock()
	if len(s.tasks) >= 64 {
		s.mu.Unlock()
		http.Error(w, "queue full", http.StatusTooManyRequests)
		return
	}
	s.tasks[input.ID] = &task{request: input, created: now, done: make(chan struct{})}
	s.mu.Unlock()
	time.AfterFunc(2*time.Minute, func() {
		s.finish(input.ID, Result{Error: "phone did not answer within two minutes"})
	})
	select {
	case s.wake <- struct{}{}:
	default:
	}
	writeJSON(w, http.StatusCreated, input)
}

func (s *Server) next(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 25*time.Second)
	defer cancel()
	for {
		s.mu.Lock()
		var chosen *task
		for _, t := range s.tasks {
			if !t.closed && (t.leased.IsZero() || time.Since(t.leased) > 30*time.Second) && (chosen == nil || t.created.Before(chosen.created)) {
				chosen = t
			}
		}
		if chosen != nil {
			chosen.leased = time.Now()
			out := chosen.request
			s.mu.Unlock()
			writeJSON(w, http.StatusOK, out)
			return
		}
		s.mu.Unlock()
		select {
		case <-s.wake:
		case <-time.After(2 * time.Second):
		case <-ctx.Done():
			w.WriteHeader(http.StatusNoContent)
			return
		}
	}
}

func (s *Server) taskRoute(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/v1/requests/"), "/")
	if len(parts) != 2 {
		http.NotFound(w, r)
		return
	}
	id, action := parts[0], parts[1]
	s.mu.Lock()
	t := s.tasks[id]
	s.mu.Unlock()
	if t == nil {
		http.NotFound(w, r)
		return
	}
	switch {
	case action == "wait" && r.Method == http.MethodGet:
		select {
		case <-t.done:
			s.mu.Lock()
			out := t.result
			s.mu.Unlock()
			writeJSON(w, http.StatusOK, out)
		case <-time.After(2 * time.Minute):
			s.finish(id, Result{Error: "phone did not answer within two minutes"})
			http.Error(w, "timeout", http.StatusGatewayTimeout)
		case <-r.Context().Done():
			s.finish(id, Result{Error: "request cancelled"})
		}
	case action == "result" && r.Method == http.MethodPost:
		if t.request.Kind != "clipboard" {
			http.Error(w, "wrong request kind", http.StatusBadRequest)
			return
		}
		var out Result
		if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&out); err != nil || len(out.Text) > 1<<20 {
			http.Error(w, "invalid result", http.StatusBadRequest)
			return
		}
		if !s.finish(id, Result{Text: out.Text, Error: out.Error}) {
			http.Error(w, "already completed", http.StatusConflict)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	case action == "file" && r.Method == http.MethodPut:
		if t.request.Kind != "file" {
			http.Error(w, "wrong request kind", http.StatusBadRequest)
			return
		}
		s.receiveFile(w, r, id)
	case action == "reject" && r.Method == http.MethodPost:
		if !s.finish(id, Result{Error: "rejected on phone"}) {
			http.Error(w, "already completed", http.StatusConflict)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	default:
		http.NotFound(w, r)
	}
}

func (s *Server) receiveFile(w http.ResponseWriter, r *http.Request, id string) {
	name := filepath.Base(r.Header.Get("X-Filename"))
	if name == "." || name == "/" || name == "" {
		name = "phone-file"
	}
	// The random prefix prevents both traversal and overwriting another upload.
	path := filepath.Join(s.dataDir, id+"-"+name)
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		http.Error(w, "cannot create file", http.StatusInternalServerError)
		return
	}
	n, copyErr := io.Copy(f, io.LimitReader(r.Body, MaxFileBytes+1))
	closeErr := f.Close()
	if copyErr != nil || closeErr != nil || n > MaxFileBytes {
		os.Remove(path)
		http.Error(w, "file exceeds 20 MiB or upload failed", http.StatusRequestEntityTooLarge)
		return
	}
	if !s.finish(id, Result{Path: path}) {
		os.Remove(path)
		http.Error(w, "already completed", http.StatusConflict)
		return
	}
	writeJSON(w, http.StatusOK, Result{Path: path})
}

func (s *Server) finish(id string, result Result) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	t := s.tasks[id]
	if t == nil || t.closed {
		return false
	}
	t.result, t.closed = result, true
	close(t.done)
	// Keep the result briefly for the waiting caller; clean it up after expiry.
	time.AfterFunc(3*time.Minute, func() { s.mu.Lock(); delete(s.tasks, id); s.mu.Unlock() })
	return true
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func ClientRequest(ctx context.Context, client *http.Client, baseURL, token, kind, prompt string) (Result, error) {
	input, _ := json.Marshal(Request{Kind: kind, Prompt: prompt})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimRight(baseURL, "/")+"/v1/requests", strings.NewReader(string(input)))
	if err != nil {
		return Result{}, err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	res, err := client.Do(req)
	if err != nil {
		return Result{}, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusCreated {
		return Result{}, fmt.Errorf("queue returned %s", res.Status)
	}
	var queued Request
	if err := json.NewDecoder(res.Body).Decode(&queued); err != nil {
		return Result{}, err
	}
	waitReq, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(baseURL, "/")+"/v1/requests/"+queued.ID+"/wait", nil)
	if err != nil {
		return Result{}, err
	}
	waitReq.Header.Set("Authorization", "Bearer "+token)
	res, err = client.Do(waitReq)
	if err != nil {
		return Result{}, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return Result{}, fmt.Errorf("wait returned %s", res.Status)
	}
	var result Result
	if err := json.NewDecoder(res.Body).Decode(&result); err != nil {
		return Result{}, err
	}
	return result, nil
}
