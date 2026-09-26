package api

// Лента звонков — своя переменная `cts` (ПЛАН-ВХОДЯЩЕГО-ЗВОНКА.md, ВЗ0а).
//
// ── КАК ИДЁТ ЗВОНОК ─────────────────────────────────────────────────────────
//
//   1. POST /calls — собеседнику изменение `ringing` и подсказка `call.poke {cts}`.
//   2. Телефон собеседника забирает ленту (GET /calls/updates?after=N), звонит и
//      подтверждает по каналу `call.ack {cts}`.
//   3. Подтверждение делает вызов доставленным: звонящему `delivered` — у него «Звонит».
//      Никто не подтвердил за 5 с — звонящему `unreachable` («не в сети»); звонок при
//      этом идёт дальше, и позднее подтверждение всё равно даст `delivered`.
//   4. Ответ, отказ, отмена, конец — изменение обеим сторонам. Никто не закрыл звонок за
//      срок — сервер закрывает его сам и пишет `missed` обоим.
//
// Групповые звонки идут прежней дорогой (журнал устройства): в план они не входят.

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

// Сроки — переменные, а не константы: проверкам иначе пришлось бы ждать по-настоящему.
var (
	// Через сколько сказать звонящему «не в сети», если вызов никто не подтвердил.
	// Проверяется ДОСТАВКА, а не ответ: звонок от этого не обрывается.
	deliveryNoticeAfter = 5 * time.Second

	// Повтор подсказки, пока вызов не доставлен. Подсказка по шине — «не более одного
	// раза»; строка в ленте при этом одна, повторяется только «приди и забери».
	ringRepokeAfter = []time.Duration{2 * time.Second, 6 * time.Second}

	// Срок звонка на сервере: сорок пять секунд вызова и запас на то, чтобы клиент успел
	// закрыть сам. Не закрыл — значит закрыть некому (оба без связи, приложение убито),
	// и без этого срока «пропущенный» не родился бы вовсе: уборщик ходит раз в час.
	ringDeadline = 50 * time.Second
)

// afterRing — всё, что звонок делает сам после того, как зазвонил.
//
// Свой контекст: запрос к этому времени давно закрыт, а его отмена унесла бы проверки.
func afterRing(deps callsDeps, callID, callerID, calleeID string, calleeCts int64) {
	go repokeRinging(deps, callID, calleeID, calleeCts)
	go noticeUndelivered(deps, callID, callerID)
	go closeUnanswered(deps, callID, callerID, calleeID)
}

func repokeRinging(deps callsDeps, callID, calleeID string, cts int64) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	for _, after := range ringRepokeAfter {
		select {
		case <-ctx.Done():
			return
		case <-time.After(after):
		}
		call, err := deps.store.GetCall(ctx, callID)
		if err != nil || call.State != "ringing" || !call.DeliveredAt.IsZero() {
			return // ответили, отменили или уже доставлено — звать незачем
		}
		deps.notifier.CallPoke(ctx, calleeID, cts)
	}
}

func noticeUndelivered(deps callsDeps, callID, callerID string) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	select {
	case <-ctx.Done():
		return
	case <-time.After(deliveryNoticeAfter):
	}
	call, err := deps.store.GetCall(ctx, callID)
	if err != nil || call.State != "ringing" || !call.DeliveredAt.IsZero() {
		return
	}
	deps.notifier.CallChange(ctx, callerID, callID, "unreachable", "")
}

func closeUnanswered(deps callsDeps, callID, callerID, calleeID string) {
	ctx, cancel := context.WithTimeout(context.Background(), ringDeadline+30*time.Second)
	defer cancel()
	select {
	case <-ctx.Done():
		return
	case <-time.After(ringDeadline):
	}
	call, err := deps.store.GetCall(ctx, callID)
	if err != nil || call.State != "ringing" {
		return // закрыли сами — так и должно быть почти всегда
	}
	if rooms := deps.rooms(); rooms != nil {
		if err := rooms.DeleteRoom(ctx, call.Room); err != nil {
			log.Printf("closeUnanswered: комната %s не закрылась: %v", call.Room, err)
		}
	}
	_ = deps.store.SetCallState(ctx, callID, "missed", "")
	deps.notifier.CallChange(ctx, callerID, callID, "missed", "")
	deps.notifier.CallChange(ctx, calleeID, callID, "missed", "")
}

// endChange — каким словом конец звонка ложится в ленту.
//
// Слово одно на обе стороны: `declined` — отклонил собеседник, `cancelled` — звонящий
// передумал, `busy` — собеседник занят, `ended` — разговор был. Кто из двоих что увидит,
// решает клиент: у собеседника `cancelled` — пропущенный, у звонящего — просто конец.
func endChange(call store.Call, enderID, state string) string {
	switch {
	case state == "busy":
		return "busy"
	case call.State != "ringing":
		return "ended"
	case enderID == call.PeerID:
		return "declined"
	default:
		return "cancelled"
	}
}

