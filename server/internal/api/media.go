// Media Service (media-storage.md §2): файлы не проксируются через бэкенд —
// только presigned URL в MinIO. CAS-дедупликация — исключительно публичное
// (для приватного хэш открытого файла не принимается вовсе: утечка «файл уже был»).
package api

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strconv"
	"time"

	"tima/server/internal/auth"
	"tima/server/internal/blob"
	"tima/server/internal/store"
)

// MediaStore — что медиа нужно от хранилища: четыре метода из ста тридцати двух.
type MediaStore interface {
	CreateMedia(ctx context.Context, m store.Media, contentHash []byte) (store.Media, error)
	CompleteMedia(ctx context.Context, mediaID, ownerID string, actualSize int64) error
	FindMediaByHash(ctx context.Context, contentHash []byte) (string, error)
	GetMedia(ctx context.Context, mediaID string) (store.Media, error)
	MediaMany(ctx context.Context, ids []string) ([]store.Media, error)
	FetchPostman(ctx context.Context, mediaID string, byPeer bool, after time.Duration) (bool, error)
	ReopenMedia(ctx context.Context, mediaID, userID string) (store.Media, error)
	DropPostman(ctx context.Context, mediaID, ownerID string) error
	GroupMemberInfo(ctx context.Context, groupID, userID string) (string, *time.Time, error)
}

var _ MediaStore = (*store.Store)(nil)

// mediaDeps — зависимости группы.
//
// Клиент объектного хранилища берётся ФУНКЦИЕЙ, а не значением, по той же
// причине, что настройки LiveKit у звонков: setupWithBlob ставит Blob уже после
// Register, и снимок, снятый при регистрации, оставил бы медиа навсегда в 503.
type mediaDeps struct {
	store    MediaStore
	blobs    func() *blob.Client
	notifier *Notifier
	rules    MediaRules
}

// RegisterMedia — маршруты медиа: инициация загрузки, подтверждение, ссылка, ссылки пачкой; у
// почтальона ещё просьба прислать снова и повторная загрузка (Решения §6).
func RegisterMedia(mux *http.ServeMux, st MediaStore, blobs func() *blob.Client, n *Notifier, requireDevice Middleware) {
	deps := mediaDeps{store: st, blobs: blobs, notifier: n, rules: MediaRulesFromEnv()}
	mux.HandleFunc("GET /api/v1/media/rules", requireDevice(mediaRules(deps)))
	mux.HandleFunc("POST /api/v1/media/init", requireDevice(mediaInit(deps)))
	mux.HandleFunc("POST /api/v1/media/complete", requireDevice(mediaComplete(deps)))
	mux.HandleFunc("GET /api/v1/media/{mediaID}/url", requireDevice(mediaURL(deps)))
	mux.HandleFunc("POST /api/v1/media/urls", requireDevice(mediaURLs(deps)))
	mux.HandleFunc("POST /api/v1/media/{mediaID}/request", requireDevice(mediaRequest(deps)))
	mux.HandleFunc("POST /api/v1/media/{mediaID}/reupload", requireDevice(mediaReupload(deps)))
	mux.HandleFunc("POST /api/v1/media/{mediaID}/drop", requireDevice(mediaDrop(deps)))
}

// MediaRules — что сервер принимает (заказчик 2026-10-22, ПЛАН-(ИФ) ИФ1): сервер задаёт, клиент
// спрашивает не чаще раза в сутки и соблюдает до отправки. Файл зашифрован — сервер видит только
// размер и заявленный вид; сторону фото и запреты по имени держит клиент по этому ответу. Подписка
// позже будет менять ответ человеку, а не клиент.
type MediaRules struct {
	FileBytes  int64 // файл другому человеку
	SelfBytes  int64 // файл себе — верхняя граница загрузки вообще
	DailyBytes int64 // квота загрузок за сутки (Решения §14)
	PhotoSide  int   // большая сторона фото (ПЛАН-(ПФ))
	MaxPhotos  int   // фото в одном сообщении (Решения §3)
	TermDays   int   // сколько дней держится файл на срок — голосовое (ПЛАН-(ВМ) Р3, Р20)
}

