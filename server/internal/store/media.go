// Медиа: запись о файле, дедупликация по хэшу, подтверждение загрузки.
package store

import (
	"context"
	"errors"
	"time"

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
	// Почтальон (Решения §6): личная переписка, получатель и устройство, загрузившее файл. Пусто —
	// обычное медиа. Свои устройства отправителя файл не «забирают» — по OwnerDevice видно, кто это.
	PostmanChat string
	PostmanPeer string
	OwnerDevice string
	// Файл на срок (ПЛАН-(ВМ) Р3): голосовое. TermSec > 0 — сервер держит файл столько секунд от
	// загрузки, скачиваний не считает. Личная — TermChat и TermPeer, группа — TermGroup.
	TermSec   int32
	TermChat  string
	TermPeer  string
	TermGroup string
}

// mediaCols — столбцы записи о файле в порядке [scanMedia].
const mediaCols = `media_id, owner_id, storage_key, mime, size_bytes, is_encrypted, chunk_count, status,
	COALESCE(postman_chat::text, ''), COALESCE(postman_peer::text, ''), COALESCE(owner_device::text, ''),
	term_sec, COALESCE(term_chat::text, ''), COALESCE(term_peer::text, ''), COALESCE(term_group::text, '')`

func scanMedia(row pgx.Row) (Media, error) {
	var m Media
	err := row.Scan(&m.MediaID, &m.OwnerID, &m.StorageKey, &m.Mime, &m.SizeBytes, &m.IsEncrypted, &m.ChunkCount, &m.Status,
		&m.PostmanChat, &m.PostmanPeer, &m.OwnerDevice, &m.TermSec, &m.TermChat, &m.TermPeer, &m.TermGroup)
	return m, err
}

// Сроки почтальона (Решения §6): час на первое скачивание, неделя — если не скачал никто.
const (
	PostmanHour = time.Hour
	PostmanWeek = 7 * 24 * time.Hour
)

// ErrMediaGone — файл почтальона удалён по сроку; строка осталась — по ней просят прислать снова.
var ErrMediaGone = errors.New("файл удалён по сроку")

// ErrUploadQuota — за сутки заявлено больше квоты.
var ErrUploadQuota = errors.New("квота загрузок за сутки исчерпана")

// CreateMedia регистрирует pending-объект; storage_key достраивается по media_id.
func (s *Store) CreateMedia(ctx context.Context, m Media, contentHash []byte) (Media, error) {
	// Квота проверяется той же командой, что вставляет: между подсчётом и записью не влезет
	// вторая загрузка того же человека. Считается заявленное — и незавершённое тоже: иначе квоту
	// обходили бы, не подтверждая загрузку.
	err := s.pool.QueryRow(ctx, `
		INSERT INTO media_objects (owner_id, storage_key, mime, size_bytes, is_encrypted, content_hash, chunk_count,
			postman_chat, postman_peer, owner_device, term_sec, term_chat, term_peer, term_group)
		SELECT $1::uuid, '', $2::text, $3::bigint, $4::boolean, $5::bytea, $6::int,
			NULLIF($8, '')::uuid, NULLIF($9, '')::uuid, NULLIF($10, '')::uuid,
			$11::int, NULLIF($12, '')::uuid, NULLIF($13, '')::uuid, NULLIF($14, '')::uuid
		 WHERE $7::bigint = 0 OR $3::bigint + (SELECT COALESCE(sum(size_bytes), 0) FROM media_objects
		                                       WHERE owner_id = $1::uuid AND created_at > now() - interval '1 day') <= $7::bigint
		RETURNING media_id`,
		m.OwnerID, m.Mime, m.SizeBytes, m.IsEncrypted, contentHash, m.ChunkCount, m.QuotaBytes,
		m.PostmanChat, m.PostmanPeer, m.OwnerDevice, m.TermSec, m.TermChat, m.TermPeer, m.TermGroup).Scan(&m.MediaID)
	if errors.Is(err, pgx.ErrNoRows) {
		return m, ErrUploadQuota
	}
	if err != nil {
		return m, err
	}
	m.StorageKey = "media/" + m.MediaID
	// Файлы почтальона — под своим префиксом: правило жизненного цикла хранилища на 7 дней
	// (страховка уборщика, Решения §6) ставится на префикс и не задевает аватары и картинки.
	if m.PostmanChat != "" {
		m.StorageKey = "postman/" + m.MediaID
	}
	if m.TermSec > 0 {
		m.StorageKey = "term/" + m.MediaID
	}
	m.Status = "pending"
	_, err = s.pool.Exec(ctx, `UPDATE media_objects SET storage_key = $2 WHERE media_id = $1`, m.MediaID, m.StorageKey)
	return m, err
}

var ErrMediaNotFound = errors.New("медиа не найдено")

// GetMedia — метаданные объекта.
func (s *Store) GetMedia(ctx context.Context, mediaID string) (Media, error) {
	m, err := scanMedia(s.pool.QueryRow(ctx, `SELECT `+mediaCols+` FROM media_objects WHERE media_id = $1`, mediaID))
	if errors.Is(err, pgx.ErrNoRows) {
		return m, ErrMediaNotFound
	}
	return m, err
}

