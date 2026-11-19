// Аватар сущности (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ5). Отдельной функцией, а не методом `*Store`: у хранилища бюджет
// методов (architecture_test).
package store

import "context"

// SetEntityAvatar — поставить аватар группе, каналу или сообществу: своё завершённое медиа того, кто ставит; пусто —
// снять. Право решает вызывающий.
func SetEntityAvatar(ctx context.Context, s *Store, kind, id, actorID, mediaID string) error {
	table, key := entityTable(kind)
	if mediaID == "" {
		_, err := s.pool.Exec(ctx, `UPDATE `+table+` SET avatar_media_id = NULL WHERE `+key+` = $1 AND deleted_at IS NULL`, id)
		return err
	}
	tag, err := s.pool.Exec(ctx, `
		UPDATE `+table+` SET avatar_media_id = $2::uuid
		 WHERE `+key+` = $1 AND deleted_at IS NULL
		   AND EXISTS (SELECT 1 FROM media_objects WHERE media_id = $2::uuid AND owner_id = $3 AND status = 'complete')`,
		id, mediaID, actorID)
	if err != nil {
		if isBadUUID(err) {
			return ErrAvatarNotOwned
		}
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrAvatarNotOwned
	}
	return nil
}

// EntityAvatars — аватары сущностей вида: номер → media_id (у кого стоит).
func EntityAvatars(ctx context.Context, s *Store, kind string, ids []string) (map[string]string, error) {
	out := map[string]string{}
	if len(ids) == 0 {
		return out, nil
	}
	table, key := entityTable(kind)
	rows, err := s.pool.Query(ctx, `
		SELECT `+key+`::text, avatar_media_id::text FROM `+table+`
		 WHERE `+key+`::text = ANY($1) AND avatar_media_id IS NOT NULL`, ids)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id, media string
		if err := rows.Scan(&id, &media); err != nil {
			return nil, err
		}
		out[id] = media
	}
	return out, rows.Err()
}
