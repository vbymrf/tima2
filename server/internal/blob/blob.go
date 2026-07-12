// Package blob — object storage (MinIO, S3 API). Файлы не проксируются через
// бэкенд (media-storage.md §1): сервис только выдаёт presigned URL и проверяет
// существование объектов; клиент ходит в MinIO напрямую.
package blob

import (
	"context"
	"fmt"
	"net/url"
	"strings"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

type Client struct {
	mc     *minio.Client
	bucket string
}

// New подключается к S3-endpoint (например http://localhost:9000) и гарантирует bucket.
func New(ctx context.Context, endpoint, accessKey, secretKey, bucket string) (*Client, error) {
	u, err := url.Parse(endpoint)
	if err != nil {
		return nil, fmt.Errorf("S3_ENDPOINT: %w", err)
	}
	mc, err := minio.New(u.Host, &minio.Options{
		Creds:  credentials.NewStaticV4(accessKey, secretKey, ""),
		Secure: u.Scheme == "https",
	})
	if err != nil {
		return nil, fmt.Errorf("minio client: %w", err)
	}
	exists, err := mc.BucketExists(ctx, bucket)
	if err != nil {
		return nil, fmt.Errorf("bucket %s: %w", bucket, err)
	}
	if !exists {
		if err := mc.MakeBucket(ctx, bucket, minio.MakeBucketOptions{}); err != nil {
			// гонка с параллельным созданием — не ошибка
			if ok, _ := mc.BucketExists(ctx, bucket); !ok {
				return nil, fmt.Errorf("создание bucket %s: %w", bucket, err)
			}
		}
	}
	return &Client{mc: mc, bucket: bucket}, nil
}

func (c *Client) PresignPut(ctx context.Context, key string, ttl time.Duration) (string, error) {
	u, err := c.mc.PresignedPutObject(ctx, c.bucket, key, ttl)
	if err != nil {
		return "", err
	}
	return u.String(), nil
}

func (c *Client) PresignGet(ctx context.Context, key string, ttl time.Duration) (string, error) {
	u, err := c.mc.PresignedGetObject(ctx, c.bucket, key, ttl, nil)
	if err != nil {
		return "", err
	}
	return u.String(), nil
}

// Size возвращает размер объекта или ErrNotFound.
func (c *Client) Size(ctx context.Context, key string) (int64, error) {
	info, err := c.mc.StatObject(ctx, c.bucket, key, minio.StatObjectOptions{})
	if err != nil {
		var er minio.ErrorResponse
		if minio.ToErrorResponse(err).Code == "NoSuchKey" || strings.Contains(err.Error(), "not exist") {
			_ = er
			return 0, ErrNotFound
		}
		return 0, err
	}
	return info.Size, nil
}

var ErrNotFound = fmt.Errorf("объект не найден в хранилище")
