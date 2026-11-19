// Перевязка владения при смене личности (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §6.3, §5 вопрос 1 — ответ «а», 2026-11-19).
// Отдельной функцией, а не методом `*Store`: у хранилища бюджет методов (architecture_test).
package store

import (
	"context"

	"github.com/jackc/pgx/v5"
)

// rebindOwnership — перерегистрация состоялась (новая личность подтверждена, прежняя уходит на удаление): всё, чем
// владела прежняя личность, переходит в **распоряжение** новой. Звено цепочки — «перевязка» (`rebind`) с ключом
// личности ПРЕЖНЕГО звена: право передачи — подпись ключом последнего звена, и передать новая личность не сможет,
// пока не предъявит прежний ключ (секретную фразу). Административная связка распоряжаться даёт, передавать — нет.
//
// Группа: новая личность — участник с ролью владельца, прежняя — участник (уйдёт при удалении личности); её
// заявка в эту группу снимается — решать её некому, владелец здесь она сама. Сообщество: новая личность подписана,
// из админов снята. Канал: из модераторов снята.
func rebindOwnership(ctx context.Context, tx pgx.Tx, oldUserID, newUserID string) error {
	for _, k := range []struct{ kind, table, key string }{
		{"group", "groups", "group_id"},
		{"channel", "channels", "channel_id"},
		{"community", "communities", "community_id"},
	} {
		if _, err := tx.Exec(ctx, `
			INSERT INTO entity_ownership (entity_kind, entity_id, link_no, owner_id, identity_pub, link_kind)
			SELECT $1, t.`+k.key+`,
			       COALESCE((SELECT max(o.link_no) FROM entity_ownership o WHERE o.entity_kind = $1 AND o.entity_id = t.`+k.key+`), 0) + 1,
			       $3,
			       (SELECT o.identity_pub FROM entity_ownership o WHERE o.entity_kind = $1 AND o.entity_id = t.`+k.key+`
			         ORDER BY o.link_no DESC LIMIT 1),
			       'rebind'
			  FROM `+k.table+` t WHERE t.owner_id = $2 AND t.deleted_at IS NULL`, k.kind, oldUserID, newUserID); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE `+k.table+` SET owner_id = $2 WHERE owner_id = $1 AND deleted_at IS NULL`, oldUserID, newUserID); err != nil {
			return err
		}
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO memberships (target_type, target_id, user_id, role)
		SELECT 'group', g.group_id, $1, 'owner' FROM groups g WHERE g.owner_id = $1 AND g.deleted_at IS NULL
		ON CONFLICT (target_type, target_id, user_id) DO UPDATE SET role = 'owner', left_at = NULL`, newUserID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `
		UPDATE memberships m SET role = 'member'
		  FROM groups g
		 WHERE m.target_type = 'group' AND m.target_id = g.group_id AND g.owner_id = $2 AND m.user_id = $1`, oldUserID, newUserID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `
		DELETE FROM identity_group_claims c USING groups g
		 WHERE c.group_id = g.group_id AND g.owner_id = $1 AND c.user_id = $1`, newUserID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO community_subscriptions (community_id, subscriber_id)
		SELECT c.community_id, $1 FROM communities c WHERE c.owner_id = $1 AND c.deleted_at IS NULL
		ON CONFLICT DO NOTHING`, newUserID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `
		DELETE FROM community_admins a USING communities c WHERE a.community_id = c.community_id AND c.owner_id = $1 AND a.user_id = $1`, newUserID); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `
		DELETE FROM channel_moderators m USING channels c WHERE m.channel_id = c.channel_id AND c.owner_id = $1 AND m.user_id = $1`, newUserID)
	return err
}
