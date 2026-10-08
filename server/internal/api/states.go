package api

// Лента состояний (ПЛАН-(ОП)-ОТМЕТОК-И-ПРИСУТСТВИЯ, ОП0–ОП4): «доставлено», «прочитано»,
// «печатает», «в сети» — только в личной переписке (решение 7).
//
// ── «ПРИДИ И ЗАБЕРИ» ДЛЯ ТОГО, ЧТО ЗАМЕНЯЕТСЯ ─────────────────────────────────
//
// Как лента звонков: изменение получает номер в ленте человека, его устройствам — сигнал
// `state.poke {rev}`, телефон забирает `GET /users/me/states?after=N`. Строка — последняя
// правда: «прочитано до» заменяет прежнее, а не копится рядом.
//
// ── КТО УЗНАЁТ (решение 9) ────────────────────────────────────────────────────
//
//   - «доставлено», «прочитано» — в списке отправителя;
//   - «печатает» — в списке собеседника, без подписок: в личной переписке он один;
//   - «в сети» — у самого человека; копия — тому, кто сейчас смотрит переписку с ним (`watch`).

import (
	"context"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"strconv"
	"time"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// Сроки — переменные: проверкам ждать по-настоящему незачем.
var (
	// «Печатает» без нового кадра гаснет через столько: телефон повторяет раз в 5 с.
	typingFor = 6 * time.Second
	// «В сети» без нового кадра: телефон на экране повторяет раз в 30 с.
	presenceWindow = 60 * time.Second
	// Подписка «смотрю переписку»: телефон подтверждает раз в минуту.
	watchFor = 2 * time.Minute
)

// StatesStore — что ручкам нужно от хранилища.
type StatesStore interface {
	ListStates(ctx context.Context, userID string, after int64) ([]store.StateRow, int64, error)
	ChatPeer(ctx context.Context, chatID, userID, deviceID string) (string, error)
	SetReceipt(ctx context.Context, r store.Receipt) (int64, error)
}

var _ StatesStore = (*store.Store)(nil)

// LiveStatesStore — что нужно кадрам живого канала: «доставлено» на подтверждение,
// «печатает», «в сети», «смотрю переписку».
type LiveStatesStore interface {
	SetReceipt(ctx context.Context, r store.Receipt) (int64, error)
	AckedDeliveries(ctx context.Context, deviceID, userID string, upTo int64) ([]store.Receipt, error)
	SetTyping(ctx context.Context, ownerID, fromID, chatID string, untilMs int64) (int64, error)
	SetDevicePresence(ctx context.Context, deviceID, userID string, foreground, gone bool, window time.Duration) (store.Presence, bool, error)
	UserPresence(ctx context.Context, userID string, window time.Duration) (store.Presence, error)
	WatchPresence(ctx context.Context, watcherDevice, watcherID, targetID string, until time.Time) error
	PresenceWatchers(ctx context.Context, targetID string) ([]string, error)
	SetPresenceState(ctx context.Context, ownerID, targetID string, p store.Presence) (int64, error)
}

var _ LiveStatesStore = (*store.Store)(nil)

// liveStates — кадры живого канала про состояния. Свой тип, а не методы Server: обработчику
// нужны хранилище состояний и уведомитель, а не всё, что видит сервер.
type liveStates struct {
	st LiveStatesStore
	n  *Notifier
}

// RegisterStates — лента состояний и «прочитано».
func RegisterStates(mux *http.ServeMux, st StatesStore, n *Notifier, requireDevice Middleware) {
	mux.HandleFunc("GET /api/v1/users/me/states", requireDevice(listStates(st)))
	mux.HandleFunc("PUT /api/v1/chats/{chatID}/read", requireDevice(readChat(st, n)))
}

// StatePoke — «в ленте состояний есть до №rev» всем устройствам человека.
func (n *Notifier) StatePoke(ctx context.Context, userID string, rev int64) {
	bus := n.bus()
	if bus == nil || rev <= 0 {
		return
	}
	devices, err := n.store.ListDevices(ctx, userID)
	if err != nil {
		log.Printf("state poke %s: devices: %v", userID, err)
		return
	}
	for _, d := range devices {
		if err := bus.Publish(ctx, d.DeviceID, map[string]any{"event": "state.poke", "rev": rev}); err != nil {
			log.Printf("state poke %s: publish: %v", d.DeviceID, err)
		}
	}
}

// listStates — GET /users/me/states?after=N → {"rev": вершина, "states": [...]}.
func listStates(st StatesStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		after, _ := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)
		rows, top, err := st.ListStates(r.Context(), id.UserID, after)
		if err != nil {
			log.Printf("listStates %s: %v", id.UserID, err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		out := make([]map[string]any, 0, len(rows))
		for _, s := range rows {
			m := map[string]any{"kind": s.Kind, "rev": s.Rev}
			switch s.Kind {
			case "receipt":
				m["chat_id"], m["peer_id"], m["delivered_ms"], m["read_ms"] = s.ChatID, s.PeerID, s.DeliveredMs, s.ReadMs
			case "typing":
				m["chat_id"], m["from_id"], m["until_ms"] = s.ChatID, s.PeerID, s.UntilMs
			case "presence":
				m["user_id"], m["online"], m["until_ms"], m["last_seen_ms"] = s.PeerID, s.Online, s.UntilMs, s.LastSeenMs
			}
			out = append(out, m)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"rev": top, "states": out, "now_ms": time.Now().UnixMilli()})
	}
}