// listCallUpdates — GET /api/v1/calls/updates?after=N: лента звонков после моего номера.
//
// Ответ составлен **для спрашивающего устройства**: у `answered` сказано, здесь ли взяли
// трубку (`here`). Этим снимается адресность, из-за которой 23.09 `call.state` не смог
// стать подсказкой: сервер знает, КТО спрашивает.
//
// `gap` — начало ленты уже вычищено (живёт сутки): пропущенные телефон берёт из журнала
// звонков. Оставшиеся изменения отдаются всё равно — среди них может быть идущий звонок.
func listCallUpdates(deps callsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		after, _ := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)
		if after < 0 {
			after = 0
		}
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		if limit <= 0 || limit > 500 {
			limit = 100
		}
		top, err := deps.store.CallTop(r.Context(), id.UserID)
		if err != nil {
			log.Printf("listCallUpdates top: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		updates, err := deps.store.ListCallUpdates(r.Context(), id.UserID, after, limit)
		if err != nil {
			log.Printf("listCallUpdates: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		gap := top > after && (len(updates) == 0 || updates[0].Cts > after+1)
		out := make([]map[string]any, 0, len(updates))
		for _, u := range updates {
			out = append(out, map[string]any{
				"cts":     u.Cts,
				"call_id": u.CallID,
				"change":  u.Change,
				"here":    u.DeviceID != "" && u.DeviceID == id.DeviceID,
				"at":      u.At.UTC().Format(time.RFC3339Nano),
				"call":    callJSON(u.Call),
			})
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"top": top, "gap": gap, "more": len(updates) == limit, "updates": out,
		})
	}
}

// markCallsSeen — POST /api/v1/calls/seen {call_ids}: человек открыл журнал звонков.
//
// Решение заказчика 2026-09-26: строка «пропущенный» в шторке снимается на всех его
// устройствах. Доезжает до них изменением `seen` в ленте — тем же путём, что звонок.
// Чужой звонок молча пропускается: сказать «просмотрено» можно только про свой.
func markCallsSeen(deps callsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			CallIDs []string `json:"call_ids"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен call_ids")
			return
		}
		if len(req.CallIDs) > 200 {
			req.CallIDs = req.CallIDs[:200]
		}
		id, _ := auth.FromContext(r.Context())
		marked := 0
		for _, callID := range req.CallIDs {
			call, err := deps.store.GetCall(r.Context(), callID)
			if err != nil || (call.InitiatorID != id.UserID && call.PeerID != id.UserID) {
				continue
			}
			if deps.notifier.CallChange(r.Context(), id.UserID, callID, "seen", id.DeviceID) > 0 {
				marked++
			}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"marked": marked})
	}
}

// CallAckStore — что подтверждению ленты нужно от хранилища.
type CallAckStore interface {
	CallTop(ctx context.Context, userID string) (int64, error)
	SetCallCursor(ctx context.Context, deviceID string, cts int64) error
	DeliverCalls(ctx context.Context, userID string, upTo int64) ([]store.DeliveredCall, error)
}

// ackCalls — `call.ack {cts}` из канала: устройство применило ленту до №cts.
//
// **Номер выше вершины отвергается.** Это ошибка клиента, а не чужая программа: самый
// реальный случай — номер соседнего аккаунта после переключения на том же телефоне. Принять
// его значило бы сдвинуть курсор через изменения, которых ещё не было, и сказать
// звонящему «Звонит» про вызов, которого телефон не видел.
func ackCalls(ctx context.Context, st CallAckStore, n *Notifier, userID, deviceID string, cts int64) {
	if cts <= 0 {
		return
	}
	top, err := st.CallTop(ctx, userID)
	if err != nil {
		log.Printf("call.ack %s: вершина: %v", deviceID, err)
		return
	}
	if cts > top {
		log.Printf("call.ack %s: номер %d выше вершины %d — отвергнут", deviceID, cts, top)
		return
	}
	if err := st.SetCallCursor(ctx, deviceID, cts); err != nil {
		log.Printf("call.ack %s: курсор: %v", deviceID, err)
		return
	}
	delivered, err := st.DeliverCalls(ctx, userID, cts)
	if err != nil {
		log.Printf("call.ack %s: доставка: %v", deviceID, err)
		return
	}
	for _, d := range delivered {
		n.CallChange(ctx, d.InitiatorID, d.CallID, "delivered", "")
	}
}