// MediaRulesFromEnv — правила из переменных сервера; пусто — числа решений (Решения §3, §6, §14).
// Ошибка в настройке — отказ запуска: молча взятое число по умолчанию разошлось бы со словом
// владельца стенда.
func MediaRulesFromEnv() MediaRules {
	return MediaRules{
		FileBytes:  envInt("TIMA_MEDIA_FILE_MB", 10) << 20,
		SelfBytes:  envInt("TIMA_MEDIA_SELF_MB", 50) << 20,
		DailyBytes: envInt("TIMA_MEDIA_DAILY_MB", 1024) << 20,
		PhotoSide:  int(envInt("TIMA_MEDIA_PHOTO_SIDE", 1280)),
		MaxPhotos:  int(envInt("TIMA_MEDIA_MAX_PHOTOS", 10)),
		TermDays:   int(envInt("TIMA_MEDIA_TERM_DAYS", 7)),
	}
}

func envInt(name string, def int64) int64 {
	v := os.Getenv(name)
	if v == "" {
		return def
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil || n <= 0 {
		log.Fatalf("%s: целое больше нуля, получено %q", name, v)
	}
	return n
}

// termSeconds — срок файла на срок в секундах; в правилах без числа (проверки) — неделя.
func (rl MediaRules) termSeconds() int32 {
	if rl.TermDays <= 0 {
		return 7 * 24 * 3600
	}
	return int32(rl.TermDays) * 24 * 3600
}

// blockedNames — окончания имён, которые клиент не отправляет вовсе (Решения §5, §6).
var blockedNames = []string{".html", ".htm", ".svg", ".exe", ".msi", ".bat", ".cmd", ".com", ".scr", ".ps1", ".vbs", ".js", ".jar"}

// mediaRules — GET /media/rules: что сервер примет от этого человека.
func mediaRules(deps mediaDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		rl := deps.rules
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"file_bytes": rl.FileBytes, "self_bytes": rl.SelfBytes, "daily_bytes": rl.DailyBytes,
			"photo_side": rl.PhotoSide, "max_photos": rl.MaxPhotos,
			"postman_hour_sec": int64(store.PostmanHour.Seconds()), "postman_week_sec": int64(store.PostmanWeek.Seconds()),
			"term_sec":      rl.termSeconds(),
			"blocked_names": blockedNames,
		})
	}
}

