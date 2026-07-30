package calls

// Управление комнатами LiveKit (RoomService) поверх обычного HTTP.
//
// # Почему без официального SDK
//
// `server-sdk-go` (и даже один `livekit/protocol`) тянет за собой больше пакетов,
// чем весь наш бэкенд целиком — измерено: 423 против 346. Нам из всего API нужны
// два вызова, а RoomService — это twirp: POST на /twirp/livekit.RoomService/<Метод>
// с JSON в теле и тем же JWT, который мы и так выпускаем. Сорок строк против
// удвоения поверхности сборки.
//
// Если однажды понадобится половина API — SDK станет оправдан, и это будет видно.
//
// # Зачем это вообще
//
// Без RoomService «завершить звонок» ничего не завершает: бэкенд менял состояние у
// себя и рассылал уведомление, а комната LiveKit жила до empty_timeout. Работало
// это лишь потому, что клиент сам отключался, услышав уведомление. Клиент, который
// его не получил или проигнорировал, продолжал публиковать звук.

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// roomAdminTTL — токен админской операции живёт ровно столько, сколько нужно на неё.
const roomAdminTTL = 30 * time.Second

// RoomClient — минимальный клиент RoomService.
type RoomClient struct {
	// URL — HTTP-адрес LiveKit (не wss://). Пусто → операции пропускаются.
	URL    string
	Issuer *Issuer
	HTTP   *http.Client
}

// NewRoomClient принимает адрес в любом виде: wss://host → https://host.
// Клиенту мы отдаём wss-адрес, и держать рядом второй параметр только ради схемы
// — лишний повод их рассинхронизировать.
func NewRoomClient(livekitURL string, issuer *Issuer) *RoomClient {
	if livekitURL == "" || issuer == nil {
		return nil
	}
	u := livekitURL
	switch {
	case strings.HasPrefix(u, "wss://"):
		u = "https://" + strings.TrimPrefix(u, "wss://")
	case strings.HasPrefix(u, "ws://"):
		u = "http://" + strings.TrimPrefix(u, "ws://")
	}
	return &RoomClient{
		URL:    strings.TrimRight(u, "/"),
		Issuer: issuer,
		HTTP:   &http.Client{Timeout: 5 * time.Second},
	}
}

// DeleteRoom закрывает комнату и отключает всех, кто в ней есть.
// Идемпотентна: несуществующая комната — не ошибка, звонок и так завершён.
func (c *RoomClient) DeleteRoom(ctx context.Context, room string) error {
	if c == nil {
		return nil // LiveKit не сконфигурирован — молча пропускаем
	}
	return c.call(ctx, "DeleteRoom", map[string]any{"room": room})
}

// RemoveParticipant выкидывает одного участника, не трогая остальных.
func (c *RoomClient) RemoveParticipant(ctx context.Context, room, identity string) error {
	if c == nil {
		return nil
	}
	return c.call(ctx, "RemoveParticipant", map[string]any{"room": room, "identity": identity})
}

// call — twirp-вызов с JSON-телом. Токен админский: roomAdmin на конкретную комнату,
// а не roomJoin. Разница существенная — этим токеном нельзя подключиться к медиа.
func (c *RoomClient) call(ctx context.Context, method string, payload map[string]any) error {
	token, err := c.Issuer.RoomAdminToken(payload["room"].(string), roomAdminTTL, time.Now())
	if err != nil {
		return err
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		c.URL+"/twirp/livekit.RoomService/"+method, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)

	resp, err := c.HTTP.Do(req)
	if err != nil {
		return fmt.Errorf("LiveKit %s: %w", method, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusOK {
		_, _ = io.Copy(io.Discard, resp.Body)
		return nil
	}
	detail, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<10))
	// «Комнаты нет» — штатный исход: она уже закрылась сама по empty_timeout.
	if bytes.Contains(detail, []byte("not_found")) || bytes.Contains(detail, []byte("requested room does not exist")) {
		return nil
	}
	return fmt.Errorf("LiveKit %s: статус %d: %s", method, resp.StatusCode, strings.TrimSpace(string(detail)))
}
