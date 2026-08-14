// Список устройств аккаунта и отзыв (key-lifecycle.md §5). Появилось вместе с
// привязкой по QR: подключить устройство стало легко, значит и отключить его
// должно быть можно — иначе привязка получается односторонней.
package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// ErrDeviceNotFound — устройство не принадлежит этому аккаунту или уже отозвано.
var ErrDeviceNotFound = errors.New("устройство не найдено среди активных устройств аккаунта")

// PlatformPhone — платформы, которым разрешено подтверждать привязку по QR
// (key-lifecycle.md §2: якорь доверия — телефон).
var PlatformPhone = map[string]bool{"android": true, "ios": true}

// SetDevicePlatform — устройство объявляет свою платформу. Идемпотентно.
//
// Отдельная ручка нужна для устройств, зарегистрированных до миграции 0029: у них
// платформа пустая, и без самообъявления они навсегда потеряли бы возможность
// подтверждать привязку. Клиент вызывает это при запуске, поэтому существующие
// установки чинятся сами.
//
// Значение приходит от самого устройства и до аттестации непроверяемо — см.
// оговорку в миграции 0029.
func (s *Store) SetDevicePlatform(ctx context.Context, deviceID, platform string) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE devices SET platform = $2 WHERE device_id = $1 AND revoked_at IS NULL`,
		deviceID, platform)
	return err
}

// DevicePlatform — платформа устройства ('' — не объявлена).
func (s *Store) DevicePlatform(ctx context.Context, deviceID string) (string, error) {
	var platform string
	err := s.pool.QueryRow(ctx, `
		SELECT platform FROM devices WHERE device_id = $1 AND revoked_at IS NULL`, deviceID).Scan(&platform)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrDeviceNotFound
	}
	return platform, err
}

// IsActiveDevice reports whether the device JWT still represents an active
// device of its claimed user. It is checked on every authenticated HTTP route,
// so revoking a device invalidates already-issued access tokens immediately.
func (s *Store) IsActiveDevice(ctx context.Context, userID, deviceID string) (bool, error) {
	var active bool
	err := s.pool.QueryRow(ctx, `
		SELECT EXISTS(
			SELECT 1 FROM devices
			WHERE user_id = $1 AND device_id = $2 AND revoked_at IS NULL
		)`, userID, deviceID).Scan(&active)
	return active, err
}

// UserDevice — строка списка устройств для настроек.
type UserDevice struct {
	DeviceID  string
	Name      string
	CreatedAt time.Time
}

// ListUserDevices — активные устройства аккаунта, старые первыми (порядок
// подключения читается естественнее, чем обратный).
func (s *Store) ListUserDevices(ctx context.Context, userID string) ([]UserDevice, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT device_id, name, created_at
		FROM devices WHERE user_id = $1 AND revoked_at IS NULL
		ORDER BY created_at`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]UserDevice, 0, 4)
	for rows.Next() {
		var d UserDevice
		if err := rows.Scan(&d.DeviceID, &d.Name, &d.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// RevokeDevice помечает устройство отозванным. Только своё: чужое устройство
// отозвать нельзя даже зная его device_id — условие по user_id в самом UPDATE,
// а не отдельной проверкой перед ним (иначе между проверкой и записью осталась
// бы щель).
//
// Обёртки ключей не трогаем: отозванное устройство теряет доступ к API и новых
// обёрток не получает, а стирание старых — работа GC по ретеншену. Удалять их
// здесь означало бы уничтожать ключи сообщений, которые могут быть нужны
// ОСТАЛЬНЫМ устройствам аккаунта как источник восстановления.
func (s *Store) RevokeDevice(ctx context.Context, userID, deviceID string) error {
	ct, err := s.pool.Exec(ctx, `
		UPDATE devices SET revoked_at = now()
		WHERE device_id = $1 AND user_id = $2 AND revoked_at IS NULL`, deviceID, userID)
	if err != nil {
		return err
	}
	if ct.RowsAffected() == 0 {
		return ErrDeviceNotFound
	}
	return nil
}

// CountActiveDevices — сколько активных устройств у аккаунта (api не даёт
// отозвать последнее: аккаунт без устройств недоступен никому, включая владельца).
func (s *Store) CountActiveDevices(ctx context.Context, userID string) (int, error) {
	var n int
	err := s.pool.QueryRow(ctx,
		`SELECT COUNT(*) FROM devices WHERE user_id = $1 AND revoked_at IS NULL`, userID).Scan(&n)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, nil
	}
	return n, err
}
