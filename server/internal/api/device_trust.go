package api

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"

	"tima/server/internal/attest"
	"tima/server/internal/auth"
	timacrypto "tima/server/internal/crypto"
	"tima/server/internal/store"
)

// ── ДОВЕРИЕ К УСТРОЙСТВАМ (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ДУ1–ДУ3) ─────────────────────
//
// Беда «вор SIM читает новые сообщения»: устройство в аккаунте ничем не было подтверждено —
// код SMS и строка в списке. Теперь устройство несёт свидетельство: его открытые ключи
// подписаны ключом подписи устройств (КПУ) телефона хозяина или ключом личности (фразой),
// а КПУ, в свою очередь, подписан ключом личности. Цепочку проверяет сервер при заведении и
// клиенты собеседников при каждой отправке — сервер не якорь доверия: взломанный, он просто
// не стал бы проверять.

// Режимы доверия — настройка сервера TIMA_DEVICE_TRUST (Р24): строгость включается сервером,
// а не выпуском клиента.
const (
	trustOff     = "off"     // не смотрим
	trustRecord  = "record"  // проверяем и пишем в журнал, не отказываем
	trustRequire = "require" // без годного свидетельства — отказ
)

// NormalizeDeviceTrust — неизвестное значение настройки становится «record»: опечатка в
// конфиге не должна ни отключать проверку, ни отрезать всех разом.
func NormalizeDeviceTrust(v string) string {
	switch v {
	case trustOff, trustRecord, trustRequire:
		return v
	}
	return trustRecord
}

// Кем подписано устройство.
const (
	certByIdentity = "identity"
	certByAsk      = "ask"
)

// deviceProof — что пришло вместе с ключами устройства.
type deviceProof struct {
	AskPub  []byte // КПУ, который телефон заводит вместе с собой; nil — не заводит
	AskSig  []byte // ключ личности над tima.ask.v1|<ask_pub>
	CertBy  string // identity | ask | ""
	CertSig []byte // подписавший над tima.device.v1|<enc>|<sig>
}

func decodeProof(askPub, askSig, certBy, certSig string) (deviceProof, error) {
	var p deviceProof
	var err error
	b64 := base64.RawURLEncoding
	if askPub != "" {
		if p.AskPub, err = b64.DecodeString(askPub); err != nil || len(p.AskPub) != 32 {
			return p, errors.New("ask_pub — Ed25519 32 байта (base64url)")
		}
		if p.AskSig, err = b64.DecodeString(askSig); err != nil || len(p.AskSig) != 64 {
			return p, errors.New("ask_sig — подпись 64 байта (base64url)")
		}
	}
	if certBy != "" {
		if certBy != certByIdentity && certBy != certByAsk {
			return p, errors.New("device_cert_by — identity или ask")
		}
		p.CertBy = certBy
		if p.CertSig, err = b64.DecodeString(certSig); err != nil || len(p.CertSig) != 64 {
			return p, errors.New("device_cert_sig — подпись 64 байта (base64url)")
		}
	}
	return p, nil
}

// verifyAsk — свидетельство КПУ сходится с ключом личности.
func verifyAsk(identityPub, askPub, askSig []byte) bool {
	return len(identityPub) == 32 && len(askPub) == 32 &&
		timacrypto.VerifyEnvelopeSignature(identityPub, timacrypto.AskCertBytes(askPub), askSig)
}

// verifyDeviceCert — свидетельство устройства сходится с подписавшим ключом.
func verifyDeviceCert(signerPub, encPub, sigPub, certSig []byte) bool {
	return len(signerPub) == 32 &&
		timacrypto.VerifyEnvelopeSignature(signerPub, timacrypto.DeviceCertBytes(encPub, sigPub), certSig)
}

// proofHolds — годится ли то, что пришло при заведении устройства: КПУ заверен ключом
// личности, устройство — КПУ или ключом личности. Возвращает, годен ли КПУ, и кем
// заверено устройство ("" — не заверено).
func proofHolds(identityPub, encPub, sigPub []byte, p deviceProof) (askOK bool, certBy string) {
	askOK = p.AskPub != nil && verifyAsk(identityPub, p.AskPub, p.AskSig)
	switch p.CertBy {
	case certByIdentity:
		if verifyDeviceCert(identityPub, encPub, sigPub, p.CertSig) {
			certBy = certByIdentity
		}
	case certByAsk:
		if askOK && verifyDeviceCert(p.AskPub, encPub, sigPub, p.CertSig) {
			certBy = certByAsk
		}
	}
	return askOK, certBy
}

// ── Ручки ─────────────────────────────────────────────────────────────────────

