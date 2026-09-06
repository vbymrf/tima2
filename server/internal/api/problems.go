package api

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"log"
	"net"
	"net/http"
	"strings"
	"time"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// Отчёты о проблеме с устройства — ПЛАН-ОТЛАДКИ.md, Б4.
//
// **Второй источник правды об одной поломке.** Первый — прямой доступ к устройству по
// проводу; сходятся они или нет, и есть мера того, годится ли формат отчёта. Поэтому
// сервер принимает отчёт как есть и ничего в нём не «улучшает»: сравнивать надо с тем,
// что действительно прислало устройство.
//
// **Токен необязателен.** «Не могу войти» — самая частая жалоба, и требовать для неё
// авторизацию значит её не услышать (решение заказчика 2026-09-06). Есть токен — сервер
// сам берёт из него аккаунт и устройство; нет — отчёт ложится неопознанным, и его
// защищает предел частоты по адресу.
//
// **Присланным идентификаторам не верим никогда.** Клиент их и не шлёт: он показывает их
// человеку, чтобы тот видел, что уходит, а связывает отчёт с аккаунтом сервер.

// ProblemLimits — сколько неопознанных отчётов принимаем с одного адреса.
//
// Пять в час: человек, у которого не выходит войти, отправит два-три, а не пятьдесят.
// Предел стоит только на отчётах без токена: у вошедшего есть аккаунт, и злоупотребление
// видно по нему.
const (
	ProblemsPerHour = 5
	ProblemWindow   = time.Hour
)

// ProblemStore — что приёму отчётов нужно от хранилища.
type ProblemStore interface {
	// SaveProblemReport кладёт отчёт и возвращает его короткий номер.
	SaveProblemReport(ctx context.Context, report store.ProblemReport) (string, error)
	// CountProblemReportsFrom считает неопознанные отчёты с адреса за окно — для предела.
	CountProblemReportsFrom(ctx context.Context, addr string, since time.Time) (int, error)
}

// problemRequest — тело запроса. Полей аккаунта здесь нет и быть не может.
type problemRequest struct {
	Kind     string `json:"kind"`
	Text     string `json:"text"`
	Origin   string `json:"origin"`
	Platform string `json:"platform"`
	Model    string `json:"model"`
	OS       string `json:"os"`
	Build    string `json:"build"`
	Stream   string `json:"stream"`
	Nickname string `json:"nickname"`
	Log      string `json:"log"`
}

// Пределы длины. Отчёт — не место для загрузки файлов: журнал за сутки укладывается в
// сотни килобайт, а всё, что больше, означает либо ошибку в цикле, либо чужой запрос.
const (
	maxProblemText = 4 * 1024
	maxProblemLog  = 512 * 1024
	maxProblemWord = 256
)

