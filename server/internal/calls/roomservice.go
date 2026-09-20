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
	"errors"
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

// RoomExists — жива ли комната в LiveKit.
//
// ── ЗАЧЕМ СПРАШИВАТЬ, А НЕ СЧИТАТЬ ПО ТАЙМЕРУ ──────────────────────────────
//
// Состояние звонка в базе закрывает тот, кто его закончил: клиент запросом `/end` или
// вебхук LiveKit. Оба могут не доехать — приложение убили, телефон уснул, вебхук
// потерялся, — и строка остаётся открытой навсегда. Это не догадка: 2026-09-20 таких
// строк накопилось восемь, и одна из них на пять часов сделала двоих недоступными.
//
// Закрывать их по возрасту можно, но срок придётся брать с большим запасом: разговор
// вправе длиться часами, и короткий срок оборвал бы живой. Комната же знает правду
// сразу — её нет, значит и звонку неоткуда идти.
//
// **Ошибка здесь обязана быть в сторону «жива».** Не ответил LiveKit, сеть моргнула —
// возвращаем ошибку, и уборщик строку не трогает. Закрытый по недоразумению живой звонок
// хуже, чем призрак, проживший лишний час.
func (c *RoomClient) RoomExists(ctx context.Context, room string) (bool, error) {
	if c == nil {
		return false, errNoLiveKit
	}
	var answer struct {
		Rooms []struct {
			Name string `json:"name"`
		} `json:"rooms"`
	}
	if err := c.callJSON(ctx, "ListRooms", map[string]any{"room": room, "names": []string{room}}, &answer); err != nil {
		return false, err
	}
	return len(answer.Rooms) > 0, nil
}

var errNoLiveKit = errors.New("LiveKit не настроен")

// RemoveParticipant выкидывает одного участника, не трогая остальных.
func (c *RoomClient) RemoveParticipant(ctx context.Context, room, identity string) error {
	if c == nil {
		return nil
	}
	return c.call(ctx, "RemoveParticipant", map[string]any{"room": room, "identity": identity})
}

// callJSON — то же, что call, но с разбором ответа. Нужен там, где ответ и есть смысл
// вызова: DeleteRoom довольно знать, что не упало, а ListRooms — нет.
func (c *RoomClient) callJSON(ctx context.Context, method string, payload map[string]any, into any) error {
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
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("LiveKit %s: %s", method, resp.Status)
	}
	return json.NewDecoder(resp.Body).Decode(into)
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
