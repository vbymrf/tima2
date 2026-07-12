// escrow-stub — stub-анклав escrow (escrow-legal-access.md §7, MVP/бета).
// ОТДЕЛЬНЫЙ процесс/контейнер: у tima нет доступа к состоянию анклава,
// tima видит только GET /v1/pubkey. Env: ESCROW_STATE_DIR (./escrow-data),
// ESCROW_LISTEN (:8090).
package main

import (
	"fmt"
	"log"
	"net/http"
	"os"

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
	mux := http.NewServeMux()
	enc.Register(mux)
	addr := os.Getenv("ESCROW_LISTEN")
	if addr == "" {
		addr = ":8090"
	}
	log.Printf("escrow-stub: ключ v%d, слушаю %s (state: %s)", enc.Version(), addr, dir)
	log.Fatal(http.ListenAndServe(addr, mux))
}
