// Media Service (media-storage.md §2): файлы не проксируются через бэкенд —
// только presigned URL в MinIO. CAS-дедупликация — исключительно публичное
// (для приватного хэш открытого файла не принимается вовсе: утечка «файл уже был»).
package api

import (
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

const (
	maxMediaBytes  = 2 << 30 // 2 GB — лимит presigned PUT (Caddy: request_body max_size 2GB)
	maxChunks      = 1024
	uploadURLTTL   = 15 * time.Minute
	downloadURLTTL = 10 * time.Minute // media-storage.md §2
)

func chunkKey(storageKey string, index int32, total int32) string {
	if total <= 1 {
		return storageKey
	}
	return fmt.Sprintf("%s/chunk-%d", storageKey, index)
}

// mediaInit — POST /media/init: регистрация объекта + presigned PUT (или CAS-дедуп).
func (s *Server) mediaInit(w http.ResponseWriter, r *http.Request) {
	if s.Blob == nil {
		writeErr(w, http.StatusServiceUnavailable, "no_storage", "object storage не сконфигурирован (S3_ENDPOINT)")
		return
	}
	var req struct {
		SizeBytes   int64  `json:"size_bytes"`
		Mime        string `json:"mime"`
		IsEncrypted bool   `json:"is_encrypted"`
		ContentHash string `json:"content_hash,omitempty"` // base64url SHA-256 plaintext, только публичное
		ChunkCount  int32  `json:"chunk_count,omitempty"`  // >1 → chunked (клиент режет по 4 MB)
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
		return
	}
	if req.SizeBytes <= 0 || req.SizeBytes > maxMediaBytes {
		writeErr(w, http.StatusBadRequest, "bad_size", "size_bytes должен быть в (0, 2 GB]")
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
		if mediaID, err := s.Store.FindMediaByHash(r.Context(), contentHash); err == nil {
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
	m, err := s.Store.CreateMedia(r.Context(), store.Media{
		OwnerID: id.UserID, Mime: req.Mime, SizeBytes: req.SizeBytes,
		IsEncrypted: req.IsEncrypted, ChunkCount: req.ChunkCount,
	}, contentHash)
	if err != nil {
		log.Printf("mediaInit: create: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	urls := make([]string, 0, m.ChunkCount)
	for i := int32(0); i < m.ChunkCount; i++ {
		u, err := s.Blob.PresignPut(r.Context(), chunkKey(m.StorageKey, i, m.ChunkCount), uploadURLTTL)
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

// mediaComplete — POST /media/complete: объект(ы) реально в MinIO → complete.
func (s *Server) mediaComplete(w http.ResponseWriter, r *http.Request) {
	if s.Blob == nil {
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
	m, err := s.Store.GetMedia(r.Context(), req.MediaID)
	if errors.Is(err, store.ErrMediaNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "медиа не найдено")
		return
	} else if err != nil {
		log.Printf("mediaComplete: get: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	id, _ := auth.FromContext(r.Context())
	if m.OwnerID != id.UserID {
		writeErr(w, http.StatusForbidden, "not_owner", "завершить загрузку может только владелец")
		return
	}
	var total int64
	for i := int32(0); i < m.ChunkCount; i++ {
		size, err := s.Blob.Size(r.Context(), chunkKey(m.StorageKey, i, m.ChunkCount))
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
	if err := s.Store.CompleteMedia(r.Context(), m.MediaID, id.UserID, total); err != nil {
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

// mediaURL — GET /media/{id}/url: presigned GET (TTL 10 мин), для чанков — список.
func (s *Server) mediaURL(w http.ResponseWriter, r *http.Request) {
	if s.Blob == nil {
		writeErr(w, http.StatusServiceUnavailable, "no_storage", "object storage не сконфигурирован (S3_ENDPOINT)")
		return
	}
	m, err := s.Store.GetMedia(r.Context(), r.PathValue("mediaID"))
	if errors.Is(err, store.ErrMediaNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "медиа не найдено")
		return
	} else if err != nil {
		log.Printf("mediaURL: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	if m.Status != "complete" {
		writeErr(w, http.StatusConflict, "not_complete", "загрузка не завершена")
		return
	}
	// TODO(права): проверка доступа по чату/сообщению — когда появится связь media↔message
	urls := make([]string, 0, m.ChunkCount)
	for i := int32(0); i < m.ChunkCount; i++ {
		u, err := s.Blob.PresignGet(r.Context(), chunkKey(m.StorageKey, i, m.ChunkCount), downloadURLTTL)
		if err != nil {
			log.Printf("mediaURL: presign: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "не выдался download URL")
			return
		}
		urls = append(urls, u)
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"media_id": m.MediaID, "mime": m.Mime, "size_bytes": m.SizeBytes,
		"is_encrypted": m.IsEncrypted, "chunk_count": m.ChunkCount, "urls": urls,
	})
}
