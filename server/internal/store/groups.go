// Group Service: группы (переписка) и подсистема membership (data-model.md §3).
// Активное членство — left_at IS NULL; выход/исключение помечается, строка
// остаётся историей. Бан (banned_until) роли не меняет — запрет писать
// проверит Message Service групп.
package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

type Group struct {
	GroupID       string
	Kind          string // 'private' (E2E, GK) | 'public'
	Title         string
	Description   string
	OwnerID       string
	SlowModeSec   int32
	Premoderation bool
	ThreadsOnly   bool
}

type Member struct {
	UserID      string
	Role        string
	JoinedAt    time.Time
	BannedUntil *time.Time
}

var (
	ErrGroupNotFound = errors.New("группа не найдена")
	ErrNotMember     = errors.New("пользователь не активный участник группы")
	ErrUserUnknown   = errors.New("пользователь не существует")
)

// isBadUUID — мусор вместо UUID в параметре запроса (22P02). Для вызывающих
// неотличим от «не найдено»: сравнение с несуществующим идентификатором.
func isBadUUID(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == "22P02"
}

// CreateGroup создаёт группу и членство владельца (owner) одной транзакцией.
func (s *Store) CreateGroup(ctx context.Context, g Group) (string, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx) //nolint:errcheck — no-op после Commit

	var id string
	if err := tx.QueryRow(ctx, `
		INSERT INTO groups (kind, title, description, owner_id, slow_mode_sec, premoderation, threads_only)
		VALUES ($1,$2,$3,$4,$5,$6,$7)
		RETURNING group_id`,
		g.Kind, g.Title, g.Description, g.OwnerID, g.SlowModeSec, g.Premoderation, g.ThreadsOnly).Scan(&id); err != nil {
		return "", err
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO memberships (target_type, target_id, user_id, role)
		VALUES ('group', $1, $2, 'owner')`, id, g.OwnerID); err != nil {
		return "", err
	}
	return id, tx.Commit(ctx)
}

func (s *Store) GetGroup(ctx context.Context, groupID string) (Group, error) {
	g := Group{GroupID: groupID}
	err := s.pool.QueryRow(ctx, `
		SELECT kind, title, COALESCE(description, ''), owner_id,
		       COALESCE(slow_mode_sec, 0), premoderation, threads_only
		FROM groups WHERE group_id = $1 AND deleted_at IS NULL`, groupID).
		Scan(&g.Kind, &g.Title, &g.Description, &g.OwnerID, &g.SlowModeSec, &g.Premoderation, &g.ThreadsOnly)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return g, ErrGroupNotFound
	}
	return g, err
}

// UpdateGroup перезаписывает изменяемые настройки; PATCH-обработчик собирает
// полную структуру поверх GetGroup. kind и owner_id не меняются.
func (s *Store) UpdateGroup(ctx context.Context, g Group) error {
	ct, err := s.pool.Exec(ctx, `
		UPDATE groups SET title = $2, description = $3, slow_mode_sec = $4,
		       premoderation = $5, threads_only = $6
		WHERE group_id = $1 AND deleted_at IS NULL`,
		g.GroupID, g.Title, g.Description, g.SlowModeSec, g.Premoderation, g.ThreadsOnly)
	if err == nil && ct.RowsAffected() == 0 {
		return ErrGroupNotFound
	}
	return err
}

func (s *Store) SoftDeleteGroup(ctx context.Context, groupID string) error {
	ct, err := s.pool.Exec(ctx,
		`UPDATE groups SET deleted_at = now() WHERE group_id = $1 AND deleted_at IS NULL`, groupID)
	if err == nil && ct.RowsAffected() == 0 {
		return ErrGroupNotFound
	}
	return err
}

// ── Membership ──

// GroupRole — роль активного участника; ErrNotMember, если не состоит или вышел.
func (s *Store) GroupRole(ctx context.Context, groupID, userID string) (string, error) {
	var role string
	err := s.pool.QueryRow(ctx, `
		SELECT role FROM memberships
		WHERE target_type = 'group' AND target_id = $1 AND user_id = $2 AND left_at IS NULL`,
		groupID, userID).Scan(&role)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return "", ErrNotMember
	}
	return role, err
}

// AddGroupMember — добавление/возврат: повторное добавление сбрасывает left_at и бан.
func (s *Store) AddGroupMember(ctx context.Context, groupID, userID, role string) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO memberships (target_type, target_id, user_id, role)
		VALUES ('group', $1, $2, $3)
		ON CONFLICT (target_type, target_id, user_id)
		DO UPDATE SET role = EXCLUDED.role, joined_at = now(), left_at = NULL, banned_until = NULL`,
		groupID, userID, role)
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && (pgErr.Code == "23503" || pgErr.Code == "22P02") { // нет такого user_id
		return ErrUserUnknown
	}
	return err
}

// RemoveGroupMember помечает выход; строка остаётся историей членства.
func (s *Store) RemoveGroupMember(ctx context.Context, groupID, userID string) error {
	ct, err := s.pool.Exec(ctx, `
		UPDATE memberships SET left_at = now()
		WHERE target_type = 'group' AND target_id = $1 AND user_id = $2 AND left_at IS NULL`,
		groupID, userID)
	if err == nil && ct.RowsAffected() == 0 {
		return ErrNotMember
	}
	return err
}

func (s *Store) SetGroupRole(ctx context.Context, groupID, userID, role string) error {
	ct, err := s.pool.Exec(ctx, `
		UPDATE memberships SET role = $3
		WHERE target_type = 'group' AND target_id = $1 AND user_id = $2 AND left_at IS NULL`,
		groupID, userID, role)
	if err == nil && ct.RowsAffected() == 0 {
		return ErrNotMember
	}
	return err
}

// BanGroupMember ставит banned_until = now() + seconds; членство и роль сохраняются.
func (s *Store) BanGroupMember(ctx context.Context, groupID, userID string, seconds int64) error {
	ct, err := s.pool.Exec(ctx, `
		UPDATE memberships SET banned_until = now() + make_interval(secs => $3)
		WHERE target_type = 'group' AND target_id = $1 AND user_id = $2 AND left_at IS NULL`,
		groupID, userID, seconds)
	if err == nil && ct.RowsAffected() == 0 {
		return ErrNotMember
	}
	return err
}

func (s *Store) ListGroupMembers(ctx context.Context, groupID string) ([]Member, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT user_id, role, joined_at, banned_until FROM memberships
		WHERE target_type = 'group' AND target_id = $1 AND left_at IS NULL
		ORDER BY joined_at`, groupID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Member
	for rows.Next() {
		var m Member
		if err := rows.Scan(&m.UserID, &m.Role, &m.JoinedAt, &m.BannedUntil); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// NonMemberDevices — какие из deviceIDs НЕ являются действующими устройствами
// активных участников группы (проверка получателей wrapped_GK при ротации).
// Сравнение по тексту: мусорный идентификатор тоже вернётся как «чужой».
func (s *Store) NonMemberDevices(ctx context.Context, groupID string, deviceIDs []string) ([]string, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT ids.id FROM unnest($2::text[]) AS ids(id)
		WHERE NOT EXISTS (
			SELECT 1 FROM devices d
			JOIN memberships m ON m.target_type = 'group' AND m.target_id = $1
			     AND m.user_id = d.user_id AND m.left_at IS NULL
			WHERE d.device_id::text = ids.id AND d.revoked_at IS NULL)`,
		groupID, deviceIDs)
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