// mediaDrop — POST /media/{id}/drop: отправитель убирает свой файл почтальона, который больше не
// нужен — фото заменено правкой (заказчик 2026-10-22, ПЛАН-(ИФ) ИФ-В2). Срок ставится на сейчас,
// удаляет уборщик почтальона, как всё по сроку.
func mediaDrop(deps mediaDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		err := deps.store.DropPostman(r.Context(), r.PathValue("mediaID"), id.UserID)
		if errors.Is(err, store.ErrMediaNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "своего файла почтальона с таким номером нет")
			return
		} else if err != nil {
			log.Printf("mediaDrop: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// Пределы файла, себе и за сутки — в [MediaRules], из переменных сервера (ПЛАН-(ИФ) ИФ1). Верхняя
// граница загрузки — предел «себе»: кому файл, ручка загрузки узнаёт только у почтальона, там же
// проверяется предел другому. У Caddy перед s3 остаётся max_size 2GB — предел держит эта ручка.
const (
	// Ссылок пачкой — до 100 (Решения §14).
	maxURLBatch    = 100
	maxChunks      = 1024
	uploadURLTTL   = 15 * time.Minute
	downloadURLTTL = 10 * time.Minute // media-storage.md §2
)

func chunkKey(storageKey string, index int32, total int32) string {
	return blob.ChunkKey(storageKey, index, total)
}

// mediaInit — POST /media/init: регистрация объекта + presigned PUT (или CAS-дедуп).
func mediaInit(deps mediaDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if deps.blobs() == nil {
			writeErr(w, http.StatusServiceUnavailable, "no_storage", "object storage не сконфигурирован (S3_ENDPOINT)")
			return
		}
		var req struct {
			SizeBytes   int64  `json:"size_bytes"`
			Mime        string `json:"mime"`
			IsEncrypted bool   `json:"is_encrypted"`
			ContentHash string `json:"content_hash,omitempty"` // base64url SHA-256 plaintext, только публичное
			ChunkCount  int32  `json:"chunk_count,omitempty"`  // >1 → chunked (клиент режет по 4 MB)
			// Postman — файл вне пузыря в личной переписке (Решения §6): переписка и получатель.
			// Сервер держит его, пока не заберут, и удаляет по правилам почтальона.
			Postman *struct {
				ChatID string `json:"chat_id"`
				PeerID string `json:"peer_id"`
			} `json:"postman,omitempty"`
			// Term — файл на срок, голосовое (ПЛАН-(ВМ) Р3): личная переписка (chat_id и peer_id) или
			// группа (group_id). Сервер держит его срок из правил, скачиваний не считает.
			Term *struct {
				ChatID  string `json:"chat_id,omitempty"`
				PeerID  string `json:"peer_id,omitempty"`
				GroupID string `json:"group_id,omitempty"`
			} `json:"term,omitempty"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		if req.SizeBytes <= 0 || req.SizeBytes > deps.rules.SelfBytes {
			writeErr(w, http.StatusBadRequest, "bad_size", fmt.Sprintf("size_bytes должен быть в (0, %d МБ]", deps.rules.SelfBytes>>20))
			return
		}
		if req.ChunkCount < 0 || req.ChunkCount > maxChunks {
			writeErr(w, http.StatusBadRequest, "bad_chunks", "chunk_count вне диапазона")
			return
		}
		if req.ChunkCount == 0 {
			req.ChunkCount = 1
		}

		var contentHash []byte
		if req.ContentHash != "" {
			if req.IsEncrypted {
				// Контракт §5: CAS только публичное; хэш приватного файла — утечка
				writeErr(w, http.StatusBadRequest, "cas_private", "content_hash допустим только для публичного медиа")
				return
			}
			var err error
			contentHash, err = base64.RawURLEncoding.DecodeString(req.ContentHash)
			if err != nil || len(contentHash) != 32 {
				writeErr(w, http.StatusBadRequest, "bad_hash", "content_hash — base64url SHA-256 (32 байта)")
				return
			}
			// CAS: тот же файл уже есть → заливка не нужна
			if mediaID, err := deps.store.FindMediaByHash(r.Context(), contentHash); err == nil {
				w.Header().Set("Content-Type", "application/json")
				_ = json.NewEncoder(w).Encode(map[string]any{"dedup": true, "media_id": mediaID})
				return
			} else if !errors.Is(err, store.ErrMediaNotFound) {
				log.Printf("mediaInit: CAS lookup: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
		}

		id, _ := auth.FromContext(r.Context())
		media := store.Media{
			OwnerID: id.UserID, Mime: req.Mime, SizeBytes: req.SizeBytes,
			IsEncrypted: req.IsEncrypted, ChunkCount: req.ChunkCount, QuotaBytes: deps.rules.DailyBytes,
		}
		if p := req.Postman; p != nil {
			// Личная переписка — только своя: chat_id выводится из пары, как у сообщения.
			if !req.IsEncrypted || contentHash != nil || p.PeerID == "" || p.ChatID != personalChatID(id.UserID, p.PeerID) {
				writeErr(w, http.StatusBadRequest, "bad_postman", "файл почтальона — зашифрованный, в своей личной переписке")
				return
			}
			if p.PeerID != id.UserID && req.SizeBytes > deps.rules.FileBytes {
				writeErr(w, http.StatusBadRequest, "postman_too_large", fmt.Sprintf("файл другому человеку — до %d МБ (себе — до %d МБ)", deps.rules.FileBytes>>20, deps.rules.SelfBytes>>20))
				return
			}
			media.PostmanChat, media.PostmanPeer, media.OwnerDevice = p.ChatID, p.PeerID, id.DeviceID
		}
		if t := req.Term; t != nil {
			if req.Postman != nil || !req.IsEncrypted || contentHash != nil || req.SizeBytes > deps.rules.FileBytes {
				writeErr(w, http.StatusBadRequest, "bad_term", fmt.Sprintf("файл на срок — зашифрованный, до %d МБ, не почтальон", deps.rules.FileBytes>>20))
				return
			}
			switch {
			case t.GroupID != "" && t.ChatID == "" && t.PeerID == "":
				member, err := isGroupMember(r.Context(), deps, t.GroupID, id.UserID)
				if err != nil {
					log.Printf("mediaInit: участник группы: %v", err)
					writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
					return
				}
				if !member {
					writeErr(w, http.StatusForbidden, "not_member", "файл группы загружает её участник")
					return
				}
				media.TermGroup = t.GroupID
			case t.GroupID == "" && t.PeerID != "" && t.ChatID == personalChatID(id.UserID, t.PeerID):
				media.TermChat, media.TermPeer = t.ChatID, t.PeerID
			default:
				writeErr(w, http.StatusBadRequest, "bad_term", "файл на срок — в своей личной переписке (chat_id и peer_id) или в группе (group_id)")
				return
			}
			media.TermSec, media.OwnerDevice = deps.rules.termSeconds(), id.DeviceID
		}
		m, err := deps.store.CreateMedia(r.Context(), media, contentHash)
		if errors.Is(err, store.ErrUploadQuota) {
			w.Header().Set("Retry-After", "3600")
			writeErr(w, http.StatusTooManyRequests, "upload_quota", fmt.Sprintf("за сутки загружено больше %d МБ — попробуйте позже", deps.rules.DailyBytes>>20))
			return
		}
		if err != nil {
			log.Printf("mediaInit: create: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		urls := make([]string, 0, m.ChunkCount)
		for i := int32(0); i < m.ChunkCount; i++ {
			u, err := deps.blobs().PresignPut(r.Context(), chunkKey(m.StorageKey, i, m.ChunkCount), uploadURLTTL)
			if err != nil {
				log.Printf("mediaInit: presign: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "не выдался upload URL")
				return
			}
			urls = append(urls, u)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"media_id": m.MediaID, "upload_urls": urls})
	}
}

// mediaComplete — POST /media/complete: объект(ы) реально в MinIO → complete.
func mediaComplete(deps mediaDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if deps.blobs() == nil {
			writeErr(w, http.StatusServiceUnavailable, "no_storage", "object storage не сконфигурирован (S3_ENDPOINT)")
			return
		}
		var req struct {
			MediaID string `json:"media_id"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 1024)).Decode(&req); err != nil || req.MediaID == "" {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен media_id")
			return
		}
		m, err := deps.store.GetMedia(r.Context(), req.MediaID)
		if errors.Is(err, store.ErrMediaNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "медиа не найдено")
			return
		} else if err != nil {
			log.Printf("mediaComplete: get: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		id, _ := auth.FromContext(r.Context())
		// Повторную загрузку файла почтальона делает и получатель — у него файл есть (Решения §6).
		if m.OwnerID != id.UserID && !(m.PostmanChat != "" && m.PostmanPeer == id.UserID) {
			writeErr(w, http.StatusForbidden, "not_owner", "завершить загрузку может только владелец")
			return
		}
		var total int64
		for i := int32(0); i < m.ChunkCount; i++ {
			size, err := deps.blobs().Size(r.Context(), chunkKey(m.StorageKey, i, m.ChunkCount))
			if errors.Is(err, blob.ErrNotFound) {
				writeErr(w, http.StatusConflict, "not_uploaded", fmt.Sprintf("чанк %d не загружен", i))
				return
			} else if err != nil {
				log.Printf("mediaComplete: stat: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка object storage")
				return
			}
			total += size
		}
		if err := deps.store.CompleteMedia(r.Context(), m.MediaID, id.UserID, total); err != nil {
			if errors.Is(err, store.ErrMediaNotFound) { // уже complete — идемпотентность
				w.Header().Set("Content-Type", "application/json")
				_ = json.NewEncoder(w).Encode(map[string]any{"media_id": m.MediaID, "duplicate": true})
				return
			}
			log.Printf("mediaComplete: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"media_id": m.MediaID, "size_bytes": total})
	}
}

// mediaURL — GET /media/{id}/url: presigned GET (TTL 10 мин), для чанков — список.
func mediaURL(deps mediaDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if deps.blobs() == nil {
			writeErr(w, http.StatusServiceUnavailable, "no_storage", "object storage не сконфигурирован (S3_ENDPOINT)")
			return
		}
		m, err := deps.store.GetMedia(r.Context(), r.PathValue("mediaID"))
		if errors.Is(err, store.ErrMediaNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "медиа не найдено")
			return
		} else if err != nil {
			log.Printf("mediaURL: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		id, _ := auth.FromContext(r.Context())
		allowed, byPeer := postmanAccess(m, id.UserID, id.DeviceID)
		if allowed && m.TermSec > 0 {
			if allowed, err = termAccess(r.Context(), deps, m, id.UserID); err != nil {
				log.Printf("mediaURL: доступ: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
		}
		if !allowed {
			writeErr(w, http.StatusNotFound, "not_found", "медиа не найдено")
			return
		}
		if m.Status == "gone" && m.TermSec > 0 {
			writeErr(w, http.StatusGone, "media_gone", "файл удалён по сроку")
			return
		}
		if m.Status == "gone" {
			writeErr(w, http.StatusGone, "media_gone", "файл удалён по сроку — попросите прислать снова")
			return
		}
		if m.Status != "complete" {
			writeErr(w, http.StatusConflict, "not_complete", "загрузка не завершена")
			return
		}
		// TODO(права): проверка доступа по чату/сообщению — когда появится связь media↔message
		urls := make([]string, 0, m.ChunkCount)
		for i := int32(0); i < m.ChunkCount; i++ {
			u, err := deps.blobs().PresignGet(r.Context(), chunkKey(m.StorageKey, i, m.ChunkCount), downloadURLTTL)
			if err != nil {
				log.Printf("mediaURL: presign: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "не выдался download URL")
				return
			}
			urls = append(urls, u)
		}
		fetched(r.Context(), deps, m, byPeer)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"media_id": m.MediaID, "mime": m.Mime, "size_bytes": m.SizeBytes,
			"is_encrypted": m.IsEncrypted, "chunk_count": m.ChunkCount, "urls": urls,
		})
	}
}

// mediaURLs — POST /media/urls: ссылки пачкой, до 100 номеров (Решения §14) — лента с картинками
// не ходит за каждой отдельно. Незавершённое и пропавшее — в `missing`, а не отказом всей пачки.
func mediaURLs(deps mediaDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if deps.blobs() == nil {
			writeErr(w, http.StatusServiceUnavailable, "no_storage", "object storage не сконфигурирован (S3_ENDPOINT)")
			return
		}
		var req struct {
			MediaIDs []string `json:"media_ids"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 16<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		if len(req.MediaIDs) == 0 || len(req.MediaIDs) > maxURLBatch {
			writeErr(w, http.StatusBadRequest, "bad_batch", fmt.Sprintf("номеров от 1 до %d", maxURLBatch))
			return
		}
		found, err := deps.store.MediaMany(r.Context(), req.MediaIDs)
		if err != nil {
			log.Printf("mediaURLs: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		byID := make(map[string]store.Media, len(found))
		for _, m := range found {
			byID[m.MediaID] = m
		}
		items := make([]map[string]any, 0, len(found))
		missing := []string{}
		who, _ := auth.FromContext(r.Context())
		for _, id := range req.MediaIDs {
			m, ok := byID[id]
			allowed, byPeer := postmanAccess(m, who.UserID, who.DeviceID)
			if ok && allowed && m.TermSec > 0 {
				if allowed, err = termAccess(r.Context(), deps, m, who.UserID); err != nil {
					log.Printf("mediaURLs: доступ: %v", err)
					writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
					return
				}
			}
			if !ok || !allowed || m.Status != "complete" {
				missing = append(missing, id)
				continue
			}
			defer fetched(r.Context(), deps, m, byPeer)
			urls := make([]string, 0, m.ChunkCount)
			for i := int32(0); i < m.ChunkCount; i++ {
				u, err := deps.blobs().PresignGet(r.Context(), chunkKey(m.StorageKey, i, m.ChunkCount), downloadURLTTL)
				if err != nil {
					log.Printf("mediaURLs: presign: %v", err)
					writeErr(w, http.StatusInternalServerError, "internal", "не выдался download URL")
					return
				}
				urls = append(urls, u)
			}
			items = append(items, map[string]any{
				"media_id": m.MediaID, "mime": m.Mime, "size_bytes": m.SizeBytes,
				"is_encrypted": m.IsEncrypted, "chunk_count": m.ChunkCount, "urls": urls,
			})
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"items": items, "missing": missing})
	}
}

// postmanAccess — кому файл почтальона отдаётся: отправителю и получателю (Решения §6). byPeer —
// скачивает получатель: его первое скачивание ставит срок. Себе (получатель — сам отправитель)
// «получатель» — любое другое своё устройство, не загрузившее файл. Обычное медиа — как прежде.
func postmanAccess(m store.Media, userID, deviceID string) (allowed, byPeer bool) {
	if m.PostmanChat == "" {
		return true, false
	}
	if userID != m.OwnerID && userID != m.PostmanPeer {
		return false, false
	}
	if m.PostmanPeer == m.OwnerID {
		return true, deviceID != m.OwnerDevice
	}
	return true, userID == m.PostmanPeer
}

// termAccess — кому отдаётся файл на срок (ПЛАН-(ВМ) Р3): в личной — отправителю и собеседнику, в
// группе — её участникам сейчас. Скачивания не считаются.
func termAccess(ctx context.Context, deps mediaDeps, m store.Media, userID string) (bool, error) {
	if m.TermGroup != "" {
		return isGroupMember(ctx, deps, m.TermGroup, userID)
	}
	return userID == m.OwnerID || userID == m.TermPeer, nil
}

// isGroupMember — состоит ли человек в группе сейчас.
func isGroupMember(ctx context.Context, deps mediaDeps, groupID, userID string) (bool, error) {
	_, _, err := deps.store.GroupMemberInfo(ctx, groupID, userID)
	if errors.Is(err, store.ErrNotMember) {
		return false, nil
	}
	return err == nil, err
}

// fetched — получатель забрал файл почтальона: срок по правилам §6; первое скачивание — отправителю
// событие `media.fetched` (галочка «приложение забрало»).
func fetched(ctx context.Context, deps mediaDeps, m store.Media, byPeer bool) {
	if m.PostmanChat == "" || !byPeer {
		return
	}
	first, err := deps.store.FetchPostman(ctx, m.MediaID, true, downloadURLTTL)
	if err != nil {
		log.Printf("media: срок почтальона %s: %v", m.MediaID, err)
		return
	}
	if first && deps.notifier != nil {
		deps.notifier.Users(ctx, []string{m.OwnerID}, "media.fetched", map[string]any{
			"media_id": m.MediaID, "chat_id": m.PostmanChat,
		})
	}
}

// mediaRequest — POST /media/{id}/request: опоздавшее устройство просит файл почтальона снова
// (Решения §6). Просьба — событие `media.wanted` устройствам отправителя и получателя: загрузит
// то, у которого файл есть.
func mediaRequest(deps mediaDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		m, err := deps.store.GetMedia(r.Context(), r.PathValue("mediaID"))
		id, _ := auth.FromContext(r.Context())
		if errors.Is(err, store.ErrMediaNotFound) || (err == nil && (m.PostmanChat == "" || (id.UserID != m.OwnerID && id.UserID != m.PostmanPeer))) {
			writeErr(w, http.StatusNotFound, "not_found", "файла почтальона нет")
			return
		} else if err != nil {
			log.Printf("mediaRequest: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		users := []string{m.OwnerID}
		if m.PostmanPeer != m.OwnerID {
			users = append(users, m.PostmanPeer)
		}
		if deps.notifier != nil {
			deps.notifier.Users(r.Context(), users, "media.wanted", map[string]any{
				"media_id": m.MediaID, "chat_id": m.PostmanChat, "by_device": id.DeviceID,
			})
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		_ = json.NewEncoder(w).Encode(map[string]any{"media_id": m.MediaID, "status": m.Status})
	}
}

// mediaReupload — POST /media/{id}/reupload: удалённый по сроку файл почтальона загружает снова
// отправитель или получатель — тот же номер и ключ, новые ссылки на загрузку; дальше /media/complete.
func mediaReupload(deps mediaDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if deps.blobs() == nil {
			writeErr(w, http.StatusServiceUnavailable, "no_storage", "object storage не сконфигурирован (S3_ENDPOINT)")
			return
		}
		id, _ := auth.FromContext(r.Context())
		m, err := deps.store.ReopenMedia(r.Context(), r.PathValue("mediaID"), id.UserID)
		if errors.Is(err, store.ErrMediaNotFound) {
			writeErr(w, http.StatusConflict, "not_gone", "загружать снова нечего: файл на месте или не ваш")
			return
		} else if err != nil {
			log.Printf("mediaReupload: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		urls := make([]string, 0, m.ChunkCount)
		for i := int32(0); i < m.ChunkCount; i++ {
			u, err := deps.blobs().PresignPut(r.Context(), chunkKey(m.StorageKey, i, m.ChunkCount), uploadURLTTL)
			if err != nil {
				log.Printf("mediaReupload: presign: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "не выдался upload URL")
				return
			}
			urls = append(urls, u)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"media_id": m.MediaID, "upload_urls": urls})
	}
}
