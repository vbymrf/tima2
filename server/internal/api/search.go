// Поиск по открытым уровням (doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §12): текст строки в поиске — первый узел
// текста, без знаков; у открытых уровней его берёт сервер. Зашифрованное ищется только на
// устройстве. Видно ровно то, что просящий и так может прочитать: граница — его уровень.
package api

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strings"
	"unicode/utf8"

	"github.com/klauspost/compress/zstd"
	"google.golang.org/protobuf/proto"

	"tima/server/internal/auth"
	pb "tima/server/internal/proto"
	"tima/server/internal/store"
)

// searchTextRunes — сколько знаков первого узла хранить для поиска и показа строки.
const searchTextRunes = 300

// maxSearchHits — сколько найденного отдавать за раз.
const maxSearchHits = 50

// openSearcher — что поиску нужно от хранилища.
type openSearcher interface {
	SearchOpen(ctx context.Context, channel bool, container, q string, maxLevel int16, limit int) ([]store.SearchHit, error)
}

// searchTextOf — первый узел текста открытого тела `zstd(protobuf(MessageBody))`. Не разобралось —
// пусто: поиск не находит, сообщение принимается как прежде.
func searchTextOf(payload []byte) string {
	dec, err := zstd.NewReader(bytes.NewReader(payload), zstd.WithDecoderMaxMemory(maxPlainPayloadBytes*4))
	if err != nil {
		return ""
	}
	defer dec.Close()
	raw, err := io.ReadAll(io.LimitReader(dec, maxPlainPayloadBytes*4))
	if err != nil {
		return ""
	}
	var body pb.MessageBody
	if proto.Unmarshal(raw, &body) != nil {
		return ""
	}
	text := ""
	for _, n := range body.GetNodes() {
		if strings.TrimSpace(n) != "" {
			text = strings.TrimSpace(n)
			break
		}
	}
	if text == "" {
		text = strings.TrimSpace(body.GetText())
	}
	return cutRunes(text, searchTextRunes)
}

func cutRunes(s string, n int) string {
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	return string([]rune(s)[:n])
}

func writeHits(w http.ResponseWriter, hits []store.SearchHit) {
	out := make([]map[string]any, 0, len(hits))
	for _, h := range hits {
		out = append(out, map[string]any{"message_id": h.Number, "text": h.Text, "created_at_unix_ms": h.AtMs})
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"hits": out})
}

func searchQuery(w http.ResponseWriter, r *http.Request) (string, bool) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if utf8.RuneCountInString(q) < 2 || utf8.RuneCountInString(q) > 100 {
		writeErr(w, http.StatusBadRequest, "bad_query", "запрос — от 2 до 100 знаков")
		return "", false
	}
	return q, true
}

// searchGroup — GET /groups/{groupID}/search?q=: открытые сообщения группы до уровня просящего.
func searchGroup(deps groupsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		g, role, ok := groupAndRole(deps, w, r)
		if !ok {
			return
		}
		q, ok := searchQuery(w, r)
		if !ok {
			return
		}
		hits, err := deps.store.SearchOpen(r.Context(), false, g.GroupID, q, readerMaxLevel(deps, r, role), maxSearchHits)
		if err != nil {
			log.Printf("searchGroup: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		writeHits(w, hits)
	}
}

// searchChannel — GET /channels/{channelID}/search?q=: записи канала до уровня просящего.
func searchChannel(st ChannelStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		channelID := r.PathValue("channelID")
		ch, err := st.GetChannel(r.Context(), channelID)
		if errors.Is(err, store.ErrChannelNotFound) {
			writeErr(w, http.StatusNotFound, "channel_not_found", "канал не найден")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		id, _ := auth.FromContext(r.Context())
		if !ch.IsPublic {
			if sub, _ := st.IsSubscribed(r.Context(), channelID, id.UserID); !sub && ch.OwnerID != id.UserID {
				writeErr(w, http.StatusNotFound, "channel_not_found", "канал не найден")
				return
			}
		}
		q, ok := searchQuery(w, r)
		if !ok {
			return
		}
		hits, err := st.SearchOpen(r.Context(), true, channelID, q, maxLevelInChannel(r, st, ch, id.UserID), maxSearchHits)
		if err != nil {
			log.Printf("searchChannel: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		writeHits(w, hits)
	}
}