// readChat — PUT /chats/{chatID}/read {"up_to_ms": T}: прочитал сообщения собеседника,
// написанные до T. Отметка — в список собеседника, ему сигнал.
func readChat(st StatesStore, n *Notifier) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		chatID := r.PathValue("chatID")
		var req struct {
			UpToMs int64 `json:"up_to_ms"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 1024)).Decode(&req); err != nil || req.UpToMs <= 0 {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен up_to_ms")
			return
		}
		peer, err := st.ChatPeer(r.Context(), chatID, id.UserID, id.DeviceID)
		if err != nil {
			log.Printf("readChat %s: %v", chatID, err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if peer == "" {
			writeErr(w, http.StatusNotFound, "chat_not_found", "переписки нет")
			return
		}
		rev, err := st.SetReceipt(r.Context(), store.Receipt{OwnerID: peer, ChatID: chatID, PeerID: id.UserID, ReadMs: req.UpToMs})
		if err != nil {
			log.Printf("readChat %s: %v", chatID, err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		n.StatePoke(r.Context(), peer, rev)
		w.WriteHeader(http.StatusNoContent)
	}
}

// deliveredOnAck — устройство подтвердило журнал до upTo: забранные личные сообщения стали
// «доставлено» у их отправителей. Зовётся ДО сдвига курсора: считается то, что между прежним
// курсором и новым. Беда хранилища здесь — не повод терять подтверждение.
func (l liveStates) deliveredOnAck(ctx context.Context, deviceID, userID string, upTo int64) {
	got, err := l.st.AckedDeliveries(ctx, deviceID, userID, upTo)
	if err != nil {
		log.Printf("ws %s: доставлено: %v", deviceID, err)
		return
	}
	for _, d := range got {
		rev, err := l.st.SetReceipt(ctx, d)
		if err != nil {
			log.Printf("ws %s: доставлено %s: %v", deviceID, d.ChatID, err)
			continue
		}
		l.n.StatePoke(ctx, d.OwnerID, rev)
	}
}

// typingFrame — кадр `typing {chat_id, to, on}`: «печатаю тебе» в список собеседника.
func (l liveStates) typingFrame(ctx context.Context, userID string, f wsClientFrame) {
	if f.To == "" || f.To == userID || f.ChatID == "" {
		return
	}
	var until int64
	if f.On != nil && *f.On {
		until = time.Now().Add(typingFor).UnixMilli()
	}
	rev, err := l.st.SetTyping(ctx, f.To, userID, f.ChatID, until)
	if err != nil {
		log.Printf("ws: печатает %s → %s: %v", userID, f.To, err)
		return
	}
	l.n.StatePoke(ctx, f.To, rev)
}

// presenceChanged — сказать смотрящим переписку с userID, что с ним сейчас.
func (l liveStates) presenceChanged(ctx context.Context, userID string, p store.Presence) {
	watchers, err := l.st.PresenceWatchers(ctx, userID)
	if err != nil {
		log.Printf("в сети %s: смотрящие: %v", userID, err)
		return
	}
	for _, w := range watchers {
		rev, err := l.st.SetPresenceState(ctx, w, userID, p)
		if err != nil {
			log.Printf("в сети %s → %s: %v", userID, w, err)
			continue
		}
		l.n.StatePoke(ctx, w, rev)
	}
}

// presenceFrame — кадр `presence {on}` или закрытие соединения (`gone`).
func (l liveStates) presenceFrame(ctx context.Context, deviceID, userID string, on, gone bool) {
	p, was, err := l.st.SetDevicePresence(ctx, deviceID, userID, on, gone, presenceWindow)
	if err != nil {
		log.Printf("ws %s: в сети: %v", deviceID, err)
		return
	}
	// На экране — каждый кадр продлевает «в сети» у смотрящих; ушёл — одно изменение.
	if p.Online || was {
		l.presenceChanged(ctx, userID, p)
	}
}

// watchFrame — кадр `watch {user_id}` / `unwatch {user_id}`: смотрю переписку с человеком.
// На `watch` смотрящий сразу получает, что с ним сейчас.
func (l liveStates) watchFrame(ctx context.Context, deviceID, userID string, f wsClientFrame, on bool) {
	if f.UserID == "" || f.UserID == userID {
		return
	}
	var until time.Time
	if on {
		until = time.Now().Add(watchFor)
	}
	if err := l.st.WatchPresence(ctx, deviceID, userID, f.UserID, until); err != nil {
		log.Printf("ws %s: смотрю %s: %v", deviceID, f.UserID, err)
		return
	}
	if !on {
		return
	}
	p, err := l.st.UserPresence(ctx, f.UserID, presenceWindow)
	if err != nil {
		log.Printf("ws %s: в сети %s: %v", deviceID, f.UserID, err)
		return
	}
	rev, err := l.st.SetPresenceState(ctx, userID, f.UserID, p)
	if err != nil {
		log.Printf("ws %s: в сети %s: %v", deviceID, f.UserID, err)
		return
	}
	l.n.StatePoke(ctx, userID, rev)
}
