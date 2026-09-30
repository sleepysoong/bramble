package relay

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

const testToken = "0123456789abcdef0123456789abcdef"

func authRequest(t *testing.T, method, url string, body io.Reader) *http.Request {
	t.Helper()
	r, err := http.NewRequest(method, url, body)
	if err != nil {
		t.Fatal(err)
	}
	r.Header.Set("Authorization", "Bearer "+testToken)
	return r
}

func TestClipboardRoundTrip(t *testing.T) {
	s, err := New(testToken, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(s)
	defer server.Close()

	bad, err := http.Get(server.URL + "/v1/device/next")
	if err != nil {
		t.Fatal(err)
	}
	bad.Body.Close()
	if bad.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unauthorized: %d", bad.StatusCode)
	}

	origin := authRequest(t, "GET", server.URL+"/v1/health", nil)
	origin.Header.Set("Origin", "https://evil.example")
	res, err := http.DefaultClient.Do(origin)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != http.StatusForbidden {
		t.Fatalf("origin: %d", res.StatusCode)
	}

	result := make(chan Result, 1)
	errs := make(chan error, 1)
	go func() {
		out, err := ClientRequest(context.Background(), server.Client(), server.URL, testToken, "clipboard", "Share for test")
		if err != nil {
			errs <- err
			return
		}
		result <- out
	}()

	next := authRequest(t, "GET", server.URL+"/v1/device/next", nil)
	client := &http.Client{Timeout: 5 * time.Second}
	res, err = client.Do(next)
	if err != nil {
		t.Fatal(err)
	}
	var request Request
	if err := json.NewDecoder(res.Body).Decode(&request); err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if request.Kind != "clipboard" || request.Prompt != "Share for test" {
		t.Fatalf("request: %+v", request)
	}
	if request.ExpiresAt.Before(time.Now()) || request.ExpiresAt.After(time.Now().Add(2*time.Minute)) {
		t.Fatalf("invalid request deadline: %s", request.ExpiresAt)
	}

	answer := authRequest(t, "POST", server.URL+"/v1/requests/"+request.ID+"/result", strings.NewReader(`{"text":"hello from phone"}`))
	res, err = client.Do(answer)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != http.StatusNoContent {
		t.Fatalf("result: %d", res.StatusCode)
	}
	select {
	case got := <-result:
		if got.Text != "hello from phone" {
			t.Fatalf("got %+v", got)
		}
	case err := <-errs:
		t.Fatal(err)
	case <-time.After(5 * time.Second):
		t.Fatal("tool did not receive result")
	}
}

func TestFileUpload(t *testing.T) {
	dir := t.TempDir()
	s, err := New(testToken, dir)
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(s)
	defer server.Close()
	create := authRequest(t, "POST", server.URL+"/v1/requests", strings.NewReader(`{"kind":"file"}`))
	res, err := server.Client().Do(create)
	if err != nil {
		t.Fatal(err)
	}
	var request Request
	if err := json.NewDecoder(res.Body).Decode(&request); err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != http.StatusCreated {
		t.Fatalf("create: %d", res.StatusCode)
	}
	put := authRequest(t, "PUT", server.URL+"/v1/requests/"+request.ID+"/file", bytes.NewBufferString("file data"))
	put.Header.Set("X-Filename", "../note.txt")
	res, err = server.Client().Do(put)
	if err != nil {
		t.Fatal(err)
	}
	var result Result
	if err := json.NewDecoder(res.Body).Decode(&result); err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("upload: %d", res.StatusCode)
	}
	if filepath.Dir(result.Path) != dir {
		t.Fatalf("escaped upload directory: %s", result.Path)
	}
	data, err := os.ReadFile(result.Path)
	if err != nil || string(data) != "file data" {
		t.Fatalf("file: %q %v", data, err)
	}

	wait := authRequest(t, "GET", server.URL+"/v1/requests/"+request.ID+"/wait", nil)
	res, err = server.Client().Do(wait)
	if err != nil {
		t.Fatal(err)
	}
	var waited Result
	if err := json.NewDecoder(res.Body).Decode(&waited); err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if waited.Path != result.Path {
		t.Fatalf("waited: %+v", waited)
	}
}

func queueForTest(t *testing.T, s *Server, kind string) Request {
	t.Helper()
	w := httptest.NewRecorder()
	s.ServeHTTP(w, authRequest(t, http.MethodPost, "/v1/requests", strings.NewReader(`{"kind":"`+kind+`"}`)))
	if w.Code != http.StatusCreated {
		t.Fatalf("create: %d %s", w.Code, w.Body.String())
	}
	var request Request
	if err := json.Unmarshal(w.Body.Bytes(), &request); err != nil {
		t.Fatal(err)
	}
	return request
}

