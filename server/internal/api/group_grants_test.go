package api

// Доступ по разрешению и срок участия (ADR-0019 §8–§9, Г4).

import (
	"net/http"
	"testing"
	"time"

	"tima/server/internal/escrow"
)

// прошлаяЭпоха — месяц, который уже закончился: срок с ним заведомо истёк.
func прошлаяЭпоха() string { return escrow.EpochOf(time.Now().AddDate(0, -1, 0)) }

// будущаяЭпоха — месяц, который ещё не наступил.
func будущаяЭпоха() string { return escrow.EpochOf(time.Now().AddDate(0, 1, 0)) }

// Разрешение открывает участнику третий уровень; истёкшее — не открывает.
func TestДоступПоРазрешениюИСрок(t *testing.T) {
	ts, _ := setup(t)
	admin := registerDevice(t, ts, "+79998880001")
	member := registerDevice(t, ts, "+79998880002")
	groupID := createPublicGroupAPI(t, ts, admin.token)
	addMemberAPI(t, ts, admin.token, groupID, member.userID, "member")

	if code, _ := sendLeveled(t, ts, admin, groupID, "d0000000-0000-0000-0000-000000000001",
		levelByGrant, []byte("цифры по нагрузке")); code != http.StatusCreated {
		t.Fatalf("сообщение уровня 3: %d", code)
	}
	if got := levelsOf(t, ts, member.token, groupID); len(got) != 0 {
		t.Fatalf("без разрешения участник не видит уровень 3, а видит %v", got)
	}

	// просьба о доступе
	if code := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/level-requests", member.token, nil, nil); code != http.StatusCreated {
		t.Fatalf("просьба о доступе: %d", code)
	}
	var queue struct {
		Grants []struct {
			UserID string `json:"user_id"`
			State  string `json:"state"`
		} `json:"grants"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID+"/level-grants", admin.token, nil, &queue); code != http.StatusOK {
		t.Fatalf("очередь просьб: %d", code)
	}
	if len(queue.Grants) != 1 || queue.Grants[0].State != "asked" {
		t.Fatalf("в очереди должна быть одна просьба, а там %+v", queue.Grants)
	}

	// выдача до будущей эпохи — доступ появился
	if code := authedJSON(t, ts, "PUT", "/api/v1/groups/"+groupID+"/level-grants/"+member.userID,
		admin.token, map[string]any{"grant": true, "until_epoch": будущаяЭпоха()}, nil); code != http.StatusOK {
		t.Fatalf("выдача доступа: %d", code)
	}
	if got := levelsOf(t, ts, member.token, groupID); len(got) != 1 || got[0] != levelByGrant {
		t.Fatalf("с разрешением участник видит уровень 3, а видит %v", got)
	}

	// срок в прошлом — доступа нет, и никакой уборки для этого не требуется
	if code := authedJSON(t, ts, "PUT", "/api/v1/groups/"+groupID+"/level-grants/"+member.userID,
		admin.token, map[string]any{"grant": true, "until_epoch": прошлаяЭпоха()}, nil); code != http.StatusOK {
		t.Fatalf("выдача с истёкшим сроком: %d", code)
	}
	if got := levelsOf(t, ts, member.token, groupID); len(got) != 0 {
		t.Fatalf("после истечения срока уровень 3 не отдаётся, а видно %v", got)
	}

	// отзыв
	if code := authedJSON(t, ts, "PUT", "/api/v1/groups/"+groupID+"/level-grants/"+member.userID,
		admin.token, map[string]any{"grant": false}, nil); code != http.StatusOK {
		t.Fatalf("отзыв: %d", code)
	}
	var mine struct {
		MyLevel int16 `json:"my_level"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID+"/level-grants", member.token, nil, &mine); code != http.StatusOK {
		t.Fatalf("своё состояние: %d", code)
	}
	if mine.MyLevel != -1 {
		t.Fatalf("после отзыва разрешения нет, а my_level = %d", mine.MyLevel)
	}
}

