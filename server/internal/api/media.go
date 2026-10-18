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
}

// RegisterMedia — маршруты медиа: инициация загрузки, подтверждение, ссылка, ссылки пачкой; у
// почтальона ещё просьба прислать снова и повторная загрузка (Решения §6).
func RegisterMedia(mux *http.ServeMux, st MediaStore, blobs func() *blob.Client, n *Notifier, requireDevice Middleware) {
	deps := mediaDeps{store: st, blobs: blobs, notifier: n}
	mux.HandleFunc("POST /api/v1/media/init", requireDevice(mediaInit(deps)))
	mux.HandleFunc("POST /api/v1/media/complete", requireDevice(mediaComplete(deps)))
	mux.HandleFunc("GET /api/v1/media/{mediaID}/url", requireDevice(mediaURL(deps)))
	mux.HandleFunc("POST /api/v1/media/urls", requireDevice(mediaURLs(deps)))
	mux.HandleFunc("POST /api/v1/media/{mediaID}/request", requireDevice(mediaRequest(deps)))
	mux.HandleFunc("POST /api/v1/media/{mediaID}/reupload", requireDevice(mediaReupload(deps)))
}

const (
	// Предел файла: 10 МБ, себе — 50 МБ (заказчик 2026-10-16…18, doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §6);
	// прежние 2 ГБ сняты. Здесь — верхняя граница, 50 МБ: кому файл, ручка загрузки пока не знает.
	// Предел 10 МБ для чужого получателя проверяется там, где получатель известен, — у почтальона
	// (ПЛАН-(ПФ) ПФ4, ПЛАН-(РС) РС3). У Caddy перед s3 остаётся max_size 2GB — предел
	// держит эта ручка: больше 50 МБ ссылка на загрузку не выдаётся.
	maxMediaBytes = 50 << 20
	// Квота загрузок на человека (Решения §14): заявлено за последние сутки, вместе с новым файлом.
	// Числа решения не назвали — 1 ГБ: двадцать файлов по 50 МБ себе и запас на картинки.
	uploadQuotaBytes = 1 << 30
	// Ссылок пачкой — до 100 (Решения §14).
	maxURLBatch = 100
	// Почтальон: файл другому человеку — до 10 МБ, себе — до 50 МБ (Решения §6).
	maxPostmanBytes = 10 << 20
	maxChunks       = 1024
	uploadURLTTL    = 15 * time.Minute
	downloadURLTTL  = 10 * time.Minute // media-storage.md §2
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
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		if req.SizeBytes <= 0 || req.SizeBytes > maxMediaBytes {
			writeErr(w, http.StatusBadRequest, "bad_size", "size_bytes должен быть в (0, 50 МБ]")
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
			IsEncrypted: req.IsEncrypted, ChunkCount: req.ChunkCount, QuotaBytes: uploadQuotaBytes,
		}
		if p := req.Postman; p != nil {
			// Личная переписка — только своя: chat_id выводится из пары, как у сообщения.
			if !req.IsEncrypted || contentHash != nil || p.PeerID == "" || p.ChatID != personalChatID(id.UserID, p.PeerID) {
				writeErr(w, http.StatusBadRequest, "bad_postman", "файл почтальона — зашифрованный, в своей личной переписке")
				return
			}
			if p.PeerID != id.UserID && req.SizeBytes > maxPostmanBytes {
				writeErr(w, http.StatusBadRequest, "postman_too_large", "файл другому человеку — до 10 МБ (себе — до 50 МБ)")
				return
			}
			media.PostmanChat, media.PostmanPeer, media.OwnerDevice = p.ChatID, p.PeerID, id.DeviceID
		}
		m, err := deps.store.CreateMedia(r.Context(), media, contentHash)
		if errors.Is(err, store.ErrUploadQuota) {
			w.Header().Set("Retry-After", "3600")
			writeErr(w, http.StatusTooManyRequests, "upload_quota", "за сутки загружено больше 1 ГБ — попробуйте позже")
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
		if !allowed {
			writeErr(w, http.StatusNotFound, "not_found", "медиа не найдено")
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