// DeviceTrustStore — что доверию нужно от хранилища.
type DeviceTrustStore interface {
	IdentityPub(ctx context.Context, userID string) ([]byte, error)
	DevicePlatform(ctx context.Context, deviceID string) (string, error)
	DeviceKeys(ctx context.Context, userID, deviceID string) ([]byte, []byte, error)
	AddSigningKey(ctx context.Context, userID, deviceID string, pub, sig []byte) (string, error)
	DeviceSigningKey(ctx context.Context, userID, deviceID string) (store.SigningKey, error)
	SetDeviceCertificate(ctx context.Context, userID, deviceID, by, askID string, sig []byte) error
	ReregOfUser(ctx context.Context, userID string) (store.Rereg, error)
}

var _ DeviceTrustStore = (*store.Store)(nil)

type deviceTrustDeps struct {
	store DeviceTrustStore
}

// RegisterDeviceTrust — две ручки: телефон заводит свой КПУ; устройство аккаунта получает
// свидетельство.
func RegisterDeviceTrust(mux *http.ServeMux, st DeviceTrustStore, requireDevice Middleware) {
	deps := deviceTrustDeps{store: st}
	mux.HandleFunc("POST /api/v1/users/me/signing-keys", requireDevice(issueSigningKey(deps)))
	mux.HandleFunc("PUT /api/v1/devices/{deviceID}/certificate", requireDevice(certifyDevice(deps)))
}

