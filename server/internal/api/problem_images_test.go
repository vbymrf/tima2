package api

import (
	"bytes"
	"testing"
)

// Снимки к отчёту: что берём и что отбрасываем (ПЛАН-(В)-ВИДЕО.md В6).
func TestAcceptImages(t *testing.T) {
	jpeg := problemImage{Mime: "image/jpeg", Data: []byte{0xFF, 0xD8, 0xFF}}

	got := acceptImages([]problemImage{jpeg, jpeg, jpeg, jpeg})
	if len(got) != maxProblemImages {
		t.Fatalf("больше трёх — лишние отбрасываются: взято %d", len(got))
	}

	big := problemImage{Mime: "image/jpeg", Data: bytes.Repeat([]byte{1}, maxProblemImageBytes+1)}
	odd := problemImage{Mime: "application/pdf", Data: []byte{1}}
	empty := problemImage{Mime: "image/png"}
	got = acceptImages([]problemImage{big, odd, empty, jpeg})
	if len(got) != 1 || got[0].Mime != "image/jpeg" {
		t.Fatalf("негодные снимки не отброшены: %+v", got)
	}

	if got := acceptImages(nil); len(got) != 0 {
		t.Fatalf("без снимков — пусто: %+v", got)
	}
}