// MediaMany — метаданные пачки объектов (ссылки пачкой, Решения §14). Чего нет — того нет в
// ответе: вызывающий называет пропавшее сам.
func (s *Store) MediaMany(ctx context.Context, ids []string) ([]Media, error) {
	rows, err := s.pool.Query(ctx, `SELECT `+mediaCols+` FROM media_objects WHERE media_id::text = ANY($1)`, ids)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Media
	for rows.Next() {
		m, err := scanMedia(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// CompleteMedia фиксирует фактический размер и переводит в complete — владелец, а у файла почтальона
// ещё и получатель: повторную загрузку делает то устройство, у которого файл есть (Решения §6). У
// почтальона с загрузки идёт срок: неделя, пока никто не скачал; у файла на срок — его срок.
func (s *Store) CompleteMedia(ctx context.Context, mediaID, ownerID string, actualSize int64) error {
	tag, err := s.pool.Exec(ctx, `
		UPDATE media_objects SET status = 'complete', size_bytes = $3,
		       uploaded_at = CASE WHEN postman_chat IS NULL AND term_sec = 0 THEN uploaded_at ELSE now() END,
		       first_fetch_at = NULL,
		       expires_at = CASE WHEN term_sec > 0 THEN now() + make_interval(secs => term_sec)
		                         WHEN postman_chat IS NULL THEN NULL ELSE now() + make_interval(secs => $4) END
		WHERE media_id = $1 AND status = 'pending'
		  AND (owner_id = $2 OR (postman_chat IS NOT NULL AND postman_peer = $2))`,
		mediaID, ownerID, actualSize, PostmanWeek.Seconds())
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

// FetchPostman — файл почтальона забирают: первое скачивание получателем ставит срок (Решения §6).
// В течение часа после загрузки — файл живёт до конца часа; позже — уходит, как только истечёт
// ссылка на это скачивание ([after]). Скачивание своими устройствами отправителя срока не трогает.
// first — это было первое скачивание получателем: отправителю — галочка.
func (s *Store) FetchPostman(ctx context.Context, mediaID string, byPeer bool, after time.Duration) (first bool, err error) {
	if !byPeer {
		return false, nil
	}
	tag, err := s.pool.Exec(ctx, `
		UPDATE media_objects SET first_fetch_at = now(),
		       expires_at = CASE WHEN now() <= uploaded_at + make_interval(secs => $2)
		                         THEN uploaded_at + make_interval(secs => $2)
		                         ELSE now() + make_interval(secs => $3) END
		WHERE media_id = $1 AND postman_chat IS NOT NULL AND status = 'complete' AND first_fetch_at IS NULL`,
		mediaID, PostmanHour.Seconds(), after.Seconds())
	if err != nil {
		return false, err
	}
	return tag.RowsAffected() > 0, nil
}

// TakeExpiredPostman — файлы почтальона и файлы на срок, чей срок вышел: строка становится gone и
// возвращается — объекты в хранилище удаляет вызывающий. Строка остаётся: по ней почтальону просят
// прислать снова, а голосовое показывается «недоступным».
func (s *Store) TakeExpiredPostman(ctx context.Context, limit int) ([]Media, error) {
	rows, err := s.pool.Query(ctx, `
		UPDATE media_objects SET status = 'gone'
		WHERE media_id IN (SELECT media_id FROM media_objects
		                    WHERE (postman_chat IS NOT NULL OR term_sec > 0) AND status = 'complete' AND expires_at <= now()
		                    ORDER BY expires_at LIMIT $1)
		RETURNING `+mediaCols, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Media
	for rows.Next() {
		m, err := scanMedia(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// DropPostman — отправитель убирает свой файл почтальона (фото заменено правкой, ПЛАН-(ИФ) ИФ-В2):
// срок — сейчас, объекты удалит уборщик почтальона. Незавершённый — тоже: он уже никому не нужен.
func (s *Store) DropPostman(ctx context.Context, mediaID, ownerID string) error {
	tag, err := s.pool.Exec(ctx, `
		UPDATE media_objects SET expires_at = now(), status = 'complete'
		WHERE media_id = $1 AND owner_id = $2 AND postman_chat IS NOT NULL AND status IN ('complete', 'pending')`,
		mediaID, ownerID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrMediaNotFound
	}
	return nil
}

// ReopenMedia — повторная загрузка удалённого по сроку файла почтальона (Решения §6: «опоздавшее
// устройство просит повторно — файл снова загружает устройство, у которого он есть»). Загружать
// может отправитель или получатель; файл снова pending под тем же ключом.
func (s *Store) ReopenMedia(ctx context.Context, mediaID, userID string) (Media, error) {
	tag, err := s.pool.Exec(ctx, `
		UPDATE media_objects SET status = 'pending', first_fetch_at = NULL, expires_at = NULL
		WHERE media_id = $1 AND postman_chat IS NOT NULL AND status = 'gone'
		  AND (owner_id = $2 OR postman_peer = $2)`, mediaID, userID)
	if err != nil {
		return Media{}, err
	}
	if tag.RowsAffected() == 0 {
		return Media{}, ErrMediaNotFound
	}
	return s.GetMedia(ctx, mediaID)
}
