// WebSocket-доставка (websocket-events.md): один WS на устройство, auth — device JWT
// в первом кадре, дальше live-поток событий из Redis Pub/Sub.
//
// РЕАЛИЗОВАНО (MVP): auth → ok → live (message.new, key.rotated); ping каждые 30 с;
// кадры — JSON (debug-транспорт по контракту; protobuf — вместе с клиентом).
// TODO(sync-offline.md §2): sync.pull/ack/cursor — нужен персистентный event log;
// до него догон после офлайна — REST-историей, WS отдаёт только live.
package api

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"time"

	"github.com/coder/websocket"

	"tima/server/internal/auth"
)

const (
	wsAuthTimeout  = 10 * time.Second
	wsPingInterval = 30 * time.Second // websocket-events.md: ping/pong каждые 30 с
)

func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	if s.Events == nil {
		writeErr(w, http.StatusServiceUnavailable, "no_events", "шина событий не сконфигурирована (REDIS_URL)")
		return
	}
	conn, err := websocket.Accept(w, r, nil)
	if err != nil {
		return // Accept сам ответил клиенту
	}
	defer conn.CloseNow()

	// Первый кадр — auth {token}
	authCtx, cancel := context.WithTimeout(r.Context(), wsAuthTimeout)
	var authFrame struct {
		Token string `json:"token"`
	}
	_, raw, err := conn.Read(authCtx)
	cancel()
	if err != nil || json.Unmarshal(raw, &authFrame) != nil {
		conn.Close(websocket.StatusPolicyViolation, "первый кадр — auth {token}")
		return
	}
	claims, err := s.Auth.Parse(authFrame.Token, auth.ScopeAccess)
	if err != nil {
		conn.Close(websocket.StatusPolicyViolation, "токен просрочен или подделан")
		return
	}
	deviceID := claims.DeviceID

	// Подписка ДО ok: после ok клиент вправе считать, что live-события не теряются
	sub, err := s.Events.Subscribe(r.Context(), deviceID)
	if err != nil {
		log.Printf("ws %s: subscribe: %v", deviceID, err)
		conn.Close(websocket.StatusInternalError, "шина событий недоступна")
		return
	}
	defer sub.Close()

	ctx := r.Context()
	if err := writeJSON(ctx, conn, map[string]any{"event": "ok", "device_id": deviceID}); err != nil {
		return
	}

	// Читатель клиентских кадров: MVP игнорирует (ack/typing — итерация sync),
	// но чтение обязательно — оно же обрабатывает pong и close.
	readErr := make(chan error, 1)
	go func() {
		for {
			if _, _, err := conn.Read(ctx); err != nil {
				readErr <- err
				return
			}
		}
	}()

	ping := time.NewTicker(wsPingInterval)
	defer ping.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-readErr:
			return // клиент закрылся
		case <-ping.C:
			pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
			err := conn.Ping(pingCtx)
			cancel()
			if err != nil {
				return
			}
		case msg, ok := <-sub.Frames():
			if !ok {
				conn.Close(websocket.StatusGoingAway, "шина событий закрылась")
				return
			}
			wctx, cancel := context.WithTimeout(ctx, 10*time.Second)
			err := conn.Write(wctx, websocket.MessageText, []byte(msg.Payload))
			cancel()
			if err != nil {
				return
			}
		}
	}
}

func writeJSON(ctx context.Context, conn *websocket.Conn, v any) error {
	raw, err := json.Marshal(v)
	if err != nil {
		return err
	}
	wctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	return conn.Write(wctx, websocket.MessageText, raw)
}
