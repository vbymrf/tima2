package api

import (
	"context"
	"net/http"
	"time"

	"tima/server/internal/ratelimit"
	"tima/server/internal/store"
)

// Устройства и привязка нового устройства по QR — шаг 4, пятая группа.
//
// Две части одной темы: список своих устройств с отзывом и добавление нового.
// Отзыв устройства обязан просить ротацию ключей во всех группах человека, и код
// этого требования живёт здесь же — поэтому группа одна.

// DeviceStore — что устройствам и привязке нужно от хранилища.
type DeviceStore interface {
	ListUserDevices(ctx context.Context, userID string) ([]store.UserDevice, error)
	ListDevices(ctx context.Context, userID string) ([]store.Device, error)
	CountActiveDevices(ctx context.Context, userID string) (int, error)
	IsActiveDevice(ctx context.Context, userID, deviceID string) (bool, error)
	RevokeDevice(ctx context.Context, userID, deviceID string) error
	DevicePlatform(ctx context.Context, deviceID string) (string, error)
	SetDevicePlatform(ctx context.Context, deviceID, platform string) error
	SigningKey(ctx context.Context, deviceID, userID string) ([]byte, error)

	// Привязка по QR
	CreateLinkSession(ctx context.Context, encryptionPub, signingPub []byte, deviceName string,
		secretHash, claimTokenHash []byte, expiresAt time.Time) (string, error)
	GetLinkSessionForConfirm(ctx context.Context, sessionID string) (store.LinkSession, error)
	ConfirmLinkSession(ctx context.Context, sessionID string, secretHash []byte, userID string) (string, error)
	ClaimLinkSession(ctx context.Context, sessionID string, claimTokenHash []byte) (string, string, error)

	// Отзыв устройства просит ротацию во всех группах человека
	ListGroupsForUser(ctx context.Context, userID string) ([]store.MyGroup, error)
	ListGroupMembers(ctx context.Context, groupID string) ([]store.Member, error)
}

var _ DeviceStore = (*store.Store)(nil)

// TokenIssuer — единственное, что нужно привязке от подсистемы входа: выдать
// access-токен устройству, которое только что подтвердили.
type TokenIssuer interface {
	IssueAccess(userID, deviceID string) (string, error)
}

// devicesDeps — зависимости группы.
type devicesDeps struct {
	store    DeviceStore
	limiter  func() *ratelimit.Limiter
	tokens   func() TokenIssuer
	notifier *Notifier
}

// RegisterDevices — шесть маршрутов: три про свои устройства, три про привязку.
//
// link/start и link/claim регистрируются БЕЗ requireDevice сознательно: их зовёт
// устройство, которое ещё не привязано и токена не имеет. Защита у них другая —
// ограничение частоты по IP внутри handler-ов.
func RegisterDevices(
	mux *http.ServeMux,
	st DeviceStore,
	limit func() *ratelimit.Limiter,
	tokens func() TokenIssuer,
	n *Notifier,
	requireDevice Middleware,
) {
	deps := devicesDeps{store: st, limiter: limit, tokens: tokens, notifier: n}

	mux.HandleFunc("GET /api/v1/devices", requireDevice(listMyDevices(deps)))
	mux.HandleFunc("PUT /api/v1/devices/me/platform", requireDevice(setMyPlatform(deps)))
	mux.HandleFunc("DELETE /api/v1/devices/{deviceID}", requireDevice(revokeDevice(deps)))

	mux.HandleFunc("POST /api/v1/link/start", linkStart(deps))
	mux.HandleFunc("POST /api/v1/link/confirm", requireDevice(linkConfirm(deps)))
	mux.HandleFunc("POST /api/v1/link/claim", linkClaim(deps))
}
