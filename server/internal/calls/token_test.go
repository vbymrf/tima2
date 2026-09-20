package calls

import (
	"encoding/base64"
	"encoding/json"
	"strings"
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

// TestServiceGrants — у каждой ручки RoomService своё право, и подменять их нельзя.
//
// Спрошено у живого LiveKit 2026-09-20: DeleteRoom с roomAdmin отвечает 401, с roomCreate
// — 404 (право есть, комнаты нет); ListRooms требует roomList. Пока слался roomAdmin,
// комнаты не закрывались вовсе всё время, что существовали звонки.
//
// Проверка стережёт не код, а ЗНАНИЕ: оно добыто опытом и в самом токене не видно.
func TestServiceGrants(t *testing.T) {
	i := NewIssuer("ключ", "секрет-подлиннее-тридцати-двух-символов")

	отдать := func(grant VideoGrant) map[string]any {
		tok, err := i.ServiceToken(grant, time.Minute, time.Now())
		if err != nil {
			t.Fatal(err)
		}
		parts := strings.Split(tok, ".")
		raw, err := base64.RawURLEncoding.DecodeString(parts[1])
		if err != nil {
			t.Fatal(err)
		}
		var body struct {
			Video map[string]any `json:"video"`
		}
		if err := json.Unmarshal(raw, &body); err != nil {
			t.Fatal(err)
		}
		return body.Video
	}

	if g := отдать(VideoGrant{RoomCreate: true}); g["roomCreate"] != true {
		t.Fatalf("у DeleteRoom нет roomCreate — комнаты перестанут закрываться: %v", g)
	}
	if g := отдать(VideoGrant{RoomList: true}); g["roomList"] != true {
		t.Fatalf("у ListRooms нет roomList — уборка брошенных звонков ослепнет: %v", g)
	}
	// И обратное: служебный токен НЕ должен нести roomJoin — им нельзя подключаться
	// к медиа, в этом весь смысл отдельного права.
	if g := отдать(VideoGrant{RoomCreate: true}); g["roomJoin"] == true {
		t.Fatalf("служебный токен пускает в медиа: %v", g)
	}
}
