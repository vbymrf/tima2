// Package auth — device JWT (api-overview.md §Общее: авторизация Bearer per device).
//
// Два scope: "register" (короткий токен после проверки SMS-кода, subject = телефон)
// и "access" (рабочий токен устройства: user_id + device_id).
package auth

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const (
	ScopeAccess     = "access"
	ScopeRegister   = "register"
	ScopeReidentify = "reidentify"

	AccessTTL     = 24 * time.Hour
	RegisterTTL   = 10 * time.Minute
	ReidentifyTTL = 5 * time.Minute
)

type Claims struct {
	DeviceID string `json:"dev,omitempty"`
	Scope    string `json:"scope"`
	jwt.RegisteredClaims
}

type Issuer struct {
	key []byte
}

func NewIssuer(key []byte) *Issuer { return &Issuer{key: key} }

// IssueAccess — рабочий токен устройства.
func (i *Issuer) IssueAccess(userID, deviceID string) (string, error) {
	return i.sign(Claims{
		DeviceID: deviceID,
		Scope:    ScopeAccess,
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   userID,
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(AccessTTL)),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
		},
	})
}

// IssueRegister — токен «SMS подтверждена, можно регистрировать устройство».
func (i *Issuer) IssueRegister(phone string) (string, error) {
	return i.sign(Claims{
		Scope: ScopeRegister,
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   phone,
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(RegisterTTL)),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
		},
	})
}

// IssueReidentifyChallenge — короткоживущий токен «докажи владение прежним ключом
// личности» (ДОКУМЕНТАЦИЯ/02 §5, воссоединение по секретной фразе). Subject —
// user_id ТЕКУЩЕЙ личности, запросившей челлендж: проверяющий обязан сверить,
// что подтверждение пришло от той же сессии, что и запрос, а не переиспользовано
// откуда-то ещё. Короткий TTL — тот же принцип, что у RegisterTTL: окно, в
// котором подпись имеет смысл, должно быть маленьким.
func (i *Issuer) IssueReidentifyChallenge(userID string) (string, error) {
	return i.sign(Claims{
		Scope: ScopeReidentify,
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   userID,
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(ReidentifyTTL)),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
		},
	})
}

func (i *Issuer) sign(c Claims) (string, error) {
	return jwt.NewWithClaims(jwt.SigningMethodHS256, c).SignedString(i.key)
}

var ErrBadToken = errors.New("токен не прошёл проверку")

func (i *Issuer) Parse(token, wantScope string) (*Claims, error) {
	var claims Claims
	_, err := jwt.ParseWithClaims(token, &claims,
		func(*jwt.Token) (any, error) { return i.key, nil },
		jwt.WithValidMethods([]string{"HS256"}), jwt.WithExpirationRequired())
	if err != nil || claims.Scope != wantScope {
		return nil, ErrBadToken
	}
	return &claims, nil
}

// ── Middleware ──

type ctxKey struct{}

// Identity — устройство, от имени которого выполняется запрос.
type Identity struct {
	UserID   string
	DeviceID string
}

func FromContext(ctx context.Context) (Identity, bool) {
	id, ok := ctx.Value(ctxKey{}).(Identity)
	return id, ok
}

// Require оборачивает handler проверкой Bearer-токена scope=access.
func (i *Issuer) Require(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		raw, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !ok || raw == "" {
			w.Header().Set("Content-Type", "application/json")
			http.Error(w, `{"code":"unauthorized","message":"нужен Bearer device JWT"}`, http.StatusUnauthorized)
			return
		}
		claims, err := i.Parse(raw, ScopeAccess)
		if err != nil {
			w.Header().Set("Content-Type", "application/json")
			http.Error(w, `{"code":"unauthorized","message":"токен просрочен или подделан"}`, http.StatusUnauthorized)
			return
		}
		id := Identity{UserID: claims.Subject, DeviceID: claims.DeviceID}
		next(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, id)))
	}
}
