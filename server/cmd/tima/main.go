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
	"strings"
	"syscall"
	"time"

	"tima/server/internal/api"
	"tima/server/internal/auth"
	"tima/server/internal/blob"
	"tima/server/internal/calls"
	"tima/server/internal/events"
	"tima/server/internal/pii"
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
	// Ключ персональных данных. Лежит ФАЙЛОМ вне PostgreSQL: смысл схемы в том,
	// что дамп базы номеров не содержит (internal/pii). Каталог не должен попадать
	// в бэкапы базы. Нет файла — генерируется при первом старте.
	keyFile := os.Getenv("TIMA_PII_KEY_FILE")
	if keyFile == "" {
		keyFile = "./pii-data/pii-key.json"
	}
	cipher, err := pii.Load(keyFile)
	if err != nil {
		log.Fatalf("ключ персональных данных: %v", err)
	}
	st, err := store.New(ctx, url, cipher)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("Персональные данные: шифрование включено, ключ v%d (%s)", cipher.KeyID(), keyFile)
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
		// Обёртки под устройства — до конца эпохи плюс запас (ПЛАН-(ПС) Р5).
		WrapGrace: envDays("TIMA_WRAP_GRACE_DAYS", 30),
		// Срок содержимого личных сообщений (Р45): пусто — вместе с депозитарием, как было;
		// число — дней; forever — бессрочно.
		MessageContentDays: messageContentDays(os.Getenv("TIMA_MESSAGE_RETENTION")),
		// Спросить LiveKit, жива ли комната брошенного звонка. Ключей нет — уборщик
		// такие строки не трогает вовсе: закрывать их по одному возрасту значило бы
		// однажды оборвать живой разговор.
		Rooms: livekitRooms(),
	}
	w.Run(ctx, interval)
}

