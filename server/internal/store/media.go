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
	// QuotaBytes — сколько человек может заявить к загрузке за последние сутки вместе с этим
	// файлом (Решения §14 «квота загрузок на человека»); 0 — без квоты (проверки, внутреннее).
	QuotaBytes int64
}

// ErrUploadQuota — за сутки заявлено больше квоты.
var ErrUploadQuota = errors.New("квота загрузок за сутки исчерпана")

// CreateMedia регистрирует pending-объект; storage_key достраивается по media_id.
func (s *Store) CreateMedia(ctx context.Context, m Media, contentHash []byte) (Media, error) {
	// Квота проверяется той же командой, что вставляет: между подсчётом и записью не влезет
	// вторая загрузка того же человека. Считается заявленное — и незавершённое тоже: иначе квоту
	// обходили бы, не подтверждая загрузку.
	err := s.pool.QueryRow(ctx, `
		INSERT INTO media_objects (owner_id, storage_key, mime, size_bytes, is_encrypted, content_hash, chunk_count)
		SELECT $1::uuid, '', $2::text, $3::bigint, $4::boolean, $5::bytea, $6::int
		 WHERE $7::bigint = 0 OR $3::bigint + (SELECT COALESCE(sum(size_bytes), 0) FROM media_objects
		                                       WHERE owner_id = $1::uuid AND created_at > now() - interval '1 day') <= $7::bigint
		RETURNING media_id`,
		m.OwnerID, m.Mime, m.SizeBytes, m.IsEncrypted, contentHash, m.ChunkCount, m.QuotaBytes).Scan(&m.MediaID)
	if errors.Is(err, pgx.ErrNoRows) {
		return m, ErrUploadQuota
	}
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

// MediaMany — метаданные пачки объектов (ссылки пачкой, Решения §14). Чего нет — того нет в
// ответе: вызывающий называет пропавшее сам.
func (s *Store) MediaMany(ctx context.Context, ids []string) ([]Media, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT media_id, owner_id, storage_key, mime, size_bytes, is_encrypted, chunk_count, status
		FROM media_objects WHERE media_id::text = ANY($1)`, ids)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Media
	for rows.Next() {
		var m Media
		if err := rows.Scan(&m.MediaID, &m.OwnerID, &m.StorageKey, &m.Mime, &m.SizeBytes, &m.IsEncrypted, &m.ChunkCount, &m.Status); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
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
