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
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const (
	MaxFileBytes        = 20 << 20
	MaxClipboardBytes   = 1 << 20
	MaxPromptCharacters = 500
	maxPendingTasks     = 64
	maxStoredTasks      = 320
)

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
	request   Request
	created   time.Time
	leased    time.Time
	done      chan struct{}
	result    Result
	closed    bool
	closedAt  time.Time
	uploading bool
	expiry    *time.Timer
	cleanup   *time.Timer
}

type Server struct {
	token   string
	dataDir string
	mu      sync.Mutex
	tasks   map[string]*task
	wake    chan struct{}
}

func New(token, dataDir string) (*Server, error) {
	if err := ValidateToken(token); err != nil {
		return nil, err
	}
	var err error
	dataDir, err = filepath.Abs(dataDir)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(dataDir, 0700); err != nil {
		return nil, err
	}
	info, err := os.Lstat(dataDir)
	if err != nil {
		return nil, err
	}
	if !info.IsDir() {
		return nil, errors.New("upload directory must be a directory, not a symbolic link")
	}
	if err := validateDirectoryOwner(info); err != nil {
		return nil, err
	}
	if err := os.Chmod(dataDir, 0700); err != nil {
		return nil, err
	}
	return &Server{token: token, dataDir: dataDir, tasks: make(map[string]*task), wake: make(chan struct{}, 1)}, nil
}

// ValidateToken keeps the shared secret safe to use as an HTTP header value.
func ValidateToken(token string) error {
	if len(token) < 32 || len(token) > 4096 {
		return errors.New("token must have between 32 and 4096 characters")
	}
	for _, c := range token {
		if c < 33 || c > 126 {
			return errors.New("token must contain only printable ASCII without whitespace")
		}
	}
	return nil
}

