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
	// CommunityID — сообщество, с которым группа связана. Пусто — группа отдельная.
	// Нужен клиенту ровно для одного: не предлагать вносить то, что уже внесено.
	CommunityID string
}

type Member struct {
	UserID      string
	Role        string
	JoinedAt    time.Time
	BannedUntil *time.Time
	// Номер оттенка полосы 0…99 (миграция 0053); nil — не выбирал.
	Hue *int16
}

var (
	ErrGroupNotFound = errors.New("группа не найдена")
	ErrNotMember     = errors.New("пользователь не активный участник группы")
	// ErrHueTaken — оттенок уже у другого участника, а участников меньше 80 % оттенков.
	ErrHueTaken = errors.New("этот цвет уже у другого участника")
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
	// Срок участия проверяется здесь же (ADR-0019 §9): просроченный участник перестаёт
	// быть участником в тот же миг, даже если строку ещё не убрала смена эпохи. Иначе
	// между истечением и ближайшей ротацией человек продолжал бы читать группу.
	err := s.pool.QueryRow(ctx, `
		SELECT role FROM memberships
		WHERE target_type = 'group' AND target_id = $1 AND user_id = $2 AND left_at IS NULL
		  AND (until_epoch IS NULL OR until_epoch >= to_char(now(), 'YYYY-MM'))`,
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
		SELECT user_id, role, joined_at, banned_until, hue FROM memberships
		WHERE target_type = 'group' AND target_id = $1 AND left_at IS NULL
		ORDER BY joined_at`, groupID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Member
	for rows.Next() {
		var m Member
		if err := rows.Scan(&m.UserID, &m.Role, &m.JoinedAt, &m.BannedUntil, &m.Hue); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// HueSharedLimit — до скольких активных участников совпадение цветов запрещено:
// 80 % от 99 доступных оттенков (№0 клиент не выдаёт — он близок к салатовому владельца).
const HueSharedLimit = 79

// SetMemberHue ставит участнику номер оттенка полосы (nil — сбросить на автоматический).
//
// Проверка «занят ли» и запись — одним UPDATE: две проверки-потом-записи с двух телефонов
// прошли бы обе. Занят и участников не больше HueSharedLimit — ErrHueTaken; не участник —
// ErrNotMember.
func (s *Store) SetMemberHue(ctx context.Context, groupID, userID string, hue *int16) error {
	if hue != nil && (*hue < 0 || *hue > 99) {
		return errors.New("номер оттенка — от 0 до 99")
	}
	ct, err := s.pool.Exec(ctx, `
		UPDATE memberships SET hue = $3
		WHERE target_type = 'group' AND target_id = $1 AND user_id = $2 AND left_at IS NULL
		  AND ($3::smallint IS NULL
		       OR NOT EXISTS (SELECT 1 FROM memberships o
		                      WHERE o.target_type = 'group' AND o.target_id = $1
		                        AND o.left_at IS NULL AND o.user_id <> $2 AND o.hue = $3)
		       OR (SELECT count(*) FROM memberships c
		           WHERE c.target_type = 'group' AND c.target_id = $1 AND c.left_at IS NULL) > $4)`,
		groupID, userID, hue, HueSharedLimit)
	if err != nil {
		return err
	}
	if ct.RowsAffected() == 0 {
		// Не обновилось: либо не участник, либо цвет занят. Различаем вторым запросом —
		// он нужен только на отказе, то есть редко.
		if _, roleErr := s.GroupRole(ctx, groupID, userID); roleErr != nil {
			return ErrNotMember
		}
		return ErrHueTaken
	}
	return nil
}

// SenderStamp — что приложить к событию о сообщении, чтобы получатели узнали о смене
// профиля и цвета без запроса: счётчик профиля отправителя и его оттенок в этой группе.
// Один запрос по первичным ключам на отправку, а не на каждую доставку.
func (s *Store) SenderStamp(ctx context.Context, groupID, userID string) (rev int32, hue *int16, err error) {
	err = s.pool.QueryRow(ctx, `
		SELECT p.profile_rev,
		       (SELECT m.hue FROM memberships m
		         WHERE m.target_type = 'group' AND m.target_id = $1 AND m.user_id = $2 AND m.left_at IS NULL)
		FROM users u JOIN persons p ON p.person_id = u.person_id
		WHERE u.user_id = $2`, groupID, userID).Scan(&rev, &hue)
	return rev, hue, err
}

// MyGroup — группа глазами участника (список на главном экране клиента).
type MyGroup struct {
	Group
	MyRole string
}

// ListGroupsForUser — активные членства пользователя в неудалённых группах.
func (s *Store) ListGroupsForUser(ctx context.Context, userID string) ([]MyGroup, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT g.group_id, g.kind, g.title, COALESCE(g.description, ''), g.owner_id,
		       COALESCE(g.slow_mode_sec, 0), g.premoderation, g.threads_only,
		       COALESCE(g.community_id::text, ''), m.role
		FROM memberships m
		JOIN groups g ON g.group_id = m.target_id AND g.deleted_at IS NULL
		WHERE m.target_type = 'group' AND m.user_id = $1 AND m.left_at IS NULL
		  -- Служебная группа аккаунта (0051) — не переписка: её ключом шифруется копия
		  -- книги, и в списке групп она была бы безымянной строкой, которую нельзя открыть.
		  AND NOT EXISTS (SELECT 1 FROM users u WHERE u.store_group_id = g.group_id)
		ORDER BY m.joined_at DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []MyGroup
	for rows.Next() {
		var g MyGroup
		if err := rows.Scan(&g.GroupID, &g.Kind, &g.Title, &g.Description, &g.OwnerID,
			&g.SlowModeSec, &g.Premoderation, &g.ThreadsOnly, &g.CommunityID, &g.MyRole); err != nil {
			return nil, err
		}
		out = append(out, g)
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
