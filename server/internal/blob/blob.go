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
	mc      *minio.Client // операции backend↔MinIO: внутренний endpoint (minio:9000)
	presign *minio.Client // ТОЛЬКО генерация presigned URL: публичный хост (s3.DOMAIN)
	bucket  string
}

func newMinio(endpoint, accessKey, secretKey string) (*minio.Client, error) {
	u, err := url.Parse(endpoint)
	if err != nil {
		return nil, fmt.Errorf("endpoint %q: %w", endpoint, err)
	}
	return minio.New(u.Host, &minio.Options{
		Creds:  credentials.NewStaticV4(accessKey, secretKey, ""),
		Secure: u.Scheme == "https",
	})
}

// New: [endpoint] — внутренний адрес MinIO для операций (minio:9000); [publicEndpoint]
// — публичный (https://s3.DOMAIN), на который должны указывать presigned URL, чтобы
// клиент их достал. Подпись presigned считается ОФЛАЙН, поэтому publicEndpoint не
// обязан быть доступен из бэкенда. Пусто → presigned на том же endpoint (dev).
func New(ctx context.Context, endpoint, publicEndpoint, accessKey, secretKey, bucket string) (*Client, error) {
	mc, err := newMinio(endpoint, accessKey, secretKey)
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
	presign := mc
	if publicEndpoint != "" && publicEndpoint != endpoint {
		presign, err = newMinio(publicEndpoint, accessKey, secretKey)
		if err != nil {
			return nil, fmt.Errorf("minio presign client: %w", err)
		}
	}
	return &Client{mc: mc, presign: presign, bucket: bucket}, nil
}

func (c *Client) PresignPut(ctx context.Context, key string, ttl time.Duration) (string, error) {
	u, err := c.presign.PresignedPutObject(ctx, c.bucket, key, ttl)
	if err != nil {
		return "", err
	}
	return u.String(), nil
}

func (c *Client) PresignGet(ctx context.Context, key string, ttl time.Duration) (string, error) {
	u, err := c.presign.PresignedGetObject(ctx, c.bucket, key, ttl, nil)
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
