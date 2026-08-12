// escrow-stub — stub-анклав escrow (escrow-legal-access.md §7, MVP/бета).
// ОТДЕЛЬНЫЙ процесс/контейнер: у tima нет доступа к состоянию анклава,
// tima видит только GET /v1/pubkey. Env: ESCROW_STATE_DIR (./escrow-data),
// ESCROW_LISTEN (:8090), ESCROW_RETENTION (6 месяцев), ESCROW_SWEEP_INTERVAL (1ч).
package main

import (
	"encoding/base64"
	"fmt"
	"log"
	"net/http"
	"os"
	"strconv"
	"time"

	"tima/server/internal/escrow"
)

func main() {
	dir := os.Getenv("ESCROW_STATE_DIR")
	if dir == "" {
		dir = "./escrow-data"
	}
	enc, newShares, err := escrow.Open(dir)
	if err != nil {
		log.Fatal(err)
	}
	if newShares != nil {
		// Единственный момент, когда доли существуют вне голов держателей.
		fmt.Println("=== ИНИЦИАЛИЗАЦИЯ ESCROW: доли Шамира (порог", escrow.SharesK, "из", escrow.SharesN, ") ===")
		fmt.Println("Раздайте держателям и УНИЧТОЖЬТЕ этот вывод; повторно доли не выдаются.")
		for _, s := range newShares {
			fmt.Println("  ", s)
		}
		fmt.Println("======================================================")
	}
	// Связка ключей эпох: ключ на пару «чат × эпоха», уничтожение по расписанию.
	// ESCROW_RETENTION — срок хранения по закону; ключ живёт (конец эпохи + retention),
	// поэтому даже сообщение последнего дня эпохи доступно положенный срок целиком.
	retention := envDuration("ESCROW_RETENTION", 6*30*24*time.Hour)
	ring, err := escrow.OpenKeyring(dir, retention)
	if err != nil {
		log.Fatal(err)
	}

	mux := http.NewServeMux()
	enc.Register(mux)
	enc.RegisterKeyring(mux, ring)

	// Уничтожение автоматическое. Если бы оно зависело от того, вспомнит ли кто-то
	// нажать кнопку, срок хранения снова стал бы обещанием, а не фактом.
	enc.StartSweeper(ring, envDuration("ESCROW_SWEEP_INTERVAL", time.Hour), nil)

	addr := os.Getenv("ESCROW_LISTEN")
	if addr == "" {
		addr = ":8090"
	}
	log.Printf("escrow-stub: слушаю %s (state: %s), срок хранения %v, legacy-ключ v%d",
		addr, dir, retention, enc.Version())
	// Ключ подписи конфига (Р2) — не секрет, печатаем при каждом старте: оператор
	// сверяет его с тем, что зашито в официальной сборке клиента, и замечает
	// расхождение сразу, а не когда об этом сообщит пользователь.
	log.Printf("escrow-stub: ключ подписи конфига (Ed25519, зашить в клиент): %s",
		base64.RawURLEncoding.EncodeToString(enc.SigningPublicKey()))
	log.Fatal(http.ListenAndServe(addr, mux))
}

// envDuration — длительность из окружения; голое число трактуем как дни.
func envDuration(name string, def time.Duration) time.Duration {
	v := os.Getenv(name)
	if v == "" {
		return def
	}
	if days, err := strconv.Atoi(v); err == nil {
		return time.Duration(days) * 24 * time.Hour
	}
	d, err := time.ParseDuration(v)
	if err != nil {
		log.Fatalf("%s: не число дней и не duration: %q", name, v)
	}
	return d
}
