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
//
// Право — `roomCreate`, а не `roomAdmin`: так у LiveKit, и это измерено, а не выведено
// (см. `ServiceToken`). Пока слался `roomAdmin`, ручка отвечала 401, и комнаты не
// закрывались вовсе.
func (c *RoomClient) DeleteRoom(ctx context.Context, room string) error {
	if c == nil {
		return nil // LiveKit не сконфигурирован — молча пропускаем
	}
	token, err := c.Issuer.ServiceToken(VideoGrant{RoomCreate: true}, roomAdminTTL, time.Now())
	if err != nil {
		return err
	}
	err = c.post(ctx, token, "DeleteRoom", map[string]any{"room": room}, nil)
	// Комнаты нет — значит звонок и так кончился. Это ответ, а не беда.
	if errors.Is(err, errNoRoom) {
		return nil
	}
	return err
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
	token, err := c.Issuer.ServiceToken(VideoGrant{RoomList: true}, roomAdminTTL, time.Now())
	if err != nil {
		return false, err
	}
	if err := c.post(ctx, token, "ListRooms", map[string]any{"names": []string{room}}, &answer); err != nil {
		return false, err
	}
	return len(answer.Rooms) > 0, nil
}

var errNoLiveKit = errors.New("LiveKit не настроен")

// RemoveParticipant выкидывает одного участника, не трогая остальных.
// Право — `roomAdmin` на КОНКРЕТНУЮ комнату: участники чужой комнаты таким токеном
// недосягаемы. В отличие от DeleteRoom, здесь это верное право, и оно же самое узкое.
func (c *RoomClient) RemoveParticipant(ctx context.Context, room, identity string) error {
	if c == nil {
		return nil
	}
	token, err := c.Issuer.RoomAdminToken(room, roomAdminTTL, time.Now())
	if err != nil {
		return err
	}
	return c.post(ctx, token, "RemoveParticipant", map[string]any{"room": room, "identity": identity}, nil)
}

// RoomParticipant — участник комнаты со слов LiveKit: кто и какие дорожки публикует.
type RoomParticipant struct {
	Identity string `json:"identity"`
	Tracks   []struct {
		Sid    string `json:"sid"`
		Source string `json:"source"` // MICROPHONE · CAMERA · SCREEN_SHARE …
		Muted  bool   `json:"muted"`
	} `json:"tracks"`
}

// ListParticipants — кто сейчас в комнате. Нужен командам создателя группового звонка:
// выключить чужой микрофон можно только по номеру дорожки, а его знает LiveKit.
func (c *RoomClient) ListParticipants(ctx context.Context, room string) ([]RoomParticipant, error) {
	if c == nil {
		return nil, errNoLiveKit
	}
	token, err := c.Issuer.RoomAdminToken(room, roomAdminTTL, time.Now())
	if err != nil {
		return nil, err
	}
	var answer struct {
		Participants []RoomParticipant `json:"participants"`
	}
	if err := c.post(ctx, token, "ListParticipants", map[string]any{"room": room}, &answer); err != nil {
		return nil, err
	}
	return answer.Participants, nil
}

// MuteTrack выключает чужую дорожку (ПЛАН-ГРУППОВЫХ-ЗВОНКОВ, решение 6). Только
// выключает: включить её обратно может сам участник, а не сервер (LiveKit по умолчанию
// включать чужое и не даёт).
func (c *RoomClient) MuteTrack(ctx context.Context, room, identity, trackSid string) error {
	if c == nil {
		return nil
	}
	token, err := c.Issuer.RoomAdminToken(room, roomAdminTTL, time.Now())
	if err != nil {
		return err
	}
	return c.post(ctx, token, "MutePublishedTrack", map[string]any{
		"room": room, "identity": identity, "track_sid": trackSid, "muted": true,
	}, nil)
}

// SetPublishSources — что участнику можно публиковать прямо сейчас: запрет создателя
// группового звонка (уточнение заказчика 2026-10-01: «сервер перестаёт принимать от
// него»). `sources` — `MICROPHONE`, `CAMERA`; пусто — ничего, участник только смотрит.
// Дорожки запрещённого источника LiveKit снимает сам.
func (c *RoomClient) SetPublishSources(ctx context.Context, room, identity string, sources []string) error {
	if c == nil {
		return nil
	}
	token, err := c.Issuer.RoomAdminToken(room, roomAdminTTL, time.Now())
	if err != nil {
		return err
	}
	if sources == nil {
		sources = []string{}
	}
	return c.post(ctx, token, "UpdateParticipant", map[string]any{
		"room": room, "identity": identity,
		"permission": map[string]any{
			"can_subscribe": true, "can_publish": len(sources) > 0, "can_publish_data": true,
			"can_update_metadata": true, "can_publish_sources": sources,
		},
	}, nil)
}

// SetRoomMetadata — данные комнаты, которые видят все в ней. Пауза группового звонка
// ложится сюда (решение 5): вошедший видит её сразу, перемену — событием комнаты.
func (c *RoomClient) SetRoomMetadata(ctx context.Context, room, metadata string) error {
	if c == nil {
		return nil
	}
	token, err := c.Issuer.RoomAdminToken(room, roomAdminTTL, time.Now())
	if err != nil {
		return err
	}
	return c.post(ctx, token, "UpdateRoomMetadata", map[string]any{"room": room, "metadata": metadata}, nil)
}

// post — twirp-вызов RoomService. Токен приходит снаружи: право у каждой ручки своё,
// и выбирать его обязан тот, кто знает, что зовёт.
//
// `into` необязателен: DeleteRoom довольно знать, что не упало, а ListRooms — нет.
func (c *RoomClient) post(ctx context.Context, token, method string, payload map[string]any, into any) error {
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
	if resp.StatusCode == http.StatusNotFound {
		return errNoRoom
	}
	if resp.StatusCode != http.StatusOK {
		answer, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return fmt.Errorf("LiveKit %s: статус %d: %s", method, resp.StatusCode, answer)
	}
	if into == nil {
		return nil
	}
	return json.NewDecoder(resp.Body).Decode(into)
}

// errNoRoom — LiveKit ответил «такой комнаты нет». Для закрытия это успех, а не беда.
var errNoRoom = errors.New("комнаты в LiveKit нет")
