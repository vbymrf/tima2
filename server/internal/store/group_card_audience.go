// Кому открыта карточка группы (ADR-0018 п. 2–3). Личная группа не ищется: о ней
// узнают по цепочке знакомств, и список получателей присылает клиент — адресная книга
// живёт только у него (решение А3).
package store

import (
	"context"
)

// SetGroupCardAudience заменяет список получателей карточки целиком.
//
// Именно заменяет, а не дополняет: клиент присылает свою книгу как есть, а помнить её
// прошлое состояние сервер не может — графа знакомств у него нет. Удалённый из книги
// перестаёт видеть карточку в тот же миг, и это главное, ради чего замена, а не дельта.
//
// Пустой список — законное значение: «убрал группу со страницы», карточку больше не
// видит никто.
func (s *Store) SetGroupCardAudience(ctx context.Context, groupID string, userIDs []string) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	if _, err := tx.Exec(ctx, `DELETE FROM group_card_audience WHERE group_id = $1`, groupID); err != nil {
		return err
	}
	for _, uid := range userIDs {
		// Незнакомый сервер user_id — не ошибка запроса, а обычное дело: в книге есть
		// номера, которых в TIMA нет. Внешний ключ такую строку отвергнет, поэтому
		// вставка идёт по одной и молча пропускает тех, кого нет.
		if _, err := tx.Exec(ctx, `
			INSERT INTO group_card_audience (group_id, user_id)
			SELECT $1, $2 WHERE EXISTS (SELECT 1 FROM users WHERE user_id = $2)
			ON CONFLICT (group_id, user_id) DO UPDATE SET updated_at = now()`,
			groupID, uid); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

// GroupCardOpenTo — открыта ли карточка этому человеку.
//
// Отвечает только про аудиторию. Публичная группа видна и без неё — это решает
// вызывающий: право читать карточку публичной группы не в списке получателей, а в самом
// её виде.
func (s *Store) GroupCardOpenTo(ctx context.Context, groupID, userID string) (bool, error) {
	var exists bool
	err := s.pool.QueryRow(ctx,
		`SELECT EXISTS (SELECT 1 FROM group_card_audience WHERE group_id = $1 AND user_id = $2)`,
		groupID, userID).Scan(&exists)
	return exists, err
}

// CardsFor — карточки, открытые этому человеку и НЕ ставшие его группами.
//
// Вкладка «Друзья» окна 2 показывает именно их: то, что положили себе контакты. Свои
// группы туда не попадают — они в «Каталоге», и человек не должен видеть одну и ту же
// группу дважды в разных списках.
func (s *Store) CardsFor(ctx context.Context, userID string) ([]GroupCardRow, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT g.group_id, g.kind, g.title, COALESCE(g.description, '')
		  FROM group_card_audience a
		  JOIN groups g ON g.group_id = a.group_id AND g.deleted_at IS NULL
		 WHERE a.user_id = $1
		   AND NOT EXISTS (
		       SELECT 1 FROM memberships m
		        WHERE m.target_type = 'group' AND m.target_id = g.group_id
		          AND m.user_id = $1 AND m.left_at IS NULL)
		 ORDER BY a.updated_at DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []GroupCardRow
	for rows.Next() {
		var c GroupCardRow
		if err := rows.Scan(&c.GroupID, &c.Kind, &c.Title, &c.Description); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// GroupCardRow — строка вкладки «Друзья».
type GroupCardRow struct {
	GroupID     string
	Kind        string
	Title       string
	Description string
}

