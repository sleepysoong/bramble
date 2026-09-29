package relay

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
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
