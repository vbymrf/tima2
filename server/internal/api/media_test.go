package api

// Интеграционный тест Media Service: PostgreSQL + MinIO из dev-compose.
// Пропускается, если MinIO недоступен. Клиентская сторона честная:
// шифрование чанков — тем же конвейером, что MediaCipher (HKDF chunk:i + SecretBox).

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"

	"golang.org/x/crypto/hkdf"
	"golang.org/x/crypto/nacl/secretbox"

	"tima/server/internal/blob"
)

func setupWithBlob(t *testing.T) (*httptest.Server, *Server) {
	t.Helper()
	ts, srv := setup(t) // PostgreSQL обязателен (skip внутри)
	endpoint := os.Getenv("TIMA_TEST_S3_ENDPOINT")
	if endpoint == "" {
		endpoint = "http://localhost:9000"
	}
	// publicEndpoint = endpoint: в тестах presigned URL никто не открывает с другого хоста
	bl, err := blob.New(context.Background(), endpoint, endpoint, "tima-admin", "tima-dev-only", "media-test")
	if err != nil {
		t.Skipf("MinIO недоступен (%v) — подними deploy/docker-compose.dev.yml", err)
	}
	srv.Blob = bl
	return ts, srv
}

func authedJSON(t *testing.T, ts *httptest.Server, method, path, token string, body any, out any) int {
	t.Helper()
	var rd io.Reader
	if body != nil {
		raw, _ := json.Marshal(body)
		rd = bytes.NewReader(raw)
	}
	req, _ := http.NewRequest(method, ts.URL+path, rd)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if out != nil {
		_ = json.NewDecoder(resp.Body).Decode(out)
	}
	return resp.StatusCode
}

func httpPut(t *testing.T, url string, data []byte) {
	t.Helper()
	req, _ := http.NewRequest("PUT", url, bytes.NewReader(data))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("PUT в MinIO: %d", resp.StatusCode)
	}
}