// RegisterProblems подключает приём отчётов.
//
// Ручка одна и публичная; авторизация, если она есть, разбирается внутри — иначе
// пришлось бы держать два маршрута, отличающихся только тем, кто их зовёт.
func RegisterProblems(mux *http.ServeMux, st ProblemStore, tokens func() *auth.Issuer) {
	mux.HandleFunc("POST /api/v1/problem-reports", func(w http.ResponseWriter, r *http.Request) {
		var req problemRequest
		// Втрое от предела журнала: он считается в рунах, а едет в байтах, и кириллица
		// занимает по два. Без запаса длинный русский журнал упирался бы в чтение и
		// возвращал «не разобрали тело» вместо честной обрезки.
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 3*maxProblemLog)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_request", "не разобрали тело запроса")
			return
		}
		if strings.TrimSpace(req.Text) == "" {
			// Отчёт без слов человека — загадка, а не жалоба: журнал покажет, что
			// происходило, но не то, чего от приложения ждали.
			writeErr(w, http.StatusBadRequest, "empty_report", "нужно описание проблемы")
			return
		}

		// Аккаунт и устройство — только из токена, и разбирается он ЗДЕСЬ, а не
		// посредником: посредник на отсутствующем токене отвечает 401, а нам такой
		// запрос нужен принять.
		userID, deviceID := identifyIfPossible(r, tokens())

		addr := clientAddr(r)
		if userID == "" {
			// Предел только для неопознанных: у вошедшего злоупотребление видно по
			// аккаунту, а закрывать ему отправку значит терять настоящие жалобы.
			count, err := st.CountProblemReportsFrom(r.Context(), addr, time.Now().Add(-ProblemWindow))
			if err != nil {
				log.Printf("problem-reports: счёт по адресу: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			if count >= ProblemsPerHour {
				writeErr(w, http.StatusTooManyRequests, "too_many_reports", "слишком много отчётов с этого адреса")
				return
			}
		}

		number, err := st.SaveProblemReport(r.Context(), store.ProblemReport{
			Number:   problemNumber(),
			UserID:   userID,
			DeviceID: deviceID,
			Kind:     cut(req.Kind, maxProblemWord),
			Body:     cut(req.Text, maxProblemText),
			Origin:   cut(req.Origin, maxProblemWord),
			Platform: cut(req.Platform, maxProblemWord),
			Model:    cut(req.Model, maxProblemWord),
			OS:       cut(req.OS, maxProblemWord),
			Build:    cut(req.Build, maxProblemWord),
			Stream:   cut(req.Stream, maxProblemWord),
			Nickname: cut(req.Nickname, maxProblemWord),
			Log:      cut(req.Log, maxProblemLog),
			FromAddr: addr,
		})
		if err != nil {
			log.Printf("problem-reports: запись: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "не удалось сохранить отчёт")
			return
		}

		// Отчёт виден в журнале сервера сразу: разбирают их обычно в тот же день, и
		// «пришло ли вообще» — первый вопрос.
		log.Printf("Отчёт о проблеме %s: %s, %s %s, аккаунт %q", number, req.Kind, req.Platform, req.Model, userID)

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]string{"number": number})
	})
}

// identifyIfPossible — кто прислал, если это вообще известно.
//
// Отсутствие токена, просроченный токен и подделка здесь одинаковы: отчёт принимается
// неопознанным. Отвечать отказом было бы хуже — человек с просроченным токеном как раз и
// пишет «не могу войти».
func identifyIfPossible(r *http.Request, issuer *auth.Issuer) (userID, deviceID string) {
	if issuer == nil {
		return "", ""
	}
	raw, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !ok || raw == "" {
		return "", ""
	}
	claims, err := issuer.Parse(raw, auth.ScopeAccess)
	if err != nil {
		return "", ""
	}
	return claims.Subject, claims.DeviceID
}

// problemNumber — короткий номер обращения.
//
// Четыре знака без похожих друг на друга: человек называет его вслух, а «0» и «O» на слух
// не различаются. Совпадения возможны и не страшны — номер ищется вместе с датой; на
// колонке стоит UNIQUE, и повтор просто не запишется.
func problemNumber() string {
	const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	raw := make([]byte, 4)
	if _, err := rand.Read(raw); err != nil {
		return time.Now().UTC().Format("150405")
	}
	out := make([]byte, len(raw))
	for i, b := range raw {
		out[i] = alphabet[int(b)%len(alphabet)]
	}
	return string(out)
}

// clientAddr — адрес отправителя для предела частоты.
//
// За Caddy настоящий адрес приходит в X-Forwarded-For; берётся первый в списке —
// остальные дописывают промежуточные узлы, и доверять им нечего.
func clientAddr(r *http.Request) string {
	if forwarded := r.Header.Get("X-Forwarded-For"); forwarded != "" {
		if first, _, ok := strings.Cut(forwarded, ","); ok {
			return strings.TrimSpace(first)
		}
		return strings.TrimSpace(forwarded)
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// cut обрезает по длине в рунах, а не в байтах: обрезка посреди кириллической буквы
// оставила бы в базе битый UTF-8.
func cut(value string, limit int) string {
	runes := []rune(value)
	if len(runes) <= limit {
		return value
	}
	return string(runes[:limit])
}