// issueSigningKey — POST /users/me/signing-keys: телефон, на котором человек ввёл фразу,
// заводит свой КПУ и заверяет им себя. Нужен устройствам, заведённым до ДУ1, и телефонам,
// привязанным по QR: при регистрации по фразе КПУ заводится сразу.
func issueSigningKey(deps deviceTrustDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		if reregBlocksTrust(r.Context(), deps.store, id.UserID) {
			writeErr(w, http.StatusConflict, "rereg_disputed", reregDisputedText)
			return
		}
		var req struct {
			AskPub        string `json:"ask_pub"`
			AskSig        string `json:"ask_sig"`
			DeviceCertSig string `json:"device_cert_sig"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		proof, err := decodeProof(req.AskPub, req.AskSig, certByAsk, req.DeviceCertSig)
		if err != nil || proof.AskPub == nil {
			writeErr(w, http.StatusBadRequest, "bad_request", "нужны ask_pub, ask_sig и device_cert_sig")
			return
		}
		ctx := r.Context()
		// КПУ держит только телефон (Р12). Платформа самообъявленная — до аттестации (ДУ8)
		// это правило порядка, а не граница безопасности.
		platform, err := deps.store.DevicePlatform(ctx, id.DeviceID)
		if err != nil {
			log.Printf("issueSigningKey: platform: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !store.PlatformPhone[platform] {
			writeErr(w, http.StatusForbidden, "not_a_phone", "ключ подписи устройств держит только телефон")
			return
		}
		identityPub, err := deps.store.IdentityPub(ctx, id.UserID)
		if err != nil {
			log.Printf("issueSigningKey: identity: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		enc, sig, err := deps.store.DeviceKeys(ctx, id.UserID, id.DeviceID)
		if err != nil {
			log.Printf("issueSigningKey: keys: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		askOK, certBy := proofHolds(identityPub, enc, sig, proof)
		if !askOK || certBy != certByAsk {
			writeErr(w, http.StatusForbidden, "bad_signature", "подпись не сходится с ключом личности аккаунта — фраза не та?")
			return
		}
		askID, err := deps.store.AddSigningKey(ctx, id.UserID, id.DeviceID, proof.AskPub, proof.AskSig)
		if err != nil {
			log.Printf("issueSigningKey: add: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if err := deps.store.SetDeviceCertificate(ctx, id.UserID, id.DeviceID, certByAsk, askID, proof.CertSig); err != nil {
			log.Printf("issueSigningKey: cert: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		log.Printf("доверие: телефон %s завёл ключ подписи устройств %s", id.DeviceID, askID)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"ask_id": askID})
	}
}

// certifyDevice — PUT /devices/{id}/certificate: заверить своё другое устройство. КПУ этого
// телефона (by=ask) или фразой (by=identity — с любого устройства аккаунта). Какие устройства
// заверять, решает человек на экране «Устройства»: заверять всё подряд из списка сервера
// значило бы заверить и устройство вора.
func certifyDevice(deps deviceTrustDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		if reregBlocksTrust(r.Context(), deps.store, id.UserID) {
			writeErr(w, http.StatusConflict, "rereg_disputed", reregDisputedText)
			return
		}
		target := r.PathValue("deviceID")
		var req struct {
			By  string `json:"by"`
			Sig string `json:"sig"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		proof, err := decodeProof("", "", req.By, req.Sig)
		if err != nil || proof.CertBy == "" {
			writeErr(w, http.StatusBadRequest, "bad_request", "нужны by (identity или ask) и sig")
			return
		}
		ctx := r.Context()
		enc, sig, err := deps.store.DeviceKeys(ctx, id.UserID, target)
		if errors.Is(err, store.ErrDeviceNotFound) {
			writeErr(w, http.StatusNotFound, "device_not_found", "устройство не найдено или отозвано")
			return
		} else if err != nil {
			log.Printf("certifyDevice: keys: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		var signer []byte
		askID := ""
		if proof.CertBy == certByAsk {
			k, err := deps.store.DeviceSigningKey(ctx, id.UserID, id.DeviceID)
			if errors.Is(err, store.ErrNoSigningKey) {
				writeErr(w, http.StatusForbidden, "no_signing_key", "у этого телефона нет ключа подписи устройств — введите на нём фразу")
				return
			} else if err != nil {
				log.Printf("certifyDevice: ask: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			signer, askID = k.Pub, k.AskID
		} else {
			if signer, err = deps.store.IdentityPub(ctx, id.UserID); err != nil {
				log.Printf("certifyDevice: identity: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
		}
		if !verifyDeviceCert(signer, enc, sig, proof.CertSig) {
			writeErr(w, http.StatusForbidden, "bad_signature", "подпись не сходится")
			return
		}
		if err := deps.store.SetDeviceCertificate(ctx, id.UserID, target, proof.CertBy, askID, proof.CertSig); err != nil {
			log.Printf("certifyDevice: set: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		log.Printf("доверие: %s заверил устройство %s (%s)", id.DeviceID, target, proof.CertBy)
		w.WriteHeader(http.StatusNoContent)
	}
}

// ── Аттестация (ДУ8, закладка Р20–Р23) ─────────────────────────────────────────

// Режимы аттестации — настройка TIMA_ATTESTATION (Р21): off — не смотрим; record —
// проверяем и пишем; require — пока как record (отказывать будет регистрация, когда включат).
func normalizeAttestation(v string) string {
	switch v {
	case trustOff, trustRecord, trustRequire:
		return v
	}
	return trustOff
}

// AttestationStore — что аттестации нужно от хранилища.
type AttestationStore interface {
	DeviceKeys(ctx context.Context, userID, deviceID string) ([]byte, []byte, error)
	SetDeviceAttestation(ctx context.Context, userID, deviceID, state, info string) error
}

// RegisterAttestation — POST /devices/me/attestation: телефон присылает цепочку аттестации ключа
// и подпись им над ключами своего устройства. Вызов — `/users/me/reidentify/challenge`, в запись
// аттестации кладётся sha256 его токена.
func RegisterAttestation(mux *http.ServeMux, st AttestationStore, tokens func() IdentityTokens, mode func() string, requireDevice Middleware) {
	mux.HandleFunc("POST /api/v1/devices/me/attestation", requireDevice(func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		if mode() == trustOff {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		var req struct {
			ChallengeToken string   `json:"challenge_token"`
			Kind           string   `json:"kind"`
			Chain          []string `json:"chain"`
			Signature      string   `json:"signature"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		claims, err := tokens().Parse(req.ChallengeToken, auth.ScopeReidentify)
		if err != nil || claims.Subject != id.UserID {
			writeErr(w, http.StatusForbidden, "bad_challenge", "вызов просрочен или выдан не этой сессии")
			return
		}
		if req.Kind != "android-key" {
			writeErr(w, http.StatusBadRequest, "bad_kind", "вид аттестации — android-key")
			return
		}
		b64 := base64.RawURLEncoding
		var chain [][]byte
		for _, c := range req.Chain {
			der, err := b64.DecodeString(c)
			if err != nil {
				writeErr(w, http.StatusBadRequest, "bad_chain", "цепочка — base64url DER")
				return
			}
			chain = append(chain, der)
		}
		sig, _ := b64.DecodeString(req.Signature)
		enc, sgn, err := st.DeviceKeys(r.Context(), id.UserID, id.DeviceID)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		challenge := sha256.Sum256([]byte(req.ChallengeToken))
		res := attest.VerifyAndroid(chain, challenge[:], timacrypto.DeviceCertBytes(enc, sgn), sig)
		state := "failed"
		if res.Ok() {
			state = "verified"
		}
		info, _ := json.Marshal(res)
		if err := st.SetDeviceAttestation(r.Context(), id.UserID, id.DeviceID, state, string(info)); err != nil {
			log.Printf("attestation: store: %v", err)
		}
		log.Printf("аттестация: устройство %s — %s (%s, загрузка %s, %s) %s", id.DeviceID, state, res.SecurityLevel, res.VerifiedBoot, res.PackageName, res.Problem)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"state": state, "problem": res.Problem})
	}))
}