func TestCompletedRequestsDoNotFillQueue(t *testing.T) {
	s, err := New(testToken, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < maxStoredTasks+20; i++ {
		request := queueForTest(t, s, "clipboard")
		if !s.finish(request.ID, Result{Text: "complete"}) {
			t.Fatal("cannot complete request")
		}
	}
	if len(s.tasks) > maxStoredTasks {
		t.Fatalf("unbounded retained results: %d", len(s.tasks))
	}
	for i := 0; i < maxPendingTasks; i++ {
		queueForTest(t, s, "clipboard")
	}
	w := httptest.NewRecorder()
	s.ServeHTTP(w, authRequest(t, http.MethodPost, "/v1/requests", strings.NewReader(`{"kind":"clipboard"}`)))
	if w.Code != http.StatusTooManyRequests {
		t.Fatalf("pending queue limit: %d", w.Code)
	}
}

func TestRejectsMalformedOrExtraJSON(t *testing.T) {
	s, err := New(testToken, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	for _, input := range []string{`{"kind":"file"} {}`, `{"kind":"file","unknown":true}`, `{"kind":"file"}` + strings.Repeat(" ", 4096)} {
		w := httptest.NewRecorder()
		s.ServeHTTP(w, authRequest(t, http.MethodPost, "/v1/requests", strings.NewReader(input)))
		if w.Code != http.StatusBadRequest {
			t.Fatalf("accepted invalid body %q: %d", input[:min(len(input), 60)], w.Code)
		}
	}
	request := queueForTest(t, s, "clipboard")
	for _, input := range []string{`null`, `{"text":"a"} {}`, `{"text":"a","path":"/tmp/evil"}`} {
		w := httptest.NewRecorder()
		s.ServeHTTP(w, authRequest(t, http.MethodPost, "/v1/requests/"+request.ID+"/result", strings.NewReader(input)))
		if w.Code != http.StatusBadRequest {
			t.Fatalf("accepted invalid result %q: %d", input, w.Code)
		}
	}
}

func TestClipboardLimitAppliesAfterJSONDecoding(t *testing.T) {
	s, err := New(testToken, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	request := queueForTest(t, s, "clipboard")
	text := strings.Repeat("\x00", MaxClipboardBytes)
	payload, err := json.Marshal(map[string]string{"text": text})
	if err != nil {
		t.Fatal(err)
	}
	w := httptest.NewRecorder()
	s.ServeHTTP(w, authRequest(t, http.MethodPost, "/v1/requests/"+request.ID+"/result", bytes.NewReader(payload)))
	if w.Code != http.StatusNoContent || s.tasks[request.ID].result.Text != text {
		t.Fatalf("escaped 1 MiB result rejected: %d %s", w.Code, w.Body.String())
	}
	request = queueForTest(t, s, "clipboard")
	payload, _ = json.Marshal(map[string]string{"text": strings.Repeat("x", MaxClipboardBytes+1)})
	w = httptest.NewRecorder()
	s.ServeHTTP(w, authRequest(t, http.MethodPost, "/v1/requests/"+request.ID+"/result", bytes.NewReader(payload)))
	if w.Code != http.StatusBadRequest {
		t.Fatalf("oversized result accepted: %d", w.Code)
	}
}

func TestPromptLimitCountsUnicodeCharacters(t *testing.T) {
	s, err := New(testToken, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	for _, count := range []int{500, 501} {
		body, _ := json.Marshal(map[string]string{"kind": "clipboard", "prompt": strings.Repeat("한", count)})
		w := httptest.NewRecorder()
		s.ServeHTTP(w, authRequest(t, http.MethodPost, "/v1/requests", bytes.NewReader(body)))
		want := http.StatusCreated
		if count > 500 {
			want = http.StatusBadRequest
		}
		if w.Code != want {
			t.Fatalf("%d Unicode characters: got %d want %d", count, w.Code, want)
		}
	}
}

func TestStatusAndLateResults(t *testing.T) {
	s, err := New(testToken, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	request := queueForTest(t, s, "clipboard")
	status := func(want int) {
		t.Helper()
		w := httptest.NewRecorder()
		s.ServeHTTP(w, authRequest(t, http.MethodGet, "/v1/requests/"+request.ID+"/status", nil))
		if w.Code != want {
			t.Fatalf("status: got %d want %d", w.Code, want)
		}
	}
	status(http.StatusNoContent)
	s.mu.Lock()
	s.tasks[request.ID].request.ExpiresAt = time.Now().Add(-time.Second)
	s.mu.Unlock()
	w := httptest.NewRecorder()
	s.ServeHTTP(w, authRequest(t, http.MethodPost, "/v1/requests/"+request.ID+"/result", strings.NewReader(`{"text":"too late"}`)))
	if w.Code != http.StatusConflict {
		t.Fatalf("late answer accepted: %d", w.Code)
	}
	status(http.StatusGone)
	if s.tasks[request.ID].result.Text != "" || s.tasks[request.ID].result.Error == "" {
		t.Fatal("late answer leaked into result")
	}
}

func TestUTF8FilenameAndDuplicateUpload(t *testing.T) {
	dir := t.TempDir()
	s, err := New(testToken, dir)
	if err != nil {
		t.Fatal(err)
	}
	request := queueForTest(t, s, "file")
	w := httptest.NewRecorder()
	r := authRequest(t, http.MethodPut, "/v1/requests/"+request.ID+"/file", strings.NewReader("original"))
	r.Header.Set("X-Filename", "fallback.pdf")
	r.Header.Set("X-Filename-Encoded", "%2E%2E%2F%ED%95%9C%EA%B8%80+%20%ED%8C%8C%EC%9D%BC.pdf")
	s.ServeHTTP(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("UTF8 upload: %d %s", w.Code, w.Body.String())
	}
	path := s.tasks[request.ID].result.Path
	if filepath.Base(path) != request.ID+"-한글+ 파일.pdf" {
		t.Fatalf("filename lost: %s", path)
	}
	w = httptest.NewRecorder()
	r = authRequest(t, http.MethodPut, "/v1/requests/"+request.ID+"/file", strings.NewReader("replacement"))
	r.Header.Set("X-Filename", "different.pdf")
	s.ServeHTTP(w, r)
	if w.Code != http.StatusConflict {
		t.Fatalf("duplicate upload: %d", w.Code)
	}
	data, err := os.ReadFile(path)
	if err != nil || string(data) != "original" {
		t.Fatalf("completed file changed: %q %v", data, err)
	}
	files, err := os.ReadDir(dir)
	if err != nil || len(files) != 1 {
		t.Fatalf("duplicate upload left files: %v %v", files, err)
	}
}

func TestTokenValidation(t *testing.T) {
	for _, token := range []string{"short", testToken + "\n", testToken + " ", strings.Repeat("x", 4097), testToken + "한"} {
		if err := ValidateToken(token); err == nil {
			t.Fatal("invalid token accepted")
		}
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestClientCancellationBeforeWaitStillCancelsQueuedTask(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	cancelled := false
	client := &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		switch r.URL.Path {
		case "/v1/requests":
			cancel()
			return &http.Response{StatusCode: http.StatusCreated, Body: io.NopCloser(strings.NewReader(`{"id":"0123456789abcdef0123456789abcdef"}`)), Header: make(http.Header)}, nil
		case "/v1/requests/0123456789abcdef0123456789abcdef/wait":
			return nil, r.Context().Err()
		case "/v1/requests/0123456789abcdef0123456789abcdef/cancel":
			if r.Context().Err() != nil || r.Header.Get("Authorization") != "Bearer "+testToken {
				t.Fatal("cleanup reused cancelled context or lost authentication")
			}
			cancelled = true
			return &http.Response{StatusCode: http.StatusNoContent, Body: http.NoBody, Header: make(http.Header)}, nil
		default:
			t.Fatalf("unexpected request: %s", r.URL.Path)
			return nil, nil
		}
	})}
	if _, err := ClientRequest(ctx, client, "http://relay.test", testToken, "file", ""); err == nil {
		t.Fatal("cancelled client request succeeded")
	}
	if !cancelled {
		t.Fatal("queued request survived cancellation before wait")
	}
}

type zeroReader struct{}

func (zeroReader) Read(p []byte) (int, error) {
	clear(p)
	return len(p), nil
}

func TestOversizedUploadLeavesNoFileAndCanRetry(t *testing.T) {
	dir := t.TempDir()
	s, err := New(testToken, dir)
	if err != nil {
		t.Fatal(err)
	}
	request := queueForTest(t, s, "file")
	w := httptest.NewRecorder()
	r := authRequest(t, http.MethodPut, "/v1/requests/"+request.ID+"/file", io.LimitReader(zeroReader{}, MaxFileBytes+1))
	r.Header.Set("X-Filename", "note.txt")
	s.ServeHTTP(w, r)
	if w.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversized upload accepted: %d", w.Code)
	}
	files, err := os.ReadDir(dir)
	if err != nil || len(files) != 0 {
		t.Fatalf("failed upload left files: %v %v", files, err)
	}
	w = httptest.NewRecorder()
	r = authRequest(t, http.MethodPut, "/v1/requests/"+request.ID+"/file", strings.NewReader("retry"))
	r.Header.Set("X-Filename", "note.txt")
	s.ServeHTTP(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("failed upload could not retry: %d %s", w.Code, w.Body.String())
	}
}

type gatedReader struct {
	reader  io.Reader
	started chan struct{}
	release chan struct{}
	once    sync.Once
}

func (r *gatedReader) Read(p []byte) (int, error) {
	r.once.Do(func() { close(r.started) })
	<-r.release
	return r.reader.Read(p)
}

func TestConcurrentUploadsDoNotWriteMultipleFiles(t *testing.T) {
	dir := t.TempDir()
	s, err := New(testToken, dir)
	if err != nil {
		t.Fatal(err)
	}
	request := queueForTest(t, s, "file")
	body := &gatedReader{reader: strings.NewReader("original"), started: make(chan struct{}), release: make(chan struct{})}
	finished := make(chan int, 1)
	go func() {
		w := httptest.NewRecorder()
		r := authRequest(t, http.MethodPut, "/v1/requests/"+request.ID+"/file", body)
		r.Header.Set("X-Filename", "first.txt")
		s.ServeHTTP(w, r)
		finished <- w.Code
	}()
	select {
	case <-body.started:
	case <-time.After(time.Second):
		t.Fatal("first upload did not begin")
	}
	w := httptest.NewRecorder()
	r := authRequest(t, http.MethodPut, "/v1/requests/"+request.ID+"/file", strings.NewReader("duplicate"))
	r.Header.Set("X-Filename", "second.txt")
	s.ServeHTTP(w, r)
	close(body.release)
	if w.Code != http.StatusConflict {
		t.Fatalf("parallel upload accepted: %d", w.Code)
	}
	select {
	case code := <-finished:
		if code != http.StatusOK {
			t.Fatalf("original upload failed: %d", code)
		}
	case <-time.After(time.Second):
		t.Fatal("original upload did not finish")
	}
	files, err := os.ReadDir(dir)
	if err != nil || len(files) != 1 {
		t.Fatalf("parallel upload left files: %v %v", files, err)
	}
}

func TestBaseURLValidation(t *testing.T) {
	for _, value := range []string{"http://localhost:8787", "https://proxy.example/", "http://[::1]:8787"} {
		if err := ValidateBaseURL(value); err != nil {
			t.Fatalf("valid origin %q: %v", value, err)
		}
	}
	for _, value := range []string{"", "ftp://proxy.example", "http:///host", "http://user:secret@host", "http://host/path", "http://host/?token=x", "http://host/?", "http://host/#secret", "http://host:0", "http://host:65536", "http://host:", "http://host:abc"} {
		if err := ValidateBaseURL(value); err == nil {
			t.Fatalf("invalid origin accepted: %q", value)
		}
	}
}

func TestClientDoesNotFollowProxyRedirects(t *testing.T) {
	var redirected atomic.Bool
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		redirected.Store(true)
		w.WriteHeader(http.StatusCreated)
	}))
	defer target.Close()
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, target.URL+"/v1/requests", http.StatusTemporaryRedirect)
	}))
	defer proxy.Close()
	_, err := ClientRequest(context.Background(), proxy.Client(), proxy.URL, testToken, "file", "")
	if err == nil || !strings.Contains(err.Error(), "307") {
		t.Fatalf("redirect was not returned as failure: %v", err)
	}
	if redirected.Load() {
		t.Fatal("proxy redirect received the authenticated request")
	}
}