// ValidateBaseURL accepts the proxy origin rather than an arbitrary endpoint.
func ValidateBaseURL(baseURL string) error {
	u, err := url.Parse(baseURL)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.ForceQuery || strings.Contains(baseURL, "#") || (u.Path != "" && u.Path != "/") || u.RawPath != "" || strings.HasSuffix(u.Host, ":") {
		return errors.New("server URL must be an HTTP(S) origin without credentials, query, fragment, or path")
	}
	if port := u.Port(); port != "" {
		n, err := strconv.Atoi(port)
		if err != nil || n < 1 || n > 65535 {
			return errors.New("server URL port must be between 1 and 65535")
		}
	}
	return nil
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
	var fields struct {
		ID string `json:"id,omitempty"`
		// Older MCP adapters marshal the response shape when creating a request.
		ExpiresAt json.RawMessage `json:"expires_at,omitempty"`
		Kind      string          `json:"kind"`
		Prompt    string          `json:"prompt"`
	}
	if err := decodeBody(w, r, 4096, &fields); err != nil || (fields.Kind != "clipboard" && fields.Kind != "file") {
		http.Error(w, "expected clipboard or file request", http.StatusBadRequest)
		return
	}
	if fields.ID != "" && !validRequestID(fields.ID) {
		http.Error(w, "invalid request id", http.StatusBadRequest)
		return
	}
	if utf8.RuneCountInString(fields.Prompt) > MaxPromptCharacters {
		http.Error(w, "prompt too long", http.StatusBadRequest)
		return
	}
	id, err := RandomToken()
	if err != nil {
		http.Error(w, "random source failed", http.StatusInternalServerError)
		return
	}
	now := time.Now()
	if fields.ID != "" {
		id = fields.ID
	}
	input := Request{ID: id[:32], Kind: fields.Kind, Prompt: fields.Prompt, ExpiresAt: now.Add(2 * time.Minute).UTC()}
	s.mu.Lock()
	if _, exists := s.tasks[input.ID]; exists {
		s.mu.Unlock()
		http.Error(w, "request id already used or cancelled", http.StatusConflict)
		return
	}
	pending := 0
	var oldestID string
	var oldest *task
	for id, t := range s.tasks {
		if !t.closed && !now.Before(t.request.ExpiresAt) {
			s.finishLocked(id, Result{Error: "phone did not answer within two minutes"})
		}
		if !t.closed {
			pending++
		} else if oldest == nil || t.closedAt.Before(oldest.closedAt) {
			oldestID, oldest = id, t
		}
	}
	if pending >= maxPendingTasks {
		s.mu.Unlock()
		http.Error(w, "queue full", http.StatusTooManyRequests)
		return
	}
	if len(s.tasks) >= maxStoredTasks && oldest != nil {
		if oldest.cleanup != nil {
			oldest.cleanup.Stop()
		}
		delete(s.tasks, oldestID)
	}
	t := &task{request: input, created: now, done: make(chan struct{})}
	s.tasks[input.ID] = t
	t.expiry = time.AfterFunc(time.Until(input.ExpiresAt), func() {
		s.finish(input.ID, Result{Error: "phone did not answer within two minutes"})
	})
	s.mu.Unlock()
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
		for id, t := range s.tasks {
			if !t.closed && !time.Now().Before(t.request.ExpiresAt) {
				s.finishLocked(id, Result{Error: "phone did not answer within two minutes"})
			}
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
	if action == "cancel" && r.Method == http.MethodPost {
		if !validRequestID(id) {
			http.NotFound(w, r)
			return
		}
		s.cancel(id)
		w.WriteHeader(http.StatusNoContent)
		return
	}
	s.mu.Lock()
	t := s.tasks[id]
	s.mu.Unlock()
	if t == nil {
		http.NotFound(w, r)
		return
	}
	switch {
	case action == "status" && r.Method == http.MethodGet:
		s.mu.Lock()
		if !t.closed && !time.Now().Before(t.request.ExpiresAt) {
			s.finishLocked(id, Result{Error: "phone did not answer within two minutes"})
		}
		closed := t.closed
		s.mu.Unlock()
		if closed {
			w.WriteHeader(http.StatusGone)
		} else {
			w.WriteHeader(http.StatusNoContent)
		}
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
		var out struct {
			Text  string `json:"text"`
			Error string `json:"error"`
		}
		if err := decodeBody(w, r, 6*MaxClipboardBytes+1024, &out); err != nil || len(out.Text) > MaxClipboardBytes || len(out.Error) > 1024 {
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

func validRequestID(id string) bool {
	if len(id) != 32 {
		return false
	}
	_, err := hex.DecodeString(id)
	return err == nil
}

func (s *Server) cancel(id string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.tasks[id] == nil {
		// A cancellation may arrive before the POST that creates this client ID.
		// Keep a bounded tombstone so a late POST cannot leave a phone request behind.
		if len(s.tasks) >= maxStoredTasks {
			var oldestID string
			var oldest *task
			for key, t := range s.tasks {
				if t.closed && (oldest == nil || t.closedAt.Before(oldest.closedAt)) {
					oldestID, oldest = key, t
				}
			}
			if oldest != nil {
				if oldest.cleanup != nil {
					oldest.cleanup.Stop()
				}
				delete(s.tasks, oldestID)
			}
		}
		now := time.Now()
		s.tasks[id] = &task{request: Request{ID: id, ExpiresAt: now.Add(2 * time.Minute)}, created: now, done: make(chan struct{})}
	}
	s.finishLocked(id, Result{Error: "request cancelled"})
}

func (s *Server) receiveFile(w http.ResponseWriter, r *http.Request, id string) {
	if r.ContentLength > MaxFileBytes {
		http.Error(w, "file exceeds 20 MiB", http.StatusRequestEntityTooLarge)
		return
	}
	name := r.Header.Get("X-Filename")
	if encoded := r.Header.Get("X-Filename-Encoded"); encoded != "" {
		var err error
		name, err = url.PathUnescape(encoded)
		if err != nil || !utf8.ValidString(name) {
			http.Error(w, "invalid encoded filename", http.StatusBadRequest)
			return
		}
	}
	// A filename never becomes a path, even if a sender supplies Windows separators.
	name = filepath.Base(strings.ReplaceAll(name, "\\", "/"))
	if name == "." || name == "/" || name == "" {
		name = "phone-file"
	}
	if len(name) > 180 || strings.ContainsAny(name, "\x00\r\n") {
		http.Error(w, "invalid filename", http.StatusBadRequest)
		return
	}
	s.mu.Lock()
	t := s.tasks[id]
	if t == nil || t.closed || t.uploading || !time.Now().Before(t.request.ExpiresAt) {
		s.mu.Unlock()
		http.Error(w, "already completed or upload in progress", http.StatusConflict)
		return
	}
	t.uploading = true
	deadline := t.request.ExpiresAt
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		t.uploading = false
		s.mu.Unlock()
	}()
	// A stalled upload must release its file and connection when the request expires.
	controller := http.NewResponseController(w)
	_ = controller.SetReadDeadline(deadline)
	stopWatching := make(chan struct{})
	watcherFinished := make(chan struct{})
	go func() {
		defer close(watcherFinished)
		select {
		case <-t.done:
			_ = controller.SetReadDeadline(time.Now())
		case <-stopWatching:
		}
	}()
	var stopOnce sync.Once
	stopWatcher := func() {
		stopOnce.Do(func() { close(stopWatching) })
		<-watcherFinished
		_ = controller.SetReadDeadline(time.Time{})
	}
	defer stopWatcher()
	path := filepath.Join(s.dataDir, id+"-"+name)
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		http.Error(w, "cannot create file", http.StatusInternalServerError)
		return
	}
	n, copyErr := io.Copy(f, io.LimitReader(r.Body, MaxFileBytes+1))
	closeErr := f.Close()
	if copyErr != nil || closeErr != nil || n > MaxFileBytes {
		_ = os.Remove(path)
		s.mu.Lock()
		if !t.closed && !time.Now().Before(t.request.ExpiresAt) {
			s.finishLocked(id, Result{Error: "phone did not answer within two minutes"})
		}
		closed := t.closed
		s.mu.Unlock()
		if closed {
			http.Error(w, "already completed", http.StatusConflict)
		} else {
			http.Error(w, "file exceeds 20 MiB or upload failed", http.StatusRequestEntityTooLarge)
		}
		return
	}
	stopWatcher()
	if !s.finish(id, Result{Path: path}) {
		_ = os.Remove(path)
		http.Error(w, "already completed", http.StatusConflict)
		return
	}
	writeJSON(w, http.StatusOK, Result{Path: path})
}

func (s *Server) finish(id string, result Result) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.finishLocked(id, result)
}

func (s *Server) finishLocked(id string, result Result) bool {
	t := s.tasks[id]
	if t == nil || t.closed {
		return false
	}
	expired := !time.Now().Before(t.request.ExpiresAt)
	if expired {
		result = Result{Error: "phone did not answer within two minutes"}
	}
	t.result, t.closed, t.closedAt = result, true, time.Now()
	if t.expiry != nil {
		t.expiry.Stop()
	}
	close(t.done)
	// Keep the result briefly for the waiting caller; clean it up after expiry.
	t.cleanup = time.AfterFunc(3*time.Minute, func() {
		s.mu.Lock()
		if s.tasks[id] == t {
			delete(s.tasks, id)
		}
		s.mu.Unlock()
	})
	return !expired
}

func decodeBody(w http.ResponseWriter, r *http.Request, limit int64, out any) error {
	r.Body = http.MaxBytesReader(w, r.Body, limit)
	decoder := json.NewDecoder(r.Body)
	var raw json.RawMessage
	if err := decoder.Decode(&raw); err != nil {
		return err
	}
	if len(raw) == 0 || raw[0] != '{' {
		return errors.New("expected JSON object")
	}
	fields := json.NewDecoder(strings.NewReader(string(raw)))
	fields.DisallowUnknownFields()
	if err := fields.Decode(out); err != nil {
		return err
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		return errors.New("expected one JSON object")
	}
	return nil
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func ClientRequest(ctx context.Context, client *http.Client, baseURL, token, kind, prompt string) (Result, error) {
	if err := ValidateBaseURL(baseURL); err != nil {
		return Result{}, err
	}
	if err := ValidateToken(token); err != nil {
		return Result{}, err
	}
	if client == nil {
		client = &http.Client{Timeout: 130 * time.Second}
	}
	// Proxy redirects must never forward the phone token to another endpoint.
	safeClient := *client
	safeClient.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	client = &safeClient
	randomID, err := RandomToken()
	if err != nil {
		return Result{}, err
	}
	id := randomID[:32]
	completed := false
	defer func() {
		if completed {
			return
		}
		// A known client ID also covers cancellation before the create response arrives.
		cancelCtx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		cancelReq, err := http.NewRequestWithContext(cancelCtx, http.MethodPost, strings.TrimRight(baseURL, "/")+"/v1/requests/"+id+"/cancel", nil)
		if err == nil {
			cancelReq.Header.Set("Authorization", "Bearer "+token)
			if res, err := client.Do(cancelReq); err == nil {
				res.Body.Close()
			}
		}
	}()
	input, _ := json.Marshal(struct {
		ID     string `json:"id"`
		Kind   string `json:"kind"`
		Prompt string `json:"prompt"`
	}{id, kind, prompt})
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
	if res.StatusCode != http.StatusCreated {
		res.Body.Close()
		return Result{}, fmt.Errorf("queue returned %s", res.Status)
	}
	var queued Request
	decodeErr := json.NewDecoder(io.LimitReader(res.Body, 4096)).Decode(&queued)
	res.Body.Close()
	if decodeErr != nil {
		return Result{}, decodeErr
	}
	if !validRequestID(queued.ID) {
		return Result{}, errors.New("queue returned invalid request id")
	}
	// Older proxies choose their own IDs; use that ID once their response is known.
	id = queued.ID
	waitReq, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(baseURL, "/")+"/v1/requests/"+id+"/wait", nil)
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
	if err := json.NewDecoder(io.LimitReader(res.Body, 6*MaxClipboardBytes+4096)).Decode(&result); err != nil {
		return Result{}, err
	}
	completed = true
	return result, nil
}
