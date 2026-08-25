// Групповые ключи: версии, ротация и раздача обёрток.
//
// Здесь же доказательства причины ротации: сервер не верит названной причине
// на слово и проверяет её по своим данным (ADR-0017).
package store

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
)

// ── Групповые ключи ──
type GroupRotation struct {
	GroupID            string
	GKVersion          int32
	RotatedBy          string
	SenderEphemeralPub []byte
	EscrowMlkemCt      []byte
	EscrowWrappedKey   []byte
	EscrowKeyVersion   int32
	// EscrowEpoch — эпоха ключа, которым завёрнут escrow-блоб (ADR-0017). Берётся из
	// метаданных анклава по EscrowKeyVersion, а не из часов сервера: клиент мог
	// зашифровать на устаревший ключ, и записать «сейчас» значило бы записать неправду.
	EscrowEpoch string
	Reason      string
	WrappedKeys map[string][]byte // recipient device_id/vu_id → wrapped_GK
}

var ErrVersionConflict = errors.New("gk_version не следует за текущей версией")

// SaveGroupRotation атомарно кладёт версию GK: строго current+1 (первая — 1).
func (s *Store) SaveGroupRotation(ctx context.Context, rot GroupRotation) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck — no-op после Commit

	// Гонку параллельных ротаций одной группы исключает advisory-блокировка транзакции
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`, rot.GroupID); err != nil {
		return err
	}
	var current int32
	if err := tx.QueryRow(ctx,
		`SELECT COALESCE(MAX(gk_version), 0) FROM group_key_history WHERE group_id = $1`,
		rot.GroupID).Scan(&current); err != nil {
		return err
	}
	if rot.GKVersion != current+1 {
		return fmt.Errorf("%w: текущая %d, предложена %d", ErrVersionConflict, current, rot.GKVersion)
	}
	// Пустая эпоха пишется как NULL, а не как пустая строка: NULL никогда не равен
	// текущей эпохе, поэтому такая группа честно ротируется при первой активности.
	var epoch any
	if rot.EscrowEpoch != "" {
		epoch = rot.EscrowEpoch
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO group_key_history (group_id, gk_version, rotated_by, sender_ephemeral_pub,
			escrow_mlkem_ct, escrow_wrapped_key, escrow_key_version, escrow_epoch, reason)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)`,
		rot.GroupID, rot.GKVersion, rot.RotatedBy, rot.SenderEphemeralPub,
		rot.EscrowMlkemCt, rot.EscrowWrappedKey, rot.EscrowKeyVersion, epoch, rot.Reason); err != nil {
		return err
	}
	for recipient, wrapped := range rot.WrappedKeys {
		if _, err := tx.Exec(ctx, `
			INSERT INTO group_wrapped_keys (group_id, gk_version, recipient, wrapped)
			VALUES ($1,$2,$3,$4)`, rot.GroupID, rot.GKVersion, recipient, wrapped); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

// LatestGroupRotation — последняя ротация группы: версия, эпоха escrow, причина и
// время. По ней сервер решает три вещи (ADR-0017): нужна ли ротация по эпохе, не
// слишком ли часто ротируют и что отдать клиенту в GET /keys.
//
// Группа без ротаций возвращает нулевую версию и пустую эпоху — это не ошибка, а
// нормальное состояние только что созданной группы.
type GroupRotationInfo struct {
	GKVersion   int32
	EscrowEpoch string // пусто — ротация была до введения ADR-0017 либо ротаций не было
	Reason      string
	RotatedAt   time.Time
}

func (s *Store) LatestGroupRotation(ctx context.Context, groupID string) (GroupRotationInfo, error) {
	var info GroupRotationInfo
	var epoch *string
	err := s.pool.QueryRow(ctx, `
		SELECT gk_version, escrow_epoch, reason, rotated_at
		FROM group_key_history
		WHERE group_id = $1
		ORDER BY gk_version DESC
		LIMIT 1`, groupID).Scan(&info.GKVersion, &epoch, &info.Reason, &info.RotatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return GroupRotationInfo{}, nil
	}
	if err != nil {
		return GroupRotationInfo{}, err
	}
	if epoch != nil {
		info.EscrowEpoch = *epoch
	}
	return info, nil
}

// RotationEvidence — то, чем сервер подтверждает названную причину ротации
// (ADR-0017 §7). Считается по состоянию сервера, а не со слов клиента.
type RotationEvidence struct {
	MessagesSince int  // сообщений в группе после последней ротации
	Joined        bool // кто-то вошёл после неё
	Left          bool // кто-то вышел или исключён после неё
	DeviceRevoked bool // у кого-то из участников отозвано устройство
}

// RotationEvidenceSince собирает подтверждения одним походом в базу.
//
// Момент отсчёта — время последней ротации. До первой ротации подтверждать нечего:
// вызывающий такую группу не проверяет вовсе, потому что первая версия ключа нужна ей
// в любом случае.
func (s *Store) RotationEvidenceSince(ctx context.Context, groupID string, since time.Time) (RotationEvidence, error) {
	var e RotationEvidence
	err := s.pool.QueryRow(ctx, `
		SELECT
			(SELECT count(*) FROM group_messages WHERE group_id = $1 AND created_at > $2),
			EXISTS (SELECT 1 FROM memberships
			        WHERE target_type = 'group' AND target_id = $1 AND joined_at > $2),
			EXISTS (SELECT 1 FROM memberships
			        WHERE target_type = 'group' AND target_id = $1 AND left_at > $2),
			EXISTS (SELECT 1 FROM devices d
			        JOIN memberships m ON m.user_id = d.user_id
			        WHERE m.target_type = 'group' AND m.target_id = $1 AND d.revoked_at > $2)
	`, groupID, since).Scan(&e.MessagesSince, &e.Joined, &e.Left, &e.DeviceRevoked)
	return e, err
}

// DeviceGroupKey — wrapped_GK одной версии для конкретного устройства.
type DeviceGroupKey struct {
	GKVersion          int32
	SenderEphemeralPub []byte
	Wrapped            []byte
}

// MissingGKVersions — версии GK группы, для которых у устройства НЕТ обёртки
// (история до входа устройства). Источник запроса восстановления (ADR-0010 §этап 1).
func (s *Store) MissingGKVersions(ctx context.Context, groupID, deviceID string) ([]int32, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT h.gk_version FROM group_key_history h
		WHERE h.group_id = $1
		  AND NOT EXISTS (
		    SELECT 1 FROM group_wrapped_keys w
		    WHERE w.group_id = h.group_id AND w.gk_version = h.gk_version AND w.recipient = $2)
		ORDER BY h.gk_version`, groupID, deviceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []int32
	for rows.Next() {
		var v int32
		if err := rows.Scan(&v); err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

// HelperDevices — устройства активных участников группы (кроме requester),
// у которых ЕСТЬ обёртка хотя бы одной из versions — кандидаты в помощники.
func (s *Store) HelperDevices(ctx context.Context, groupID, requester string, versions []int32) ([]string, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT DISTINCT w.recipient
		FROM group_wrapped_keys w
		JOIN devices d ON d.device_id = w.recipient AND d.revoked_at IS NULL
		JOIN memberships m ON m.user_id = d.user_id
		  AND m.target_type = 'group' AND m.target_id = w.group_id AND m.left_at IS NULL
		WHERE w.group_id = $1 AND w.recipient <> $2 AND w.gk_version = ANY($3)`,
		groupID, requester, versions)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

// IsGroupMemberDevice — принадлежит ли устройство активному участнику группы
// (проверка и для запросившего восстановление, и для получателя обёрток).
func (s *Store) IsGroupMemberDevice(ctx context.Context, groupID, deviceID string) (bool, error) {
	var ok bool
	err := s.pool.QueryRow(ctx, `
		SELECT EXISTS (
		  SELECT 1 FROM devices d
		  JOIN memberships m ON m.user_id = d.user_id
		    AND m.target_type = 'group' AND m.target_id = $1 AND m.left_at IS NULL
		  WHERE d.device_id = $2 AND d.revoked_at IS NULL)`, groupID, deviceID).Scan(&ok)
	return ok, err
}

// RecoveryKey — обёртка GK версии для устройства-получателя, сделанная помощником.
type RecoveryKey struct {
	GKVersion          int32
	SenderEphemeralPub []byte
	Wrapped            []byte
}

// SaveRecoveryKeys кладёт обёртки восстановления в group_wrapped_keys для recipient.
// Существующие не трогаются (ON CONFLICT DO NOTHING) — восстановление идемпотентно.
func (s *Store) SaveRecoveryKeys(ctx context.Context, groupID, recipient string, keys []RecoveryKey) error {
	batch := &pgx.Batch{}
	for _, k := range keys {
		batch.Queue(`
			INSERT INTO group_wrapped_keys (group_id, gk_version, recipient, wrapped, sender_ephemeral_pub)
			VALUES ($1, $2, $3, $4, $5)
			ON CONFLICT (group_id, gk_version, recipient) DO NOTHING`,
			groupID, k.GKVersion, recipient, k.Wrapped, k.SenderEphemeralPub)
	}
	br := s.pool.SendBatch(ctx, batch)
	defer br.Close()
	for range keys {
		if _, err := br.Exec(); err != nil {
			return err
		}
	}
	return nil
}

// CurrentGKVersion — максимальная версия GK группы (0, если ротаций не было).
// Нужна клиенту-админу, чьё новое устройство ещё без обёрток: чтобы ротировать
// строго current+1, а не вслепую с 0 (иначе version_conflict).
func (s *Store) CurrentGKVersion(ctx context.Context, groupID string) (int32, error) {
	var v int32
	err := s.pool.QueryRow(ctx, `
		SELECT COALESCE(MAX(gk_version), 0) FROM group_key_history WHERE group_id = $1`, groupID).Scan(&v)
	return v, err
}

// ListGroupKeysForDevice — версии > sinceVersion, для которых у устройства есть обёртка
// (GET /groups/{id}/keys?since_version=). Исключённый участник новых версий не увидит.
func (s *Store) ListGroupKeysForDevice(ctx context.Context, groupID, deviceID string, sinceVersion int32) ([]DeviceGroupKey, error) {
	// COALESCE: обёртка восстановления несёт свой эфемерал (0008); обычная — из истории.
	rows, err := s.pool.Query(ctx, `
		SELECT h.gk_version, COALESCE(w.sender_ephemeral_pub, h.sender_ephemeral_pub), w.wrapped
		FROM group_key_history h
		JOIN group_wrapped_keys w
		  ON w.group_id = h.group_id AND w.gk_version = h.gk_version AND w.recipient = $2
		WHERE h.group_id = $1 AND h.gk_version > $3
		ORDER BY h.gk_version`, groupID, deviceID, sinceVersion)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []DeviceGroupKey
	for rows.Next() {
		var k DeviceGroupKey
		if err := rows.Scan(&k.GKVersion, &k.SenderEphemeralPub, &k.Wrapped); err != nil {
			return nil, err
		}
		out = append(out, k)
	}
	return out, rows.Err()
}