func TestCancellationInterruptsStalledHTTPUpload(t *testing.T) {
	dir := t.TempDir()
	s, err := New(testToken, dir)
	if err != nil {
		t.Fatal(err)
	}
	request := queueForTest(t, s, "file")
	server := httptest.NewServer(s)
	defer server.Close()
	conn, err := net.DialTimeout("tcp", server.Listener.Addr().String(), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if _, err := fmt.Fprintf(conn, "PUT /v1/requests/%s/file HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer %s\r\nX-Filename: stalled.txt\r\nTransfer-Encoding: chunked\r\n\r\n1\r\nx\r\n", request.ID, testToken); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(time.Second)
	for {
		s.mu.Lock()
		started := s.tasks[request.ID].uploading
		s.mu.Unlock()
		if started {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("stalled upload did not begin")
		}
		time.Sleep(time.Millisecond)
	}
	res, err := server.Client().Do(authRequest(t, http.MethodPost, server.URL+"/v1/requests/"+request.ID+"/cancel", nil))
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != http.StatusNoContent {
		t.Fatalf("cancel: %d", res.StatusCode)
	}
	if err := conn.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatal(err)
	}
	res, err = http.ReadResponse(bufio.NewReader(conn), &http.Request{Method: http.MethodPut})
	if err != nil {
		t.Fatalf("cancel did not interrupt upload: %v", err)
	}
	res.Body.Close()
	if res.StatusCode != http.StatusConflict {
		t.Fatalf("cancelled upload: %d", res.StatusCode)
	}
	files, err := os.ReadDir(dir)
	if err != nil || len(files) != 0 {
		t.Fatalf("cancelled upload left file: %v %v", files, err)
	}
}

