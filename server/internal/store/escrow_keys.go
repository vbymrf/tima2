package store

// Реестр ключей escrow (миграция 0020). Здесь только публичные части и сроки:
// приватный материал живёт исключительно в анклаве.

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// EscrowKey — запись реестра. Идентификатор совпадает с escrow_key_version в блобе.
type EscrowKey struct {
	ID        uint32    `json:"id"`
	Region    string    `json:"region"`
	Epoch     string    `json:"epoch"`
	ChatID    string    `json:"chat_id"`
	PublicKey []byte    `json:"-"`
	ValidFrom time.Time `json:"valid_from"`
	ValidTo   time.Time `json:"valid_to"`
	DestroyAt time.Time `json:"destroy_at"`
}

var ErrEscrowKeyUnknown = errors.New("ключ escrow не найден")

// FindEscrowKey — ключ области из кэша; ErrEscrowKeyUnknown, если не запрашивался.
func (s *Store) FindEscrowKey(ctx context.Context, region, epoch, chatID string) (EscrowKey, error) {
	var k EscrowKey
	err := s.pool.QueryRow(ctx, `
		SELECT id, region, epoch, chat_id, public_key, valid_from, valid_to, destroy_at
		FROM escrow_keys WHERE region = $1 AND epoch = $2 AND chat_id = $3`,
		region, epoch, chatID).Scan(&k.ID, &k.Region, &k.Epoch, &k.ChatID,
		&k.PublicKey, &k.ValidFrom, &k.ValidTo, &k.DestroyAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return EscrowKey{}, ErrEscrowKeyUnknown
	}
	return k, err
}

// SaveEscrowKey кладёт в кэш публичную часть, выданную анклавом.
//
// Конфликт по области разрешается ничегонеделанием: анклав для одной области
// выдаёт один и тот же ключ, а если запись уже есть — она и верна. Перезаписывать
// опаснее: гонка двух запросов подменила бы идентификатор, на который уже
// сослались отправленные блобы.
func (s *Store) SaveEscrowKey(ctx context.Context, k EscrowKey) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO escrow_keys (id, region, epoch, chat_id, public_key, valid_from, valid_to, destroy_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
		ON CONFLICT (region, epoch, chat_id) DO NOTHING`,
		k.ID, k.Region, k.Epoch, k.ChatID, k.PublicKey, k.ValidFrom, k.ValidTo, k.DestroyAt)
	if err != nil && isUniqueViolation(err) {
		// Конфликт по первичному ключу, а не по области: анклав выдал идентификатор,
		// который в реестре уже занят другой областью. Так бывает, если состояние
		// анклава потеряно и нумерация началась заново.
		//
		// Молча перезаписывать НЕЛЬЗЯ: на прежний идентификатор уже ссылаются
		// отправленные блобы, и подмена означала бы, что их расшифруют не тем
		// ключом. Падаем громко — это расхождение чинится руками.
		return fmt.Errorf("реестр escrow и анклав разошлись: идентификатор %d уже занят другой областью (%w)", k.ID, err)
	}
	return err
}

func isUniqueViolation(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == "23505"
}

// ExpiredEscrowKeys — ключи, чей срок вышел: их содержимое уже никем не
// расшифровывается, и этап Р4 вправе физически стирать связанные сообщения.
func (s *Store) ExpiredEscrowKeys(ctx context.Context, now time.Time, limit int) ([]uint32, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT id FROM escrow_keys WHERE destroy_at <= $1 ORDER BY destroy_at LIMIT $2`, now, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []uint32
	for rows.Next() {
		var id uint32
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}
