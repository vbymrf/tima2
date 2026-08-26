package api

import (
	"context"
	"net/http"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// Люди и аккаунт — шаг 4, седьмая группа: справочник, имена, личности, удаление.
//
// Эти маршруты жили в auth.go рядом со входом, и соседство было случайным: вход
// (SMS, регистрация) остаётся на Server как его законная роль, а поиск человека по
// номеру и смена личности — обычные операции над аккаунтом.

// UserStore — что группе нужно от хранилища.
type UserStore interface {
	FindUserByPhone(ctx context.Context, phone string) (string, error)
	FindUsersByPhones(ctx context.Context, phones []string) (map[string]string, error)
	PhonesOfChatPeers(ctx context.Context, userID string, ids []string) (map[string]string, error)
	DisplayNames(ctx context.Context, ids []string) (map[string]string, error)
	SetDisplayName(ctx context.Context, userID, name string) error
	IdentitiesOf(ctx context.Context, ids []string) (map[string]store.Identity, error)
	ListDevices(ctx context.Context, userID string) ([]store.Device, error)

	// Удаление аккаунта: пометка, стирание по сроку (ADR-0015)
	PersonOfUser(ctx context.Context, userID string) (string, error)
	RetentionDays(ctx context.Context, name string) (int, error)
	MarkAccountDeleted(ctx context.Context, personID string, purgeAfterDays int) error

	// Смена личности после «начать заново» и возврат по прежней фразе
	SetOrCheckIdentity(ctx context.Context, userID string, identityPub []byte) error
	StartNewIdentity(ctx context.Context, personID, linkedFrom string, proof []byte) (string, error)
	FindPriorIdentity(ctx context.Context, personID string, identityPub []byte) (string, error)
	MoveDeviceToUser(ctx context.Context, deviceID, newUserID string) error
}

var _ UserStore = (*store.Store)(nil)

// IdentityTokens — что смене личности нужно от подсистемы входа.
//
// Шире, чем TokenIssuer у привязки, и намеренно: здесь ещё выпускается и
// разбирается челлендж, доказывающий владение прежней фразой.
type IdentityTokens interface {
	IssueAccess(userID, deviceID string) (string, error)
	IssueReidentifyChallenge(userID string) (string, error)
	Parse(token, wantScope string) (*auth.Claims, error)
}

type usersDeps struct {
	store  UserStore
	tokens func() IdentityTokens
}

// RegisterUsers — восемь маршрутов: справочник, имена, личности, удаление аккаунта.
func RegisterUsers(mux *http.ServeMux, st UserStore, tokens func() IdentityTokens, requireDevice Middleware) {
	deps := usersDeps{store: st, tokens: tokens}

	mux.HandleFunc("GET /api/v1/users/lookup", requireDevice(lookupUser(deps)))
	mux.HandleFunc("POST /api/v1/users/discover", requireDevice(discoverContacts(deps)))
	mux.HandleFunc("PATCH /api/v1/users/me/name", requireDevice(setDisplayName(deps)))
	mux.HandleFunc("DELETE /api/v1/users/me", requireDevice(deleteAccount(deps)))
	mux.HandleFunc("POST /api/v1/users/names", requireDevice(resolveNames(deps)))
	mux.HandleFunc("POST /api/v1/users/identities", requireDevice(resolveIdentities(deps)))
	mux.HandleFunc("POST /api/v1/users/me/reidentify/challenge", requireDevice(reidentifyChallenge(deps)))
	mux.HandleFunc("POST /api/v1/users/me/reidentify", requireDevice(reidentify(deps)))
}
