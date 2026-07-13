package calls

import (
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// TestTokenGrants — выданный токен проверяется секретом LiveKit и несёт нужные grants.
func TestTokenGrants(t *testing.T) {
	iss := NewIssuer("APIkey123", "supersecret")
	if iss == nil {
		t.Fatal("issuer не создан")
	}
	now := time.Now() // exp относительно реальных часов парсера
	tok, err := iss.Token("call-room-1", "user-1:dev-1", true, 2*time.Minute, now)
	if err != nil {
		t.Fatal(err)
	}

	// Проверяем подпись секретом LiveKit и разбираем claims
	parsed, err := jwt.ParseWithClaims(tok, &claims{}, func(tk *jwt.Token) (any, error) {
		if _, ok := tk.Method.(*jwt.SigningMethodHMAC); !ok {
			t.Fatalf("не HS256: %v", tk.Method)
		}
		return []byte("supersecret"), nil
	})
	if err != nil || !parsed.Valid {
		t.Fatalf("токен не проверился: %v", err)
	}
	c := parsed.Claims.(*claims)
	if c.Issuer != "APIkey123" {
		t.Fatalf("iss = %q, ожидался API key", c.Issuer)
	}
	if c.Subject != "user-1:dev-1" {
		t.Fatalf("identity = %q", c.Subject)
	}
	if c.Video.Room != "call-room-1" || !c.Video.RoomJoin {
		t.Fatalf("grants комнаты неверны: %+v", c.Video)
	}
	if c.Video.CanPublish == nil || !*c.Video.CanPublish {
		t.Fatal("canPublish должен быть true для говорящего")
	}
}

// TestTokenRejectsWrongSecret — токен нельзя подтвердить чужим секретом.
func TestTokenRejectsWrongSecret(t *testing.T) {
	iss := NewIssuer("k", "right-secret")
	tok, _ := iss.Token("r", "u:d", false, time.Minute, time.Now())
	_, err := jwt.ParseWithClaims(tok, &claims{}, func(*jwt.Token) (any, error) {
		return []byte("wrong-secret"), nil
	})
	if err == nil {
		t.Fatal("токен не должен проверяться чужим секретом")
	}
}

func TestIssuerNilWithoutConfig(t *testing.T) {
	if NewIssuer("", "") != nil {
		t.Fatal("без ключей issuer должен быть nil")
	}
	if _, err := (*Issuer)(nil).Token("r", "i", true, time.Minute, time.Now()); err != ErrNotConfigured {
		t.Fatalf("nil issuer должен вернуть ErrNotConfigured, получено %v", err)
	}
}