func TestCancellationBeforeCreationBlocksLateRequest(t *testing.T) {
	s, err := New(testToken, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	const id = "abcdef0123456789abcdef0123456789"
	w := httptest.NewRecorder()
	s.ServeHTTP(w, authRequest(t, http.MethodPost, "/v1/requests/"+id+"/cancel", nil))
	if w.Code != http.StatusNoContent {
		t.Fatalf("early cancellation: %d", w.Code)
	}
	w = httptest.NewRecorder()
	s.ServeHTTP(w, authRequest(t, http.MethodPost, "/v1/requests", strings.NewReader(`{"id":"`+id+`","kind":"clipboard"}`)))
	if w.Code != http.StatusConflict {
		t.Fatalf("cancelled request was created later: %d", w.Code)
	}
	valid := queueForTest(t, s, "clipboard")
	w = httptest.NewRecorder()
	s.ServeHTTP(w, authRequest(t, http.MethodGet, "/v1/device/next", nil))
	var request Request
	if err := json.Unmarshal(w.Body.Bytes(), &request); err != nil {
		t.Fatal(err)
	}
	if request.ID != valid.ID {
		t.Fatalf("cancelled request appeared on phone: %+v", request)
	}
}

func TestClientCancellationBeforeCreateResponseStillCancelsKnownID(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var createdID, cancelledID string
	client := &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.URL.Path == "/v1/requests" {
			var input Request
			if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
				t.Fatal(err)
			}
			createdID = input.ID
			cancel()
			return nil, context.Canceled
		}
		parts := strings.Split(r.URL.Path, "/")
		if len(parts) != 5 || parts[4] != "cancel" {
			t.Fatalf("unexpected request: %s", r.URL.Path)
		}
		cancelledID = parts[3]
		return &http.Response{StatusCode: http.StatusNoContent, Body: http.NoBody, Header: make(http.Header)}, nil
	})}
	if _, err := ClientRequest(ctx, client, "http://relay.test", testToken, "file", ""); err == nil {
		t.Fatal("cancelled client request succeeded")
	}
	if !validRequestID(createdID) || createdID != cancelledID {
		t.Fatalf("lost request ID before create response: created=%q cancelled=%q", createdID, cancelledID)
	}
}

func TestLegacyRequestShapeKeepsServerDeadline(t *testing.T) {
	s, err := New(testToken, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(Request{Kind: "clipboard", ExpiresAt: time.Now().Add(24 * time.Hour)})
	w := httptest.NewRecorder()
	s.ServeHTTP(w, authRequest(t, http.MethodPost, "/v1/requests", bytes.NewReader(body)))
	var request Request
	if err := json.Unmarshal(w.Body.Bytes(), &request); err != nil {
		t.Fatalf("legacy create: %d %s %v", w.Code, w.Body.String(), err)
	}
	if w.Code != http.StatusCreated || request.ExpiresAt.After(time.Now().Add(2*time.Minute)) {
		t.Fatalf("legacy client controlled deadline: %d %+v", w.Code, request)
	}
}

func TestUploadDirectoryRejectsSymlinkWithoutChangingTarget(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "target")
	if err := os.Mkdir(target, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(target, 0755); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(dir, "uploads")
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	if _, err := New(testToken, link); err == nil {
		t.Fatal("upload directory symlink accepted")
	}
	info, err := os.Stat(target)
	if err != nil || info.Mode().Perm() != 0755 {
		t.Fatalf("unrelated target permission changed: %v %v", info, err)
	}
}
