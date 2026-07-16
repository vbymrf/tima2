// tima — модульный монолит бэкенда TIMA (doc/07-deployment/server-setup.md §5):
// один бинарник, подкоманды serve (по умолчанию) | worker | migrate.
package main

import (
	"context"
	"crypto/rand"
	"fmt"
	"log"
	"net/http"
	"net/http/pprof"
	"os"
	"os/signal"
	"runtime"
	"strconv"
	"syscall"
	"time"

	"tima/server/internal/api"
	"tima/server/internal/auth"
	"tima/server/internal/blob"
	"tima/server/internal/calls"
	"tima/server/internal/events"
	"tima/server/internal/ratelimit"
	"tima/server/internal/store"
	"tima/server/internal/worker"
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
		runWorker()
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

// envDays читает срок из env: голое число — дни, иначе time.ParseDuration (h/m/s).
func envDays(name string, defDays int) time.Duration {
	v := os.Getenv(name)
	if v == "" {
		return time.Duration(defDays) * 24 * time.Hour
	}
	if days, err := strconv.Atoi(v); err == nil {
		return time.Duration(days) * 24 * time.Hour
	}
	if d, err := time.ParseDuration(v); err == nil {
		return d
	}
	log.Fatalf("%s: не число дней и не duration: %q", name, v)
	return 0
}

// runWorker — фоновые задачи (GC ретеншена, internal/worker). Env:
// TIMA_GC_INTERVAL (duration, 1h), TIMA_RETENTION_DAYS (90), TIMA_APPEAL_WINDOW_DAYS (30).
func runWorker() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	st := mustStore(ctx)
	defer st.Close()
	if err := st.Migrate(ctx, migrations.FS); err != nil {
		log.Fatal(err)
	}
	interval := time.Hour
	if v := os.Getenv("TIMA_GC_INTERVAL"); v != "" {
		d, err := time.ParseDuration(v)
		if err != nil {
			log.Fatalf("TIMA_GC_INTERVAL: %v", err)
		}
		interval = d
	}
	w := &worker.Worker{
		Store:        st,
		Retention:    envDays("TIMA_RETENTION_DAYS", 90),
		AppealWindow: envDays("TIMA_APPEAL_WINDOW_DAYS", 30),
	}
	w.Run(ctx, interval)
}

// atoiOr — целое из env или def, если не задано/не число.
func atoiOr(name string, def int) int {
	if v := os.Getenv(name); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
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
			// Переопределение лимитов auth (0 → прод-дефолт): dev/нагрузочные прогоны
			SMSPerPhone:   atoiOr("TIMA_RL_SMS_PER_PHONE", 0),
			SMSPerIP:      atoiOr("TIMA_RL_SMS_PER_IP", 0),
			VerifyPerCode: atoiOr("TIMA_RL_VERIFY_PER_CODE", 0),
		}
		if redisURL := os.Getenv("REDIS_URL"); redisURL != "" {
			bus, err := events.New(ctx, redisURL)
			if err != nil {
				log.Fatal(err)
			}
			srv.Events = bus
			limiter, err := ratelimit.New(ctx, redisURL)
			if err != nil {
				log.Fatal(err)
			}
			srv.Limit = limiter
			log.Print("WS-доставка и rate limiting подключены (Redis)")
		} else {
			log.Print("REDIS_URL не задан — /ws отвечает 503, лимитов частоты нет, доставка только REST-историей")
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
		if lk := calls.NewIssuer(os.Getenv("LIVEKIT_API_KEY"), os.Getenv("LIVEKIT_API_SECRET")); lk != nil {
			srv.Calls = lk
			srv.LiveKitURL = os.Getenv("LIVEKIT_URL")
			log.Printf("Звонки: LiveKit-токены подключены (%s)", srv.LiveKitURL)
		} else {
			log.Print("LIVEKIT_API_KEY/SECRET не заданы — /calls отвечает 503")
		}
		if escrowURL := os.Getenv("ESCROW_URL"); escrowURL != "" {
			srv.EscrowURL = escrowURL
			log.Printf("Escrow: публичный ключ проксируется из %s", escrowURL)
		} else {
			log.Print("ESCROW_URL не задан — /escrow/pubkey отвечает 503 (подними cmd/escrow-stub)")
		}
		srv.Register(mux)
		log.Print("Auth + Message Service подключены")
	} else {
		log.Print("DATABASE_URL не задан — поднят только healthz")
	}

	// Сервис отладки: pprof (heap/goroutine/CPU-профили) + /debug/stats.
	// Включается TIMA_DEBUG_ADDR (напр. 127.0.0.1:6060). НЕ вешать на 0.0.0.0
	// в проде без файрвола — профили раскрывают внутренности процесса.
	if dbgAddr := os.Getenv("TIMA_DEBUG_ADDR"); dbgAddr != "" {
		startDebugServer(dbgAddr)
	}

	addr := os.Getenv("LISTEN_ADDR")
	if addr == "" {
		addr = ":8080"
	}
	log.Printf("tima serve: слушаю %s", addr)
	log.Fatal(http.ListenAndServe(addr, mux))
}

// startDebugServer поднимает отдельный HTTP-сервер отладки (pprof + краткая
// сводка runtime). Отдельный порт/listener: диагностику видно, даже если
// основной обработчик залип, и её легко закрыть файрволом.
func startDebugServer(addr string) {
	dbg := http.NewServeMux()
	dbg.HandleFunc("/debug/pprof/", pprof.Index)
	dbg.HandleFunc("/debug/pprof/cmdline", pprof.Cmdline)
	dbg.HandleFunc("/debug/pprof/profile", pprof.Profile) // CPU-профиль ?seconds=N
	dbg.HandleFunc("/debug/pprof/symbol", pprof.Symbol)
	dbg.HandleFunc("/debug/pprof/trace", pprof.Trace)

	// /debug/stats — сводка одним взглядом (без инструментов): растущие
	// NumGoroutine или HeapAlloc между запросами = утечка.
	dbg.HandleFunc("/debug/stats", func(w http.ResponseWriter, _ *http.Request) {
		var m runtime.MemStats
		runtime.ReadMemStats(&m)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"goroutines":%d,"heap_alloc_mb":%.1f,"heap_objects":%d,"num_gc":%d,"sys_mb":%.1f}`,
			runtime.NumGoroutine(),
			float64(m.HeapAlloc)/1024/1024, m.HeapObjects, m.NumGC, float64(m.Sys)/1024/1024)
	})

	log.Printf("сервис отладки: слушаю %s (pprof: /debug/pprof/, сводка: /debug/stats)", addr)
	go func() {
		if err := http.ListenAndServe(addr, dbg); err != nil {
			log.Printf("сервис отладки остановлен: %v", err)
		}
	}()
}
