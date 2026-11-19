package api

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"net/http/httptest"
	"testing"
	"time"

	timacrypto "tima/server/internal/crypto"
)

// Владение по ключу личности (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §6.5, СЦ11).

type ownerKit struct {
	dev  *device
	priv ed25519.PrivateKey
}

func withIdentity(t *testing.T, ts *httptest.Server, phone string) ownerKit {
	t.Helper()
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	d, code, why := registerRaw(t, ts, phone, pub, false)
	if code != 201 && code != 200 {
		t.Fatalf("регистрация %s: %d %s", phone, code, why)
	}
	return ownerKit{dev: d, priv: priv}
}

func signAct(priv ed25519.PrivateKey, kind, id string, link int, from, to string, exp int64) string {
	return base64.RawURLEncoding.EncodeToString(ed25519.Sign(priv, timacrypto.EntityTransferBytes(kind, id, link, from, to, exp)))
}

type chainAnswer struct {
	Chain []struct {
		LinkNo  int    `json:"link_no"`
		OwnerID string `json:"owner_id"`
		Kind    string `json:"kind"`
	} `json:"chain"`
	CanTransfer bool `json:"can_transfer"`
	NextLink    int  `json:"next_link"`
}

func TestПередачаВладенияГруппойПоКлючуЛичности(t *testing.T) {
	ts, _ := setup(t)
	owner := withIdentity(t, ts, "+79990008201")
	mod := withIdentity(t, ts, "+79990008202")
	plain := withIdentity(t, ts, "+79990008203")
	group := makeGroup(t, ts, owner.dev.token, "public")
	for _, m := range []struct {
		who  ownerKit
		role string
	}{{mod, "moderator"}, {plain, "member"}} {
		if code := postAuthed(t, ts, owner.dev.token, "POST", "/api/v1/groups/"+group+"/members",
			map[string]any{"user_id": m.who.dev.userID, "role": m.role}, nil); code != 201 {
			t.Fatalf("участник %s: %d", m.role, code)
		}
	}

	// Создание — первое звено с ключом создателя; передать может.
	var ch chainAnswer
	if code := getAuthed(t, ts, owner.dev.token, "/api/v1/groups/"+group+"/ownership", &ch); code != 200 {
		t.Fatalf("цепочка: %d", code)
	}
	if len(ch.Chain) != 1 || ch.Chain[0].Kind != "genesis" || ch.Chain[0].OwnerID != owner.dev.userID || !ch.CanTransfer {
		t.Fatalf("первое звено: %+v", ch)
	}
	exp := time.Now().Add(20 * time.Minute).Unix()

	// Чужой ключ — отказ.
	if code := postAuthed(t, ts, owner.dev.token, "POST", "/api/v1/groups/"+group+"/ownership/offer", map[string]any{
		"to_user_id": mod.dev.userID, "expires_unix": exp, "signature": signAct(plain.priv, "group", group, 2, owner.dev.userID, mod.dev.userID, exp),
	}, nil); code != 403 {
		t.Fatalf("подпись чужим ключом: ждали 403, пришло %d", code)
	}
	// Участник без роли — не принимает.
	if code := postAuthed(t, ts, owner.dev.token, "POST", "/api/v1/groups/"+group+"/ownership/offer", map[string]any{
		"to_user_id": plain.dev.userID, "expires_unix": exp, "signature": signAct(owner.priv, "group", group, 2, owner.dev.userID, plain.dev.userID, exp),
	}, nil); code != 409 {
		t.Fatalf("принимающий ниже модератора: ждали 409, пришло %d", code)
	}
	// Срок дальше 30 минут — отказ.
	far := time.Now().Add(2 * time.Hour).Unix()
	if code := postAuthed(t, ts, owner.dev.token, "POST", "/api/v1/groups/"+group+"/ownership/offer", map[string]any{
		"to_user_id": mod.dev.userID, "expires_unix": far, "signature": signAct(owner.priv, "group", group, 2, owner.dev.userID, mod.dev.userID, far),
	}, nil); code != 400 {
		t.Fatalf("долгий акт: ждали 400, пришло %d", code)
	}

	offer := func() {
		t.Helper()
		if code := postAuthed(t, ts, owner.dev.token, "POST", "/api/v1/groups/"+group+"/ownership/offer", map[string]any{
			"to_user_id": mod.dev.userID, "expires_unix": exp, "signature": signAct(owner.priv, "group", group, 2, owner.dev.userID, mod.dev.userID, exp),
		}, nil); code != 201 {
			t.Fatalf("предложение: %d", code)
		}
	}
	offer()
	var mine struct {
		Offers []struct {
			EntityID string `json:"entity_id"`
			Title    string `json:"title"`
		} `json:"offers"`
	}
	if code := getAuthed(t, ts, mod.dev.token, "/api/v1/users/me/ownership-offers", &mine); code != 200 || len(mine.Offers) != 1 || mine.Offers[0].EntityID != group {
		t.Fatalf("мои предложения: %d %+v", code, mine)
	}

	// Три неверные фразы гасят предложение.
	bad := signAct(plain.priv, "group", group, 2, owner.dev.userID, mod.dev.userID, exp)
	for i := 0; i < 3; i++ {
		if code := postAuthed(t, ts, mod.dev.token, "POST", "/api/v1/groups/"+group+"/ownership/accept", map[string]any{"signature": bad}, nil); code != 403 {
			t.Fatalf("неверная подпись %d: ждали 403, пришло %d", i+1, code)
		}
	}
	good := signAct(mod.priv, "group", group, 2, owner.dev.userID, mod.dev.userID, exp)
	if code := postAuthed(t, ts, mod.dev.token, "POST", "/api/v1/groups/"+group+"/ownership/accept", map[string]any{"signature": good}, nil); code != 404 {
		t.Fatalf("после трёх ошибок предложение погашено: ждали 404, пришло %d", code)
	}

	// Заново — и принятие.
	offer()
	if code := postAuthed(t, ts, mod.dev.token, "POST", "/api/v1/groups/"+group+"/ownership/accept", map[string]any{"signature": good}, nil); code != 200 {
		t.Fatalf("принятие: %d", code)
	}
	var g struct {
		OwnerID string `json:"owner_id"`
		MyRole  string `json:"my_role"`
	}
	if code := getAuthed(t, ts, owner.dev.token, "/api/v1/groups/"+group, &g); code != 200 || g.OwnerID != mod.dev.userID || g.MyRole != "member" {
		t.Fatalf("после передачи у прежнего: %d %+v", code, g)
	}
	if code := getAuthed(t, ts, mod.dev.token, "/api/v1/groups/"+group+"/ownership", &ch); code != 200 || len(ch.Chain) != 2 || ch.Chain[1].Kind != "transfer" || !ch.CanTransfer {
		t.Fatalf("цепочка после передачи: %d %+v", code, ch)
	}
	// Повтор того же акта не проходит.
	if code := postAuthed(t, ts, mod.dev.token, "POST", "/api/v1/groups/"+group+"/ownership/accept", map[string]any{"signature": good}, nil); code != 404 {
		t.Fatalf("повтор: ждали 404, пришло %d", code)
	}
	// Прежний владелец — участник — выйти может.
	if code := postAuthed(t, ts, owner.dev.token, "DELETE", "/api/v1/groups/"+group+"/members/"+owner.dev.userID, nil, nil); code != 204 && code != 200 {
		t.Fatalf("прежний владелец выходит: %d", code)
	}
}