// livekitRooms — клиент управления комнатами для уборщика. `nil`, если LiveKit не
// настроен: половина ключей — это не настроенный LiveKit, а недонастроенный, и молчать
// об этом нельзя, иначе брошенные звонки копятся без объяснения.
func livekitRooms() *calls.RoomClient {
	key, secret := os.Getenv("LIVEKIT_API_KEY"), os.Getenv("LIVEKIT_API_SECRET")
	url := os.Getenv("LIVEKIT_URL")
	if key == "" || secret == "" || url == "" {
		log.Print("LIVEKIT_* не заданы — брошенные звонки не закрываются: спросить о комнате некого")
		return nil
	}
	return calls.NewRoomClient(url, calls.NewIssuer(key, secret))
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

// splitList — значения через запятую из env; пустые выбрасываются.
func splitList(v string) []string {
	var out []string
	for _, x := range strings.Split(v, ",") {
		if x = strings.TrimSpace(x); x != "" {
			out = append(out, x)
		}
	}
	return out
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
			// Доверие к устройствам (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ Р24): off | record | require.
			DeviceTrust: api.NormalizeDeviceTrust(os.Getenv("TIMA_DEVICE_TRUST")),
			// Аттестация телефона (ДУ8, Р21): off | record | require; пусто — off.
			Attestation: os.Getenv("TIMA_ATTESTATION"),
			// Годная аттестация (Р25, ЗБ2): корни и подписи APK — sha256 hex через запятую.
			AttestationPolicy: api.AttestationPolicy{
				Roots: splitList(os.Getenv("TIMA_ATTESTATION_ROOTS")),
				Apps:  splitList(os.Getenv("TIMA_ATTESTATION_APK_DIGESTS")),
			},
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
			// S3_PUBLIC_ENDPOINT — публичный адрес MinIO для presigned URL (клиент ходит
			// сюда). Пусто → presigned на внутреннем endpoint (dev/localhost).
			publicEndpoint := os.Getenv("S3_PUBLIC_ENDPOINT")
			bl, err := blob.New(ctx, endpoint, publicEndpoint, os.Getenv("S3_ACCESS_KEY"), os.Getenv("S3_SECRET_KEY"), bucket)
			if err != nil {
				log.Fatal(err)
			}
			srv.Blob = bl
			if publicEndpoint != "" {
				log.Printf("Media Service подключён (bucket %s, presigned → %s)", bucket, publicEndpoint)
			} else {
				log.Printf("Media Service подключён (bucket %s)", bucket)
			}
		} else {
			log.Print("S3_ENDPOINT не задан — media-эндпоинты отвечают 503")
		}
		if lk := calls.NewIssuer(os.Getenv("LIVEKIT_API_KEY"), os.Getenv("LIVEKIT_API_SECRET")); lk != nil {
			srv.Calls = lk
			srv.LiveKitURL = os.Getenv("LIVEKIT_URL")
			// Потолок видео звонка (ПЛАН-(В)-ВИДЕО.md В5б): CALL_VIDEO_WIDTH/HEIGHT/FPS/BITRATE,
			// без них — 1280×720, 24 кадра/с, 800 кбит/с.
			srv.CallVideo = api.VideoLimitsFromEnv(os.Getenv)
			log.Printf("Звонки: потолок видео %d×%d, %d к/с, %d бит/с",
				srv.CallVideo.Width, srv.CallVideo.Height, srv.CallVideo.FPS, srv.CallVideo.Bitrate)
			// Групповой звонок (ПЛАН-(ГЗ)-ГРУППОВЫХ-ЗВОНКОВ): CALL_GROUP_MAX, CALL_GROUP_TTL,
			// CALL_GROUP_HD_UPTO/HEIGHT, CALL_GROUP_SD_UPTO/HEIGHT.
			srv.CallGroups = api.GroupCallRulesFromEnv(os.Getenv)
			log.Printf("Групповые звонки: до %d участников, видео %v, временная группа %s",
				srv.CallGroups.Max, srv.CallGroups.Video, srv.CallGroups.TTL)
			// Управление комнатами: адрес берём из того же LIVEKIT_URL (wss → https),
			// чтобы не заводить второй параметр, который рассинхронизируется.
			srv.Rooms = calls.NewRoomClient(srv.LiveKitURL, lk)
			log.Printf("Звонки: LiveKit-токены и управление комнатами подключены (%s)", srv.LiveKitURL)
		} else {
			log.Print("LIVEKIT_API_KEY/SECRET не заданы — /calls отвечает 503")
		}
		if escrowURL := os.Getenv("ESCROW_URL"); escrowURL != "" {
			srv.EscrowURL = escrowURL
			srv.EscrowRegion = os.Getenv("ESCROW_REGION")
			if v := os.Getenv("ESCROW_OVERLAP_DAYS"); v != "" {
				srv.EscrowOverlap = envDays("ESCROW_OVERLAP_DAYS", 7)
			}
			// Печатаем ДЕЙСТВУЮЩИЙ регион, а не переменную окружения: пустая строка
			// в логе выглядит как «регион не настроен», хотя работает умолчание.
			log.Printf("Escrow: ключи эпох из %s (регион %s)", escrowURL, srv.EscrowRegionEffective())
		} else {
			log.Print("ESCROW_URL не задан — /escrow/pubkey отвечает 503 (подними cmd/escrow-stub)")
		}
		// Авто-обновление клиента (self-distributed пакеты): версия и ссылка из env.
		// Поток один на обе платформы: он говорит, какому ряду сборок принадлежит
		// предложение, а не на чём его ставят.
		stream := os.Getenv("APP_STREAM")
		if code := atoiOr("APP_LATEST_VERSION_CODE", 0); code > 0 {
			srv.AppVer = &api.AppVersion{
				VersionCode: code,
				VersionName: os.Getenv("APP_LATEST_VERSION_NAME"),
				PackageURL:  os.Getenv("APP_APK_URL"),
				Notes:       os.Getenv("APP_UPDATE_NOTES"),
				Stream:      stream,
				SHA256:      os.Getenv("APP_APK_SHA256"),
				Size:        int64(atoiOr("APP_APK_SIZE", 0)),
				MinClient:   atoiOr("APP_MIN_CLIENT", 0),
				Important:   os.Getenv("APP_IMPORTANT") == "1",
			}
			// Поток печатаем отдельно: без него клиент v2 предложение проигнорирует,
			// и молчащая вкладка «Обновление» выглядит как поломка, а не как настройка.
			if srv.AppVer.Stream == "" {
				log.Print("APP_STREAM не задан — клиенты, различающие потоки, это предложение пропустят")
			}
			log.Printf("Авто-обновление Android: версия %d поток %q (%s)",
				code, srv.AppVer.Stream, srv.AppVer.PackageURL)
		} else {
			log.Print("APP_LATEST_VERSION_CODE не задан — /app/version отдаёт 204 (обновления Android выключены)")
		}
		// ПК: те же поля с префиксом APP_WIN_. Отдельный номер, потому что платформы
		// выпускаются порознь: собранный MSI может отставать от APK на день, и общий
		// номер предложил бы человеку версию, которой для его платформы ещё нет.
		if code := atoiOr("APP_WIN_VERSION_CODE", 0); code > 0 {
			srv.AppVerWin = &api.AppVersion{
				VersionCode: code,
				VersionName: os.Getenv("APP_WIN_VERSION_NAME"),
				PackageURL:  os.Getenv("APP_MSI_URL"),
				Notes:       os.Getenv("APP_WIN_UPDATE_NOTES"),
				Stream:      stream,
				SHA256:      os.Getenv("APP_MSI_SHA256"),
				Size:        int64(atoiOr("APP_MSI_SIZE", 0)),
				MinClient:   atoiOr("APP_WIN_MIN_CLIENT", 0),
				Important:   os.Getenv("APP_WIN_IMPORTANT") == "1",
			}
			// Хэш для ПК — единственная проверка скачанного: подписи кода у пакета нет
			// (решение заказчика 2026-09-06). Без него клиент честно откажется ставить,
			// и человек увидит «сервер не объявил хэш», а не молчащую кнопку.
			if srv.AppVerWin.SHA256 == "" {
				log.Print("APP_MSI_SHA256 не задан — ПК не поставит это обновление: проверять скачанное будет нечем")
			}
			log.Printf("Авто-обновление ПК: версия %d поток %q (%s)",
				code, srv.AppVerWin.Stream, srv.AppVerWin.PackageURL)
		} else {
			log.Print("APP_WIN_VERSION_CODE не задан — /app/version?platform=windows отдаёт 204")
		}
		// Плановая смена ключа тихих групп (ADR-0017 §3): напоминание по расписанию.
		srv.EpochReminders = time.Hour
		// Перерегистрация (ДУ9, Р51): сроки — число дней или duration Go; на стенде минуты
		// ставятся только на время живой проверки.
		srv.Rereg = api.ReregTimes{
			Wait:        envDays("TIMA_REREG_WAIT", 90),
			Window:      envDays("TIMA_REREG_WINDOW", 30),
			DeleteAfter: envDays("TIMA_IDENTITY_DELETE_AFTER", 30),
		}
		srv.RunRereg = true
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

// messageContentDays — разбор TIMA_MESSAGE_RETENTION (Р45). Ошибка в настройке — отказ запуска:
// молча подставленное «по умолчанию» стёрло бы то, что велели хранить.
func messageContentDays(v string) int {
	switch v {
	case "", "escrow":
		return store.ContentWithEscrow
	case "forever":
		return store.ContentForever
	}
	days, err := strconv.Atoi(v)
	if err != nil || days < 1 {
		log.Fatalf("TIMA_MESSAGE_RETENTION: число дней ≥ 1, forever или пусто, получено %q", v)
	}
	return days
}