func httpGet(t *testing.T, url string) []byte {
	t.Helper()
	resp, err := http.Get(url)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET из MinIO: %d", resp.StatusCode)
	}
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func TestMediaEncryptedSingle(t *testing.T) {
	ts, _ := setupWithBlob(t)
	user := registerDevice(t, ts, "+79991110001")

	// «Голосовое»: клиент шифрует SecretBox(media_key) и льёт ciphertext
	voice := make([]byte, 48*1024)
	if _, err := rand.Read(voice); err != nil {
		t.Fatal(err)
	}
	var mediaKey [32]byte
	var nonce [24]byte
	rand.Read(mediaKey[:])
	rand.Read(nonce[:])
	ciphertext := secretbox.Seal(nonce[:], voice, &nonce, &mediaKey)

	var initResp struct {
		MediaID    string   `json:"media_id"`
		UploadURLs []string `json:"upload_urls"`
	}
	code := authedJSON(t, ts, "POST", "/api/v1/media/init", user.token, map[string]any{
		"size_bytes": len(ciphertext), "mime": "audio/ogg", "is_encrypted": true,
	}, &initResp)
	if code != http.StatusCreated || len(initResp.UploadURLs) != 1 {
		t.Fatalf("media/init: %d, urls=%d", code, len(initResp.UploadURLs))
	}

	// complete до заливки → 409
	if code := authedJSON(t, ts, "POST", "/api/v1/media/complete", user.token,
		map[string]string{"media_id": initResp.MediaID}, nil); code != http.StatusConflict {
		t.Fatalf("complete до заливки: ожидался 409, получен %d", code)
	}

	httpPut(t, initResp.UploadURLs[0], ciphertext)

	var completeResp struct {
		SizeBytes int64 `json:"size_bytes"`
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/media/complete", user.token,
		map[string]string{"media_id": initResp.MediaID}, &completeResp); code != http.StatusOK {
		t.Fatalf("media/complete: %d", code)
	}
	if completeResp.SizeBytes != int64(len(ciphertext)) {
		t.Fatalf("размер: %d != %d", completeResp.SizeBytes, len(ciphertext))
	}

	// Скачивание: presigned GET → ciphertext → расшифровка локально
	var urlResp struct {
		URLs []string `json:"urls"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/media/"+initResp.MediaID+"/url", user.token, nil, &urlResp); code != http.StatusOK {
		t.Fatalf("media url: %d", code)
	}
	downloaded := httpGet(t, urlResp.URLs[0])
	var dnonce [24]byte
	copy(dnonce[:], downloaded[:24])
	opened, ok := secretbox.Open(nil, downloaded[24:], &dnonce, &mediaKey)
	if !ok || !bytes.Equal(opened, voice) {
		t.Fatal("скачанное медиа не расшифровалось в исходник")
	}
}

func TestMediaChunkedAndCAS(t *testing.T) {
	ts, _ := setupWithBlob(t)
	user := registerDevice(t, ts, "+79991110002")

	// ── Chunked: 3 чанка, ключи chunk_key[i] = HKDF(media_key, "chunk:i") ──
	var mediaKey [32]byte
	rand.Read(mediaKey[:])
	chunkKeyOf := func(i int) *[32]byte {
		var k [32]byte
		r := hkdf.New(sha256.New, mediaKey[:], nil, []byte("chunk:"+string(rune('0'+i))))
		io.ReadFull(r, k[:])
		return &k
	}
	plainChunks := make([][]byte, 3)
	cipherChunks := make([][]byte, 3)
	for i := range plainChunks {
		plainChunks[i] = bytes.Repeat([]byte{byte(0x10 + i)}, 8*1024)
		var nonce [24]byte
		rand.Read(nonce[:])
		cipherChunks[i] = secretbox.Seal(nonce[:], plainChunks[i], &nonce, chunkKeyOf(i))
	}

	var initResp struct {
		MediaID    string   `json:"media_id"`
		UploadURLs []string `json:"upload_urls"`
	}
	total := 0
	for _, c := range cipherChunks {
		total += len(c)
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/media/init", user.token, map[string]any{
		"size_bytes": total, "mime": "video/mp4", "is_encrypted": true, "chunk_count": 3,
	}, &initResp); code != http.StatusCreated || len(initResp.UploadURLs) != 3 {
		t.Fatalf("chunked init: %d, urls=%d", code, len(initResp.UploadURLs))
	}
	for i, u := range initResp.UploadURLs {
		httpPut(t, u, cipherChunks[i])
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/media/complete", user.token,
		map[string]string{"media_id": initResp.MediaID}, nil); code != http.StatusOK {
		t.Fatalf("chunked complete: %d", code)
	}
	var urlResp struct {
		URLs []string `json:"urls"`
	}
	authedJSON(t, ts, "GET", "/api/v1/media/"+initResp.MediaID+"/url", user.token, nil, &urlResp)
	if len(urlResp.URLs) != 3 {
		t.Fatalf("ожидалось 3 download URL, получено %d", len(urlResp.URLs))
	}
	for i, u := range urlResp.URLs {
		got := httpGet(t, u)
		var nonce [24]byte
		copy(nonce[:], got[:24])
		opened, ok := secretbox.Open(nil, got[24:], &nonce, chunkKeyOf(i))
		if !ok || !bytes.Equal(opened, plainChunks[i]) {
			t.Fatalf("чанк %d не расшифровался", i)
		}
	}

	// ── CAS (публичное): повторный init того же файла → dedup без заливки ──
	public := []byte("публичный мем TIMA")
	hash := sha256.Sum256(public)
	hashB64 := base64.RawURLEncoding.EncodeToString(hash[:])

	var pubInit struct {
		MediaID    string   `json:"media_id"`
		UploadURLs []string `json:"upload_urls"`
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/media/init", user.token, map[string]any{
		"size_bytes": len(public), "mime": "image/png", "is_encrypted": false, "content_hash": hashB64,
	}, &pubInit); code != http.StatusCreated {
		t.Fatalf("public init: %d", code)
	}
	httpPut(t, pubInit.UploadURLs[0], public)
	authedJSON(t, ts, "POST", "/api/v1/media/complete", user.token, map[string]string{"media_id": pubInit.MediaID}, nil)

	var dedup struct {
		Dedup   bool   `json:"dedup"`
		MediaID string `json:"media_id"`
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/media/init", user.token, map[string]any{
		"size_bytes": len(public), "mime": "image/png", "is_encrypted": false, "content_hash": hashB64,
	}, &dedup); code != http.StatusOK || !dedup.Dedup || dedup.MediaID != pubInit.MediaID {
		t.Fatalf("CAS: ожидался dedup на %s, получено %+v (код %d)", pubInit.MediaID, dedup, code)
	}

	// ── CAS для приватного запрещён контрактом ──
	if code := authedJSON(t, ts, "POST", "/api/v1/media/init", user.token, map[string]any{
		"size_bytes": 100, "mime": "image/png", "is_encrypted": true, "content_hash": hashB64,
	}, nil); code != http.StatusBadRequest {
		t.Fatalf("CAS для приватного: ожидался 400, получен %d", code)
	}
}
