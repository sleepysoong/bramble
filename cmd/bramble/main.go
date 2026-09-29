package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"github.com/sleepysoong/bramble/internal/mcp"
	"github.com/sleepysoong/bramble/internal/relay"
)

func main() {
	if len(os.Args) < 2 {
		usage()
	}
	switch os.Args[1] {
	case "token":
		token, err := relay.RandomToken()
		if err != nil {
			log.Fatal(err)
		}
		fmt.Println(token)
	case "serve":
		flags := flag.NewFlagSet("serve", flag.ExitOnError)
		listen := flags.String("listen", "127.0.0.1:8787", "HTTP listen address")
		data := flags.String("data", filepath.Join(os.TempDir(), "bramble-uploads"), "private upload directory")
		_ = flags.Parse(os.Args[2:])
		token := os.Getenv("BRAMBLE_TOKEN")
		if token == "" {
			log.Fatal("set BRAMBLE_TOKEN to a value from `bramble token`")
		}
		s, err := relay.New(token, *data)
		if err != nil {
			log.Fatal(err)
		}
		log.Printf("listening on %s; uploads in %s", *listen, *data)
		httpServer := &http.Server{Addr: *listen, Handler: s, ReadHeaderTimeout: 5 * time.Second}
		log.Fatal(httpServer.ListenAndServe())
	case "mcp":
		url := os.Getenv("BRAMBLE_SERVER")
		if url == "" {
			url = "http://127.0.0.1:8787"
		}
		token := os.Getenv("BRAMBLE_TOKEN")
		if token == "" {
			log.Fatal("set BRAMBLE_TOKEN")
		}
		s := &mcp.Server{BaseURL: url, Token: token, Output: os.Stdout}
		if err := s.Run(os.Stdin); err != nil {
			log.Fatal(err)
		}
	default:
		usage()
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: bramble token | serve [-listen address] [-data directory] | mcp")
	os.Exit(2)
}
