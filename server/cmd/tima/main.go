// tima — модульный монолит бэкенда TIMA (doc/07-deployment/server-setup.md §5):
// один бинарник, подкоманды serve (по умолчанию) | worker | migrate.
package main

import (
	"context"
	"crypto/rand"
	"fmt"
	"log"
	"net/http"
	"os"

	"tima/server/internal/api"
	"tima/server/internal/auth"
	"tima/server/internal/blob"
	"tima/server/internal/events"
	"tima/server/internal/store"
	"tima/server/migrations"
)

func main() {
	cmd := "serve"
	if len(os.Args) > 1 {
		cmd = os.Args[1]
	}
	switch cmd {
	case "serve":
		serve()
	case "migrate":
		migrate()
	case "worker":
		log.Fatal("worker: не реализован (фаза 2+: fan-out лент, push, GC медиа)")
	default:
		fmt.Fprintf(os.Stderr, "использование: tima [serve|worker|migrate]\n")
		os.Exit(2)
	}
}

func mustStore(ctx context.Context) *store.Store {
	url := os.Getenv("DATABASE_URL")
	if url == "" {
		log.Fatal("DATABASE_URL не задан (dev: postgres://tima:tima-dev-only@localhost:5432/tima)")
	}
	st, err := store.New(ctx, url)
	if err != nil {
		log.Fatal(err)
	}
	return st
}

func migrate() {
	ctx := context.Background()
	st := mustStore(ctx)
	defer st.Close()
	if err := st.Migrate(ctx, migrations.FS); err != nil {
		log.Fatal(err)
	}
	log.Println("миграции применены")
}

func serve() {
	mux := http.NewServeMux()
	healthz := func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"status":"ok"}`)
	}
	mux.HandleFunc("GET /healthz", healthz)        // для docker healthcheck
	mux.HandleFunc("GET /api/v1/healthz", healthz) // smoke-тест через Caddy

	// API поднимается при наличии DATABASE_URL; без него — только healthz.
	if os.Getenv("DATABASE_URL") != "" {
		ctx := context.Background()
		st := mustStore(ctx)
		if err := st.Migrate(ctx, migrations.FS); err != nil {
			log.Fatal(err)
		}
		key := []byte(os.Getenv("JWT_SIGNING_KEY"))
		if len(key) == 0 {
			key = make([]byte, 32)
			if _, err := rand.Read(key); err != nil {
				log.Fatal(err)
			}
			log.Print("ВНИМАНИЕ: JWT_SIGNING_KEY не задан — сгенерирован эфемерный (токены умрут с рестартом)")
		}
		srv := &api.Server{
			Store:  st,
			Auth:   auth.NewIssuer(key),
			DevSMS: os.Getenv("TIMA_DEV_SMS") == "1",
		}
		if redisURL := os.Getenv("REDIS_URL"); redisURL != "" {
			bus, err := events.New(ctx, redisURL)
			if err != nil {
				log.Fatal(err)
			}
			srv.Events = bus
			log.Print("WS-доставка подключена (Redis Pub/Sub)")
		} else {
			log.Print("REDIS_URL не задан — /ws отвечает 503, доставка только REST-историей")
		}
		if endpoint := os.Getenv("S3_ENDPOINT"); endpoint != "" {
			bucket := os.Getenv("S3_BUCKET")
			if bucket == "" {
				bucket = "media"
			}
			bl, err := blob.New(ctx, endpoint, os.Getenv("S3_ACCESS_KEY"), os.Getenv("S3_SECRET_KEY"), bucket)
			if err != nil {
				log.Fatal(err)
			}
			srv.Blob = bl
			log.Printf("Media Service подключён (bucket %s)", bucket)
		} else {
			log.Print("S3_ENDPOINT не задан — media-эндпоинты отвечают 503")
		}
		srv.Register(mux)
		log.Print("Auth + Message Service подключены")
	} else {
		log.Print("DATABASE_URL не задан — поднят только healthz")
	}

	addr := os.Getenv("LISTEN_ADDR")
	if addr == "" {
		addr = ":8080"
	}
	log.Printf("tima serve: слушаю %s", addr)
	log.Fatal(http.ListenAndServe(addr, mux))
}
