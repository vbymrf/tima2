package api

import (
	"encoding/json"
	"strings"
	"testing"
)

// Номер сообщения больше 2⁵³ доезжает до клиента без потери разрядов (стенд 2026-10-02:
// 956072612176218224 уходил как …200, и история давала дубль).
func TestEventFrameKeepsBigNumbersExact(t *testing.T) {
	frame, err := eventFrame([]byte(`{"chat_id":"c","message_id":956072612176218224}`))
	if err != nil {
		t.Fatal(err)
	}
	frame["event"] = "message.new"
	raw, _ := json.Marshal(frame)
	if !strings.Contains(string(raw), `"message_id":956072612176218224`) {
		t.Fatalf("номер исказился: %s", raw)
	}
}
