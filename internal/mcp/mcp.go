package mcp

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/sleepysoong/bramble/internal/relay"
)

const latestProtocolVersion = "2025-11-25"

// Version is filled by the release build with -ldflags -X.
var Version = "dev"

type message struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Method  string          `json:"method,omitempty"`
	Params  json.RawMessage `json:"params,omitempty"`
}

type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

type response struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Result  any             `json:"result,omitempty"`
	Error   *rpcError       `json:"error,omitempty"`
}

type runningCall struct {
	cancel context.CancelFunc
	method string
}

type Server struct {
	BaseURL string
	Token   string
	Client  *http.Client
	Output  io.Writer
	mu      sync.Mutex
}

func (s *Server) Run(input io.Reader) error {
	if s.Client == nil {
		s.Client = &http.Client{Timeout: 130 * time.Second}
	}
	if s.Output == nil {
		s.Output = os.Stdout
	}
	scanner := bufio.NewScanner(input)
	scanner.Buffer(make([]byte, 4096), 1<<20)
	var wg sync.WaitGroup
	var activeMu sync.Mutex
	active := make(map[string]runningCall)
	initialized := false
	for scanner.Scan() {
		line := scanner.Bytes()
		var m message
		if !utf8.Valid(line) || !json.Valid(line) {
			s.write(response{JSONRPC: "2.0", ID: json.RawMessage("null"), Error: &rpcError{-32700, "parse error"}})
			continue
		}
		if err := json.Unmarshal(line, &m); err != nil || m.JSONRPC != "2.0" || m.Method == "" || (len(m.ID) != 0 && !validID(m.ID)) || (len(m.Params) != 0 && !isObject(m.Params)) {
			s.write(response{JSONRPC: "2.0", ID: json.RawMessage("null"), Error: &rpcError{-32600, "invalid request"}})
			continue
		}
		if len(m.ID) == 0 {
			if m.Method == "notifications/cancelled" {
				var params struct {
					RequestID json.RawMessage `json:"requestId"`
				}
				if json.Unmarshal(m.Params, &params) == nil && validID(params.RequestID) {
					activeMu.Lock()
					if call, ok := active[requestKey(params.RequestID)]; ok && call.method != "initialize" {
						call.cancel()
					}
					activeMu.Unlock()
				}
			}
			continue
		}
		if m.Method == "initialize" {
			if initialized {
				s.write(response{JSONRPC: "2.0", ID: m.ID, Error: &rpcError{-32600, "already initialized"}})
			} else {
				initialized = s.handle(context.Background(), m)
			}
			continue
		}
		if !initialized && m.Method != "ping" {
			s.write(response{JSONRPC: "2.0", ID: m.ID, Error: &rpcError{-32600, "initialize first"}})
			continue
		}
		if m.Method != "tools/call" {
			s.handle(context.Background(), m)
			continue
		}
		activeMu.Lock()
		key := requestKey(m.ID)
		_, duplicate := active[key]
		if duplicate || len(active) >= 64 {
			activeMu.Unlock()
			s.write(response{JSONRPC: "2.0", ID: m.ID, Error: &rpcError{-32600, "duplicate request id or too many active calls"}})
			continue
		}
		ctx, cancel := context.WithCancel(context.Background())
		active[key] = runningCall{cancel: cancel, method: m.Method}
		activeMu.Unlock()
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer cancel()
			s.handle(ctx, m)
			activeMu.Lock()
			delete(active, key)
			activeMu.Unlock()
		}()
	}
	// Closing stdin is MCP shutdown: pending phone requests must be cancelled promptly.
	activeMu.Lock()
	for _, call := range active {
		call.cancel()
	}
	activeMu.Unlock()
	wg.Wait()
	return scanner.Err()
}

func validID(id json.RawMessage) bool {
	var value any
	decoder := json.NewDecoder(bytes.NewReader(id))
	decoder.UseNumber()
	if decoder.Decode(&value) != nil {
		return false
	}
	switch value := value.(type) {
	case string:
		return true
	case json.Number:
		return !strings.ContainsAny(string(value), ".eE")
	default:
		return false
	}
}

func requestKey(id json.RawMessage) string {
	var value any
	decoder := json.NewDecoder(bytes.NewReader(id))
	decoder.UseNumber()
	_ = decoder.Decode(&value)
	if text, ok := value.(string); ok {
		return "string:" + text
	}
	number := fmt.Sprint(value)
	if number == "-0" {
		number = "0"
	}
	return "number:" + number
}

func isObject(value json.RawMessage) bool {
	value = bytes.TrimSpace(value)
	return len(value) > 0 && value[0] == '{'
}

