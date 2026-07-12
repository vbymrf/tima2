// Package ratelimit — лимиты частоты через Redis (api-overview.md: rate limiting;
// auth-контур: защита от перебора SMS-кодов и спама отправкой SMS).
// Счётчик с фиксированным окном: INCR + EXPIRE — просто и достаточно для auth;
// скользящее окно/токен-бакет — если появятся более чувствительные лимиты.
package ratelimit

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
)

type Limiter struct {
	rdb *redis.Client
	// Prefix добавляется ко всем ключам (изоляция интеграционных тестов);
	// в проде пустой.
	Prefix string
}

func New(ctx context.Context, redisURL string) (*Limiter, error) {
	opts, err := redis.ParseURL(redisURL)
	if err != nil {
		return nil, fmt.Errorf("REDIS_URL: %w", err)
	}
	rdb := redis.NewClient(opts)
	if err := rdb.Ping(ctx).Err(); err != nil {
		rdb.Close()
		return nil, fmt.Errorf("ping Redis: %w", err)
	}
	return &Limiter{rdb: rdb}, nil
}

func (l *Limiter) Close() error { return l.rdb.Close() }

// Allow регистрирует попытку по ключу и говорит, укладывается ли она в
// limit за window. При отказе retryAfter — сколько ждать до сброса окна.
func (l *Limiter) Allow(ctx context.Context, key string, limit int64, window time.Duration) (allowed bool, retryAfter time.Duration, err error) {
	full := "rl:" + l.Prefix + key
	n, err := l.rdb.Incr(ctx, full).Result()
	if err != nil {
		return false, 0, err
	}
	if n == 1 {
		if err := l.rdb.Expire(ctx, full, window).Err(); err != nil {
			return false, 0, err
		}
	}
	if n <= limit {
		return true, 0, nil
	}
	ttl, err := l.rdb.TTL(ctx, full).Result()
	if err != nil || ttl < 0 {
		ttl = window
	}
	return false, ttl, nil
}
