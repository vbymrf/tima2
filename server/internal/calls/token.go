// Package calls — выдача токенов доступа LiveKit (calls-livekit.md §4). JWT
// подписывается API-секретом LiveKit НА БЭКЕНДЕ; клиент секрета не знает. Формат —
// стандартный LiveKit access token (HS256, claim `video` с grants). Сам медиа-сервер
// LiveKit для выдачи токена не нужен — нужен при реальном подключении клиента.
package calls

import (
	"errors"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// VideoGrant — права в комнате (подмножество LiveKit VideoGrant, calls-livekit.md §4).
type VideoGrant struct {
	Room         string `json:"room,omitempty"`
	RoomJoin     bool   `json:"roomJoin,omitempty"`
	RoomAdmin    bool   `json:"roomAdmin,omitempty"`  // участники КОНКРЕТНОЙ комнаты
	RoomCreate   bool   `json:"roomCreate,omitempty"` // создать и УДАЛИТЬ комнату — право служебное, не на комнату
	RoomList     bool   `json:"roomList,omitempty"`   // спросить список комнат
	CanPublish   *bool  `json:"canPublish,omitempty"`
	CanSubscribe *bool  `json:"canSubscribe,omitempty"`
}

type claims struct {
	Video VideoGrant `json:"video"`
	Name  string     `json:"name,omitempty"`
	jwt.RegisteredClaims
}

// Issuer выпускает LiveKit-токены. APIKey/APISecret — из конфигурации LiveKit.
type Issuer struct {
	APIKey    string
	APISecret string
}

var ErrNotConfigured = errors.New("LiveKit не сконфигурирован (LIVEKIT_API_KEY/SECRET)")

func NewIssuer(apiKey, apiSecret string) *Issuer {
	if apiKey == "" || apiSecret == "" {
		return nil
	}
	return &Issuer{APIKey: apiKey, APISecret: apiSecret}
}

// Token — access-токен для подключения identity к room. TTL на подключение
// короткий (calls-livekit.md §3: 2 мин); canPublish/canSubscribe — по роли.
func (i *Issuer) Token(room, identity string, canPublish bool, ttl time.Duration, now time.Time) (string, error) {
	if i == nil {
		return "", ErrNotConfigured
	}
	pub, sub := canPublish, true
	c := claims{
		Video: VideoGrant{Room: room, RoomJoin: true, CanPublish: &pub, CanSubscribe: &sub},
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer:    i.APIKey, // LiveKit: iss = API key
			Subject:   identity, // identity = user_id:device_id
			ExpiresAt: jwt.NewNumericDate(now.Add(ttl)),
			NotBefore: jwt.NewNumericDate(now.Add(-10 * time.Second)),
			IssuedAt:  jwt.NewNumericDate(now),
		},
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, c).SignedString([]byte(i.APISecret))
}

// ServiceToken — токен для служебных ручек RoomService.
//
// ── ПРАВА У КАЖДОЙ РУЧКИ СВОИ, И ЭТО ИЗМЕРЕНО ──────────────────────────────
//
// Здесь на все случаи слался `roomAdmin`, и `DeleteRoom` отвечал 401 — комнаты не
// закрывались всё время, пока звонки существовали. В логе это лежало строкой
// «комната не закрылась», и нашлось только потому, что строку читали глазами.
//
// Спрошено у живого LiveKit 2026-09-20, ответы такие:
//
//	ListRooms   roomAdmin+room → 401 · roomCreate → 401 · roomCreate+roomList → 200
//	DeleteRoom  roomAdmin+room → 401 · roomCreate → 404 (то есть право есть, комнаты нет)
//
// Отсюда: `DeleteRoom` требует `roomCreate`, `ListRooms` — `roomList`, и оба права
// **служебные**: они не привязаны к комнате. Поэтому даются по одному и на минуту, а не
// пачкой: утёкший `roomCreate` закрывает ЛЮБУЮ комнату, а не ту, ради которой выдан.
func (i *Issuer) ServiceToken(grant VideoGrant, ttl time.Duration, now time.Time) (string, error) {
	if i == nil {
		return "", ErrNotConfigured
	}
	c := claims{
		Video: grant,
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer:    i.APIKey,
			Subject:   "tima-backend",
			ExpiresAt: jwt.NewNumericDate(now.Add(ttl)),
			NotBefore: jwt.NewNumericDate(now.Add(-10 * time.Second)),
			IssuedAt:  jwt.NewNumericDate(now),
		},
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, c).SignedString([]byte(i.APISecret))
}

// RoomAdminToken — токен для управления комнатой (RoomService): закрыть её,
// выкинуть участника. Права намеренно другие, чем у Token: roomAdmin без roomJoin,
// то есть подключиться к медиа по нему нельзя. Если такой токен утечёт, им можно
// сломать звонок, но не подслушать его.
func (i *Issuer) RoomAdminToken(room string, ttl time.Duration, now time.Time) (string, error) {
	if i == nil {
		return "", ErrNotConfigured
	}
	c := claims{
		Video: VideoGrant{Room: room, RoomAdmin: true},
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer:    i.APIKey,
			Subject:   "tima-backend",
			ExpiresAt: jwt.NewNumericDate(now.Add(ttl)),
			NotBefore: jwt.NewNumericDate(now.Add(-10 * time.Second)),
			IssuedAt:  jwt.NewNumericDate(now),
		},
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, c).SignedString([]byte(i.APISecret))
}