func TestПередачаКлючомАккаунтаПослеСменыЛичностиНеПроходит(t *testing.T) {
	// Административная смена личности: ключ аккаунта уже другой, а последнее звено подписано прежним. Подпись
	// текущим ключом не проходит; распоряжаться (можно ли передать) — «нет» (§6.3).
	ts, _ := setup(t)
	owner := withIdentity(t, ts, "+79990008211")
	admin := withIdentity(t, ts, "+79990008212")
	community := makeCommunity(t, ts, owner.dev, "Клуб")
	if code := postAuthed(t, ts, owner.dev.token, "POST", "/api/v1/communities/"+community+"/admins",
		map[string]any{"user_id": admin.dev.userID}, nil); code != 200 && code != 201 && code != 204 {
		t.Fatalf("админ: %d", code)
	}
	newPub, newPriv, _ := ed25519.GenerateKey(rand.Reader)
	db := testDB(t)
	if _, err := db.Exec(context.Background(), `UPDATE users SET identity_pub = $2 WHERE user_id = $1`, owner.dev.userID, newPub); err != nil {
		t.Fatal(err)
	}
	var ch chainAnswer
	if code := getAuthed(t, ts, owner.dev.token, "/api/v1/communities/"+community+"/ownership", &ch); code != 200 || ch.CanTransfer {
		t.Fatalf("после смены ключа передать нельзя: %d %+v", code, ch)
	}
	exp := time.Now().Add(10 * time.Minute).Unix()
	if code := postAuthed(t, ts, owner.dev.token, "POST", "/api/v1/communities/"+community+"/ownership/offer", map[string]any{
		"to_user_id": admin.dev.userID, "expires_unix": exp, "signature": signAct(newPriv, "community", community, 2, owner.dev.userID, admin.dev.userID, exp),
	}, nil); code != 403 {
		t.Fatalf("подпись текущим ключом аккаунта: ждали 403, пришло %d", code)
	}
	// Прежним ключом — проходит, и админ принимает.
	if code := postAuthed(t, ts, owner.dev.token, "POST", "/api/v1/communities/"+community+"/ownership/offer", map[string]any{
		"to_user_id": admin.dev.userID, "expires_unix": exp, "signature": signAct(owner.priv, "community", community, 2, owner.dev.userID, admin.dev.userID, exp),
	}, nil); code != 201 {
		t.Fatalf("подпись ключом звена: %d", code)
	}
	if code := postAuthed(t, ts, admin.dev.token, "POST", "/api/v1/communities/"+community+"/ownership/accept", map[string]any{
		"signature": signAct(admin.priv, "community", community, 2, owner.dev.userID, admin.dev.userID, exp),
	}, nil); code != 200 {
		t.Fatalf("принятие админом: %d", code)
	}
	var page communityPageAnswer
	if code := getAuthed(t, ts, admin.dev.token, "/api/v1/communities/"+community, &page); code != 200 || !page.Community.Owner {
		t.Fatalf("новый владелец сообщества: %d %+v", code, page.Community)
	}
}

