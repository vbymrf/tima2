// tima — модульный монолит бэкенда TIMA (doc/07-deployment/server-setup.md §5):
// один бинарник, подкоманды serve (по умолчанию) | worker | migrate.
package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
)

func main() {
	cmd := "serve"
	if len(os.Args) > 1 {
		cmd = os.Args[1]
	}
	switch cmd {
	case "serve":
		serve()
	case "worker":
		log.Fatal("worker: не реализован (фаза 2+: fan-out лент, push, GC медиа)")
	case "migrate":
		log.Fatal("migrate: не реализован (появится вместе с Message Service)")
	default:
		fmt.Fprintf(os.Stderr, "использование: tima [serve|worker|migrate]\n")
		os.Exit(2)
	}
}

func serve() {
	mux := http.NewServeMux()
	healthz := func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"status":"ok"}`)
	}
	mux.HandleFunc("GET /healthz", healthz)        // для docker healthcheck
	mux.HandleFunc("GET /api/v1/healthz", healthz) // smoke-тест через Caddy

	addr := os.Getenv("LISTEN_ADDR")
	if addr == "" {
		addr = ":8080"
	}
	log.Printf("tima serve: слушаю %s", addr)
	log.Fatal(http.ListenAndServe(addr, mux))
}
