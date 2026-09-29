package mcp

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/sleepysoong/bramble/internal/relay"
)

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
	for scanner.Scan() {
		var m message
		if err := json.Unmarshal(scanner.Bytes(), &m); err != nil {
			continue
		}
		if len(m.ID) == 0 {
			continue
		} // Notifications need no reply.
		wg.Add(1)
		go func() { defer wg.Done(); s.handle(m) }()
	}
	wg.Wait()
	return scanner.Err()
}

func (s *Server) handle(m message) {
	result := response{JSONRPC: "2.0", ID: m.ID}
	switch m.Method {
	case "initialize":
		result.Result = map[string]any{
			"protocolVersion": "2025-06-18",
			"capabilities":    map[string]any{"tools": map[string]any{"listChanged": false}},
			"serverInfo":      map[string]string{"name": "bramble", "version": "0.1.0"},
		}
	case "ping":
		result.Result = map[string]any{}
	case "tools/list":
		result.Result = map[string]any{"tools": []any{
			map[string]any{"name": "phone_read_clipboard", "description": "Ask the phone owner to share the current clipboard text.", "inputSchema": map[string]any{"type": "object", "properties": map[string]any{"prompt": map[string]string{"type": "string", "description": "Reason shown on the phone"}}, "additionalProperties": false}},
			map[string]any{"name": "phone_pick_file", "description": "Ask the phone owner to select and upload a file (up to 20 MiB). Returns its local path on the proxy host.", "inputSchema": map[string]any{"type": "object", "properties": map[string]any{"prompt": map[string]string{"type": "string", "description": "Reason shown on the phone"}}, "additionalProperties": false}},
		}}
	case "tools/call":
		var params struct {
			Name      string `json:"name"`
			Arguments struct {
				Prompt string `json:"prompt"`
			} `json:"arguments"`
		}
		if err := json.Unmarshal(m.Params, &params); err != nil {
			result.Error = &rpcError{-32602, "invalid parameters"}
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
		ctx, cancel := context.WithTimeout(context.Background(), 125*time.Second)
		defer cancel()
		out, err := relay.ClientRequest(ctx, s.Client, s.BaseURL, s.Token, kind, params.Arguments.Prompt)
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
	s.mu.Lock()
	defer s.mu.Unlock()
	_ = json.NewEncoder(s.Output).Encode(result)
}

func toolResult(value string, failed bool) any {
	return map[string]any{"content": []any{map[string]string{"type": "text", "text": value}}, "isError": failed}
}
