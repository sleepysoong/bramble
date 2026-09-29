package mcp

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/sleepysoong/bramble/internal/relay"
)

func TestStdioInitializationAndTools(t *testing.T) {
	var output bytes.Buffer
	s := &Server{Output: &output}
	input := strings.NewReader("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}\n" +
		"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n" +
		"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n")
	if err := s.Run(input); err != nil {
		t.Fatal(err)
	}
	lines := bytes.Split(bytes.TrimSpace(output.Bytes()), []byte("\n"))
	if len(lines) != 2 {
		t.Fatalf("expected two responses, got %q", output.String())
	}
	for _, line := range lines {
		var message struct {
			ID     int                        `json:"id"`
			Result map[string]json.RawMessage `json:"result"`
		}
		if err := json.Unmarshal(line, &message); err != nil {
			t.Fatal(err)
		}
		switch message.ID {
		case 1:
			if len(message.Result["protocolVersion"]) == 0 {
				t.Fatal("initialize response missing protocol version")
			}
		case 2:
			var tools struct {
				Tools []struct {
					Name string `json:"name"`
				} `json:"tools"`
			}
			if err := json.Unmarshal(message.Result["tools"], &tools.Tools); err != nil {
				t.Fatal(err)
			}
			if len(tools.Tools) != 2 || tools.Tools[0].Name != "phone_read_clipboard" || tools.Tools[1].Name != "phone_pick_file" {
				t.Fatalf("tools: %+v", tools.Tools)
			}
		default:
			t.Fatalf("unexpected id %d", message.ID)
		}
	}
}

func TestToolCallViaPhone(t *testing.T) {
	const token = "0123456789abcdef0123456789abcdef"
	relayServer, err := relay.New(token, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	httpServer := httptest.NewServer(relayServer)
	defer httpServer.Close()
	var output bytes.Buffer
	s := &Server{BaseURL: httpServer.URL, Token: token, Client: &http.Client{Timeout: 5 * time.Second}, Output: &output}
	finished := make(chan struct{})
	go func() {
		defer close(finished)
		m := message{JSONRPC: "2.0", ID: json.RawMessage("7"), Method: "tools/call", Params: json.RawMessage(`{"name":"phone_read_clipboard","arguments":{"prompt":"test"}}`)}
		s.handle(m)
	}()
	request, err := http.NewRequest("GET", httpServer.URL+"/v1/device/next", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+token)
	client := &http.Client{Timeout: 5 * time.Second}
	res, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	var pending relay.Request
	if err := json.NewDecoder(res.Body).Decode(&pending); err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	answer, err := http.NewRequest("POST", httpServer.URL+"/v1/requests/"+pending.ID+"/result", strings.NewReader(`{"text":"phone text"}`))
	if err != nil {
		t.Fatal(err)
	}
	answer.Header.Set("Authorization", "Bearer "+token)
	res, err = client.Do(answer)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	select {
	case <-finished:
	case <-time.After(5 * time.Second):
		t.Fatal("MCP tool timed out")
	}
	if !strings.Contains(output.String(), "phone text") {
		t.Fatalf("MCP response: %s", output.String())
	}
}