func TestПонижениеПринимающегоГаситПредложение(t *testing.T) {
	ts, _ := setup(t)
	owner := withIdentity(t, ts, "+79990008221")
	mod := withIdentity(t, ts, "+79990008222")
	group := makeGroup(t, ts, owner.dev.token, "public")
	if code := postAuthed(t, ts, owner.dev.token, "POST", "/api/v1/groups/"+group+"/members",
		map[string]any{"user_id": mod.dev.userID, "role": "moderator"}, nil); code != 201 {
		t.Fatalf("модератор: %d", code)
	}
	exp := time.Now().Add(10 * time.Minute).Unix()
	if code := postAuthed(t, ts, owner.dev.token, "POST", "/api/v1/groups/"+group+"/ownership/offer", map[string]any{
		"to_user_id": mod.dev.userID, "expires_unix": exp, "signature": signAct(owner.priv, "group", group, 2, owner.dev.userID, mod.dev.userID, exp),
	}, nil); code != 201 {
		t.Fatalf("предложение: %d", code)
	}
	if code := postAuthed(t, ts, owner.dev.token, "PUT", "/api/v1/groups/"+group+"/members/"+mod.dev.userID+"/role",
		map[string]any{"role": "member"}, nil); code != 200 && code != 204 {
		t.Fatalf("понижение: %d", code)
	}
	if code := postAuthed(t, ts, mod.dev.token, "POST", "/api/v1/groups/"+group+"/ownership/accept", map[string]any{
		"signature": signAct(mod.priv, "group", group, 2, owner.dev.userID, mod.dev.userID, exp),
	}, nil); code != 409 {
		t.Fatalf("пониженный принимает: ждали 409, пришло %d", code)
	}
}