// handle returns whether an initialize request was accepted.
func (s *Server) handle(parent context.Context, m message) bool {
	result := response{JSONRPC: "2.0", ID: m.ID}
	initialized := false
	switch m.Method {
	case "initialize":
		var params struct {
			ProtocolVersion string                     `json:"protocolVersion"`
			Capabilities    map[string]json.RawMessage `json:"capabilities"`
			ClientInfo      *struct {
				Name    string `json:"name"`
				Version string `json:"version"`
			} `json:"clientInfo"`
		}
		if json.Unmarshal(m.Params, &params) != nil || params.ProtocolVersion == "" || params.Capabilities == nil || params.ClientInfo == nil || params.ClientInfo.Name == "" || params.ClientInfo.Version == "" {
			result.Error = &rpcError{-32602, "invalid initialize parameters"}
			break
		}
		version := params.ProtocolVersion
		switch version {
		case "2024-11-05", "2025-03-26", "2025-06-18", latestProtocolVersion:
		default:
			version = latestProtocolVersion
		}
		result.Result = map[string]any{
			"protocolVersion": version,
			"capabilities":    map[string]any{"tools": map[string]any{"listChanged": false}},
			"serverInfo":      map[string]string{"name": "bramble", "version": Version},
		}
		initialized = true
	case "ping":
		result.Result = map[string]any{}
	case "tools/list":
		result.Result = map[string]any{"tools": []any{
			map[string]any{"name": "phone_read_clipboard", "description": "Ask the phone owner to share the current clipboard text (up to 1 MiB of UTF-8).", "inputSchema": toolInputSchema()},
			map[string]any{"name": "phone_pick_file", "description": "Ask the phone owner to select and upload a file (up to 20 MiB). Returns its local path on the proxy host.", "inputSchema": toolInputSchema()},
		}}
	case "tools/call":
		var params struct {
			Name      string          `json:"name"`
			Arguments json.RawMessage `json:"arguments"`
		}
		if json.Unmarshal(m.Params, &params) != nil || params.Name == "" {
			result.Error = &rpcError{-32602, "invalid parameters"}
			break
		}
		var arguments struct {
			Prompt string `json:"prompt"`
		}
		if len(params.Arguments) != 0 {
			decoder := json.NewDecoder(bytes.NewReader(params.Arguments))
			decoder.DisallowUnknownFields()
			var rawArguments map[string]json.RawMessage
			_ = json.Unmarshal(params.Arguments, &rawArguments)
			prompt, supplied := rawArguments["prompt"]
			if !isObject(params.Arguments) || decoder.Decode(&arguments) != nil || (supplied && (len(prompt) == 0 || bytes.TrimSpace(prompt)[0] != '"')) {
				result.Result = toolResult("arguments must contain only an optional string prompt", true)
				break
			}
		}
		if utf8.RuneCountInString(arguments.Prompt) > relay.MaxPromptCharacters {
			result.Result = toolResult("prompt must contain at most 500 characters", true)
			break
		}
		kind := ""
		switch params.Name {
		case "phone_read_clipboard":
			kind = "clipboard"
		case "phone_pick_file":
			kind = "file"
		default:
			result.Error = &rpcError{-32602, "unknown tool"}
		}
		if kind == "" {
			break
		}
		ctx, cancel := context.WithTimeout(parent, 125*time.Second)
		defer cancel()
		out, err := relay.ClientRequest(ctx, s.Client, s.BaseURL, s.Token, kind, arguments.Prompt)
		if err != nil {
			result.Result = toolResult(err.Error(), true)
			break
		}
		if out.Error != "" {
			result.Result = toolResult(out.Error, true)
			break
		}
		if kind == "file" {
			result.Result = toolResult(out.Path, false)
		} else {
			result.Result = toolResult(out.Text, false)
		}
	default:
		result.Error = &rpcError{-32601, fmt.Sprintf("method %q not found", m.Method)}
	}
	if parent.Err() == nil {
		s.write(result)
	}
	return initialized
}

func (s *Server) write(result response) {
	s.mu.Lock()
	defer s.mu.Unlock()
	_ = json.NewEncoder(s.Output).Encode(result)
}

func toolInputSchema() any {
	return map[string]any{"type": "object", "properties": map[string]any{"prompt": map[string]any{"type": "string", "maxLength": relay.MaxPromptCharacters, "description": "Reason shown on the phone"}}, "additionalProperties": false}
}

func toolResult(value string, failed bool) any {
	return map[string]any{"content": []any{map[string]string{"type": "text", "text": value}}, "isError": failed}
}
