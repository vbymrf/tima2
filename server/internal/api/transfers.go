package api

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"time"

	"tima/server/internal/auth"
	timacrypto "tima/server/internal/crypto"
	"tima/server/internal/store"
)

// Передача виртуального аккаунта — ПЛАН-КОНТАКТОВ.md, Д12.
//
// **Передача уносит всё**: переписку, группы, каналы, роли и владение. Ничего не
// переносится построчно — членства и владение ссылаются на тот же идентификатор, который
// передача не меняет.
//
// **Два условия, а не одно.** Знания фразы мало: нужно ещё действие прежнего владельца —
// он выдаёт код. Иначе подслушанной фразы хватало бы, чтобы забрать аккаунт.
//
// **Фразу приложение не передаёт.** Её передают люди сами и тем способом, каким считают
// нужным; сюда уходит только защищённый запрос — код передачи. Пока код и фраза идут
// разными путями, перехват одного пути ничего не даёт.

// TransferTTL — сколько живёт код передачи.
//
// Тридцать минут от подтверждения телефона на старом устройстве (решение заказчика
// 2026-09-05). Срок считает СЕРВЕР: на клиенте он переводится часами телефона, и
// проверка там означала бы «код живёт столько, сколько я захочу».
const TransferTTL = 30 * time.Minute

// TransferStore — что передаче нужно от хранилища.
type TransferStore interface {
	StartTransfer(ctx context.Context, ownerUserID, virtualUserID string, codeHash []byte, ttl time.Duration) (string, error)
	CancelTransfer(ctx context.Context, ownerUserID, virtualUserID string) error
	FindTransfer(ctx context.Context, codeHash []byte) (store.Transfer, error)
	FailedAttempt(ctx context.Context, transferID string) error
	CompleteTransfer(ctx context.Context, transferID, newOwnerUserID string) error
}

var _ TransferStore = (*store.Store)(nil)

// RegisterTransfers — три маршрута.
func RegisterTransfers(mux *http.ServeMux, st TransferStore, requireDevice Middleware) {
	mux.HandleFunc("POST /api/v1/users/me/virtuals/{userID}/transfer", requireDevice(startTransfer(st)))
	mux.HandleFunc("DELETE /api/v1/users/me/virtuals/{userID}/transfer", requireDevice(cancelTransfer(st)))
	mux.HandleFunc("POST /api/v1/transfers/accept", requireDevice(acceptTransfer(st)))
}

// hashTransferCode — что лежит в базе вместо кода.
//
// Утечка таблицы не должна отдавать коды: тот же принцип, что у `sms_codes`.
func hashTransferCode(code []byte) []byte {
	sum := sha256.Sum256(code)
	return sum[:]
}

// startTransfer — POST /users/me/virtuals/{userID}/transfer.
//
// Код возвращается **один раз** и больше нигде не показывается: в базе лежит его хэш.
// Показать его второй раз означало бы хранить сам код.
func startTransfer(st TransferStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		code := make([]byte, 32)
		if _, err := rand.Read(code); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "нет энтропии")
			return
		}
		transferID, err := st.StartTransfer(r.Context(), id.UserID, r.PathValue("userID"),
			hashTransferCode(code), TransferTTL)
		switch {
		case errors.Is(err, store.ErrNotYourVirtual):
			writeErr(w, http.StatusForbidden, "not_your_virtual", "это не ваш виртуальный аккаунт")
			return
		case errors.Is(err, store.ErrUserUnknown):
			writeErr(w, http.StatusNotFound, "user_not_found", "аккаунт не найден")
			return
		case err != nil:
			log.Printf("startTransfer: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"transfer_id":     transferID,
			"code":            base64.RawURLEncoding.EncodeToString(code),
			"expires_in_sec":  int(TransferTTL.Seconds()),
			"attempts_before": store.TransferAttempts,
		})
	}
}

// cancelTransfer — DELETE /users/me/virtuals/{userID}/transfer: передумал.
func cancelTransfer(st TransferStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		if err := st.CancelTransfer(r.Context(), id.UserID, r.PathValue("userID")); err != nil {
			log.Printf("cancelTransfer: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// acceptTransfer — POST /transfers/accept {code, proof}.
//
// `proof` — подпись кода передачи ключом личности **передаваемого аккаунта**. Так и
// проверяется «ввёл фразу»: фразы сервер не видел и видеть не должен, а ключ из неё
// выводится однозначно.
//
// Времени на этот шаг не отведено: тридцать минут ограничивают предъявление кода, а
// дальше человек не торопится — спешка на вводе фразы стоит попытки.
func acceptTransfer(st TransferStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Code  string `json:"code"`
			Proof string `json:"proof"` // base64url, 64 байта: подпись кода ключом личности
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		b64 := base64.RawURLEncoding
		code, err1 := b64.DecodeString(req.Code)
		proof, err2 := b64.DecodeString(req.Proof)
		if err1 != nil || err2 != nil || len(code) != 32 || len(proof) != 64 {
			writeErr(w, http.StatusBadRequest, "bad_encoding", "code и proof — base64url, 32 и 64 байта")
			return
		}

		transfer, err := st.FindTransfer(r.Context(), hashTransferCode(code))
		if errors.Is(err, store.ErrTransferGone) {
			// Один ответ на «нет», «погашен» и «просрочен»: различать их значило бы
			// сообщать предъявителю, существовала ли передача вообще.
			writeErr(w, http.StatusNotFound, "transfer_gone", "код передачи не действует")
			return
		} else if err != nil {
			log.Printf("acceptTransfer: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}

		if !timacrypto.VerifyEnvelopeSignature(transfer.IdentityPub, code, proof) {
			// Неверная фраза. Третья попытка гасит код: перебор упирается не в длину
			// фразы, а в двух живых людей — второй обязан выдать новый код.
			if err := st.FailedAttempt(r.Context(), transfer.ID); errors.Is(err, store.ErrTooManyAttempts) {
				writeErr(w, http.StatusForbidden, "transfer_burned",
					"три неверные попытки — попросите новый код")
				return
			} else if err != nil {
				log.Printf("acceptTransfer: попытка: %v", err)
			}
			writeErr(w, http.StatusForbidden, "bad_proof", "фраза не подходит")
			return
		}

		id, _ := auth.FromContext(r.Context())
		if err := st.CompleteTransfer(r.Context(), transfer.ID, id.UserID); errors.Is(err, store.ErrTransferGone) {
			writeErr(w, http.StatusNotFound, "transfer_gone", "код передачи не действует")
			return
		} else if err != nil {
			log.Printf("acceptTransfer: завершение: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}

		// Устройства прежнего владельца отозваны, владелец перевязан. Новому владельцу
		// остаётся войти в аккаунт своим устройством — и ротировать групповые ключи:
		// сервер этого сделать не может, ключи выпускают участники (ADR-0017).
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"user_id":       transfer.UserID,
			"rotate_needed": true,
		})
	}
}
