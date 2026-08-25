// Медиа: запись о файле, дедупликация по хэшу, подтверждение загрузки.
package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
)

// ── Медиа ──
type Media struct {
	MediaID     string
	OwnerID     string
	StorageKey  string
	Mime        string
	SizeBytes   int64
	IsEncrypted bool
	ChunkCount  int32
	Status      string
}

// CreateMedia регистрирует pending-объект; storage_key достраивается по media_id.
func (s *Store) CreateMedia(ctx context.Context, m Media, contentHash []byte) (Media, error) {
	err := s.pool.QueryRow(ctx, `
		INSERT INTO media_objects (owner_id, storage_key, mime, size_bytes, is_encrypted, content_hash, chunk_count)
		VALUES ($1, '', $2, $3, $4, $5, $6)
		RETURNING media_id`,
		m.OwnerID, m.Mime, m.SizeBytes, m.IsEncrypted, contentHash, m.ChunkCount).Scan(&m.MediaID)
	if err != nil {
		return m, err
	}
	m.StorageKey = "media/" + m.MediaID
	m.Status = "pending"
	_, err = s.pool.Exec(ctx, `UPDATE media_objects SET storage_key = $2 WHERE media_id = $1`, m.MediaID, m.StorageKey)
	return m, err
}

var ErrMediaNotFound = errors.New("медиа не найдено")

// GetMedia — метаданные объекта.
func (s *Store) GetMedia(ctx context.Context, mediaID string) (Media, error) {
	var m Media
	err := s.pool.QueryRow(ctx, `
		SELECT media_id, owner_id, storage_key, mime, size_bytes, is_encrypted, chunk_count, status
		FROM media_objects WHERE media_id = $1`, mediaID).
		Scan(&m.MediaID, &m.OwnerID, &m.StorageKey, &m.Mime, &m.SizeBytes, &m.IsEncrypted, &m.ChunkCount, &m.Status)
	if errors.Is(err, pgx.ErrNoRows) {
		return m, ErrMediaNotFound
	}
	return m, err
}

// CompleteMedia фиксирует фактический размер и переводит в complete (только владелец).
func (s *Store) CompleteMedia(ctx context.Context, mediaID, ownerID string, actualSize int64) error {
	tag, err := s.pool.Exec(ctx, `
		UPDATE media_objects SET status = 'complete', size_bytes = $3
		WHERE media_id = $1 AND owner_id = $2 AND status = 'pending'`, mediaID, ownerID, actualSize)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrMediaNotFound
	}
	return nil
}

// FindMediaByHash — CAS-дедупликация (только публичное): complete-объект с тем же SHA-256.
func (s *Store) FindMediaByHash(ctx context.Context, contentHash []byte) (string, error) {
	var mediaID string
	err := s.pool.QueryRow(ctx, `
		SELECT media_id FROM media_objects
		WHERE content_hash = $1 AND status = 'complete'`, contentHash).Scan(&mediaID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrMediaNotFound
	}
	return mediaID, err
}
