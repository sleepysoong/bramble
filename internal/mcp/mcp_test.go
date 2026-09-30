package mcp

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
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
	input := strings.NewReader("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}\n" +
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
		s.handle(context.Background(), m)
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

const initializeLine = `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}` + "\n"

func TestProtocolNegotiationAndInvalidMessages(t *testing.T) {
	for _, version := range []string{"2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25", "unknown-version"} {
		t.Run(version, func(t *testing.T) {
			var output bytes.Buffer
			s := &Server{Output: &output}
			input := strings.Replace(initializeLine, "2025-11-25", version, 1)
			if err := s.Run(strings.NewReader(input)); err != nil {
				t.Fatal(err)
			}
			var reply struct {
				Result struct {
					ProtocolVersion string `json:"protocolVersion"`
				} `json:"result"`
			}
			if err := json.Unmarshal(output.Bytes(), &reply); err != nil {
				t.Fatal(err)
			}
			want := version
			if version == "unknown-version" {
				want = latestProtocolVersion
			}
			if reply.Result.ProtocolVersion != want {
				t.Fatalf("version: got %q want %q", reply.Result.ProtocolVersion, want)
			}
		})
	}
	for _, test := range []struct {
		input string
		code  int
	}{
		{"{broken\n", -32700},
		{`{"jsonrpc":"1.0","id":1,"method":"ping"}` + "\n", -32600},
		{`{"jsonrpc":"2.0","id":null,"method":"ping"}` + "\n", -32600},
		{`{"jsonrpc":"2.0","id":1.5,"method":"ping"}` + "\n", -32600},
		{`{"jsonrpc":"2.0","id":1,"method":"tools/list"}` + "\n", -32600},
		{`{"jsonrpc":"2.0","id":1,"method":"initialize"}` + "\n", -32602},
	} {
		var output bytes.Buffer
		s := &Server{Output: &output}
		if err := s.Run(strings.NewReader(test.input)); err != nil {
			t.Fatal(err)
		}
		var reply response
		if err := json.Unmarshal(output.Bytes(), &reply); err != nil {
			t.Fatal(err)
		}
		if reply.Error == nil || reply.Error.Code != test.code {
			t.Fatalf("input %q: %+v", test.input, reply.Error)
		}
	}
}

func TestInvalidToolArgumentsDoNotContactPhone(t *testing.T) {
	for _, arguments := range []string{`{"unknown":true}`, `{"prompt":7}`, `{"prompt":null}`, `null`, `[]`, `{"prompt":"` + strings.Repeat("한", 501) + `"}`} {
		var output bytes.Buffer
		s := &Server{Output: &output}
		m := message{JSONRPC: "2.0", ID: json.RawMessage("2"), Method: "tools/call", Params: json.RawMessage(`{"name":"phone_read_clipboard","arguments":` + arguments + `}`)}
		s.handle(context.Background(), m)
		var reply struct {
			Result struct {
				IsError bool `json:"isError"`
			} `json:"result"`
		}
		if err := json.Unmarshal(output.Bytes(), &reply); err != nil {
			t.Fatal(err)
		}
		if !reply.Result.IsError {
			t.Fatalf("invalid arguments accepted: %s", arguments)
		}
	}
}

func TestCancellationAndStdioShutdownClosePhoneRequest(t *testing.T) {
	for _, shutdown := range []bool{false, true} {
		t.Run(fmt.Sprintf("shutdown=%t", shutdown), func(t *testing.T) {
			const token = "0123456789abcdef0123456789abcdef"
			relayServer, err := relay.New(token, t.TempDir())
			if err != nil {
				t.Fatal(err)
			}
			httpServer := httptest.NewServer(relayServer)
			defer httpServer.Close()
			input, writer := io.Pipe()
			defer input.Close()
			defer writer.Close()
			var output bytes.Buffer
			s := &Server{BaseURL: httpServer.URL, Token: token, Client: &http.Client{Timeout: 5 * time.Second}, Output: &output}
			finished := make(chan error, 1)
			go func() { finished <- s.Run(input) }()
			if _, err := io.WriteString(writer, initializeLine+`{"jsonrpc":"2.0","id":"call","method":"tools/call","params":{"name":"phone_read_clipboard","arguments":{}}}`+"\n"); err != nil {
				t.Fatal(err)
			}
			next, _ := http.NewRequest(http.MethodGet, httpServer.URL+"/v1/device/next", nil)
			next.Header.Set("Authorization", "Bearer "+token)
			client := &http.Client{Timeout: 5 * time.Second}
			res, err := client.Do(next)
			if err != nil {
				t.Fatal(err)
			}
			var pending relay.Request
			decodeErr := json.NewDecoder(res.Body).Decode(&pending)
			res.Body.Close()
			if decodeErr != nil {
				t.Fatal(decodeErr)
			}
			if !shutdown {
				// Escaped and literal string IDs refer to the same request.
				if _, err := io.WriteString(writer, `{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":"c\u0061ll"}}`+"\n"); err != nil {
					t.Fatal(err)
				}
				deadline := time.Now().Add(3 * time.Second)
				for {
					status, _ := http.NewRequest(http.MethodGet, httpServer.URL+"/v1/requests/"+pending.ID+"/status", nil)
					status.Header.Set("Authorization", "Bearer "+token)
					res, err := client.Do(status)
					if err != nil {
						t.Fatal(err)
					}
					res.Body.Close()
					if res.StatusCode == http.StatusGone {
						break
					}
					if time.Now().After(deadline) {
						t.Fatal("cancellation notification was ignored")
					}
					time.Sleep(10 * time.Millisecond)
				}
			}
			writer.Close()
			select {
			case err := <-finished:
				if err != nil {
					t.Fatal(err)
				}
			case <-time.After(4 * time.Second):
				t.Fatal("MCP kept cancelled phone request alive")
			}
			wait, _ := http.NewRequest(http.MethodGet, httpServer.URL+"/v1/requests/"+pending.ID+"/wait", nil)
			wait.Header.Set("Authorization", "Bearer "+token)
			res, err = client.Do(wait)
			if err != nil {
				t.Fatal(err)
			}
			var result relay.Result
			decodeErr = json.NewDecoder(res.Body).Decode(&result)
			res.Body.Close()
			if decodeErr != nil || result.Error != "request cancelled" {
				t.Fatalf("phone request did not cancel: %+v %v", result, decodeErr)
			}
			for _, line := range bytes.Split(bytes.TrimSpace(output.Bytes()), []byte("\n")) {
				var reply response
				if err := json.Unmarshal(line, &reply); err != nil {
					t.Fatal(err)
				}
				if string(reply.ID) == `"call"` {
					t.Fatalf("cancelled request produced a response: %s", line)
				}
			}
		})
	}
}