// Срок участия: истёк — человек перестаёт быть участником немедленно, не дожидаясь уборки.
func TestСрокУчастияЗакрываетДоступСразу(t *testing.T) {
	ts, _ := setup(t)
	admin := registerDevice(t, ts, "+79998880001")
	member := registerDevice(t, ts, "+79998880002")
	groupID := createPublicGroupAPI(t, ts, admin.token)
	addMemberAPI(t, ts, admin.token, groupID, member.userID, "member")

	if code, _ := sendLeveled(t, ts, admin, groupID, "d0000000-0000-0000-0000-000000000002",
		levelMembers, []byte("вступившим")); code != http.StatusCreated {
		t.Fatalf("сообщение уровня 2: %d", code)
	}
	if got := levelsOf(t, ts, member.token, groupID); len(got) != 1 {
		t.Fatalf("участник видит сообщение уровня 2, а видит %v", got)
	}

	// срок в будущем — ничего не меняется
	if code := authedJSON(t, ts, "PUT", "/api/v1/groups/"+groupID+"/members/"+member.userID+"/term",
		admin.token, map[string]any{"until_epoch": будущаяЭпоха()}, nil); code != http.StatusOK {
		t.Fatalf("срок в будущем: %d", code)
	}
	if got := levelsOf(t, ts, member.token, groupID); len(got) != 1 {
		t.Fatalf("со сроком в будущем участник читает группу, а видит %v", got)
	}

	// срок в прошлом — участником быть перестал: публичная группа отдаёт ему только «всем»
	if code := authedJSON(t, ts, "PUT", "/api/v1/groups/"+groupID+"/members/"+member.userID+"/term",
		admin.token, map[string]any{"until_epoch": прошлаяЭпоха()}, nil); code != http.StatusOK {
		t.Fatalf("срок в прошлом: %d", code)
	}
	if got := levelsOf(t, ts, member.token, groupID); len(got) != 0 {
		t.Fatalf("просроченный участник не видит уровень 2, а видит %v", got)
	}
	var card struct {
		MyRole string `json:"my_role"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID, member.token, nil, &card); code != http.StatusOK {
		t.Fatalf("карточка публичной группы: %d", code)
	}
	if card.MyRole != "" {
		t.Fatalf("просроченный участник не должен иметь роли, а она %q", card.MyRole)
	}
}

// В личной группе доступа по разрешению нет: там всего два уровня.
func TestРазрешенийВЛичнойГруппеНет(t *testing.T) {
	ts, _ := setup(t)
	admin := registerDevice(t, ts, "+79998880001")
	member := registerDevice(t, ts, "+79998880002")
	groupID := createGroupAPI(t, ts, admin.token)
	addMemberAPI(t, ts, admin.token, groupID, member.userID, "member")

	if code := authedJSON(t, ts, "PUT", "/api/v1/groups/"+groupID+"/level-grants/"+member.userID,
		admin.token, map[string]any{"grant": true}, nil); code != http.StatusBadRequest {
		t.Fatalf("в личной группе разрешений нет, получено %d", code)
	}
}

// Срок — эпоха вида 2026-10, а не произвольная строка.
func TestСрокТолькоЭпохой(t *testing.T) {
	ts, _ := setup(t)
	admin := registerDevice(t, ts, "+79998880001")
	member := registerDevice(t, ts, "+79998880002")
	groupID := createPublicGroupAPI(t, ts, admin.token)
	addMemberAPI(t, ts, admin.token, groupID, member.userID, "member")

	for _, плохой := range []string{"2026-13", "12.10.2026", "2026", "завтра"} {
		if code := authedJSON(t, ts, "PUT", "/api/v1/groups/"+groupID+"/members/"+member.userID+"/term",
			admin.token, map[string]any{"until_epoch": плохой}, nil); code != http.StatusBadRequest {
			t.Fatalf("срок %q должен отклоняться, получено %d", плохой, code)
		}
	}
}
