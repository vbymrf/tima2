// Package events — доставка realtime-событий устройствам (websocket-events.md).
// Шина — Redis Pub/Sub: канал на устройство; издатель — API-обработчики,
// подписчик — WS-соединение устройства. Офлайн-очередь push (Redis Stream +
// FCM/APNs) — отдельная итерация worker-а.
package events

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/redis/go-redis/v9"
)

type Bus struct {
	rdb *redis.Client
}

func New(ctx context.Context, redisURL string) (*Bus, error) {
	opts, err := redis.ParseURL(redisURL)
	if err != nil {
		return nil, fmt.Errorf("REDIS_URL: %w", err)
	}
	rdb := redis.NewClient(opts)
	if err := rdb.Ping(ctx).Err(); err != nil {
		rdb.Close()
		return nil, fmt.Errorf("ping Redis: %w", err)
	}
	return &Bus{rdb: rdb}, nil
}

func (b *Bus) Close() error { return b.rdb.Close() }

func channel(deviceID string) string { return "tima:dev:" + deviceID }

// Publish шлёт событие устройству. payload сериализуется в JSON-кадр
// (debug-транспорт по websocket-events.md; protobuf-кадры — вместе с клиентом).
// eventID — из персистентного event log (device_events): live-кадр и кадр
// sync.pull одного события несут один и тот же event_id.
func (b *Bus) Publish(ctx context.Context, deviceID, event string, eventID int64, payload map[string]any) error {
	frame := map[string]any{
		"event":    event,
		"event_id": eventID,
	}
	for k, v := range payload {
		frame[k] = v
	}
	raw, err := json.Marshal(frame)
	if err != nil {
		return err
	}
	return b.rdb.Publish(ctx, channel(deviceID), raw).Err()
}

// Subscription — подписка устройства; Frames закрывается при Close/обрыве.
type Subscription struct {
	ps *redis.PubSub
}

func (s *Subscription) Frames() <-chan *redis.Message { return s.ps.Channel() }
func (s *Subscription) Close() error                  { return s.ps.Close() }

// Subscribe возвращает подтверждённую подписку: после возврата Publish не теряется.
func (b *Bus) Subscribe(ctx context.Context, deviceID string) (*Subscription, error) {
	ps := b.rdb.Subscribe(ctx, channel(deviceID))
	if _, err := ps.Receive(ctx); err != nil { // ждём подтверждение SUBSCRIBE
		ps.Close()
		return nil, err
	}
	return &Subscription{ps: ps}, nil
}
