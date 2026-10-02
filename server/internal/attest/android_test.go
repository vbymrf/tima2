package attest

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/asn1"
	"math/big"
	"testing"
	"time"
)

// Своя цепочка «как у телефона»: корень, листовой с записью аттестации. Настоящие цепочки
// телефонов видно в режиме «записывать» на стенде.
func explicit(tag int, inner []byte) asn1.RawValue {
	return asn1.RawValue{Class: asn1.ClassContextSpecific, Tag: tag, IsCompound: true, Bytes: inner}
}

func makeChain(t *testing.T, challenge []byte) ([][]byte, *ecdsa.PrivateKey) {
	t.Helper()
	rootKey, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	rootTpl := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "корень"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), IsCA: true, BasicConstraintsValid: true,
		KeyUsage: x509.KeyUsageCertSign}
	rootDER, _ := x509.CreateCertificate(rand.Reader, rootTpl, rootTpl, &rootKey.PublicKey, rootKey)
	root, _ := x509.ParseCertificate(rootDER)

	rot, _ := asn1.Marshal(struct {
		Key    []byte
		Locked bool
		State  asn1.Enumerated
		Hash   []byte
	}{make([]byte, 32), true, 0, make([]byte, 32)})
	appInner, _ := asn1.Marshal(struct {
		Packages []struct {
			Name    []byte
			Version int64
		} `asn1:"set"`
		Signatures [][]byte `asn1:"set"`
	}{[]struct {
		Name    []byte
		Version int64
	}{{[]byte("io.tima.app.v2"), 118}}, [][]byte{make([]byte, 32)}})
	appOctet, _ := asn1.Marshal(appInner)
	rotEl, _ := asn1.Marshal(explicit(704, rot))
	appEl, _ := asn1.Marshal(explicit(709, appOctet))
	desc, err := asn1.Marshal(struct {
		AttestationVersion       int
		AttestationSecurityLevel asn1.Enumerated
		KeymasterVersion         int
		KeymasterSecurityLevel   asn1.Enumerated
		AttestationChallenge     []byte
		UniqueID                 []byte
		SoftwareEnforced         asn1.RawValue
		TeeEnforced              asn1.RawValue
	}{4, 1, 41, 1, challenge, nil,
		asn1.RawValue{Tag: asn1.TagSequence, IsCompound: true, Bytes: appEl},
		asn1.RawValue{Tag: asn1.TagSequence, IsCompound: true, Bytes: rotEl}})
	if err != nil {
		t.Fatal(err)
	}
	leafKey, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	leafTpl := &x509.Certificate{SerialNumber: big.NewInt(2), Subject: pkix.Name{CommonName: "ключ"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		ExtraExtensions: []pkix.Extension{{Id: oidKeyDescription, Value: desc}}}
	leafDER, _ := x509.CreateCertificate(rand.Reader, leafTpl, root, &leafKey.PublicKey, rootKey)
	return [][]byte{leafDER, rootDER}, leafKey
}

func TestVerifyAndroidReadsTheRecord(t *testing.T) {
	challenge := []byte("вызов сервера")
	chain, key := makeChain(t, challenge)
	signed := []byte("tima.device.v1|enc|sig")
	d := sha256.Sum256(signed)
	sig, _ := ecdsa.SignASN1(rand.Reader, key, d[:])

	r := VerifyAndroid(chain, challenge, signed, sig)
	if !r.Ok() {
		t.Fatalf("годная аттестация не прошла: %+v", r)
	}
	if r.SecurityLevel != "tee" || r.VerifiedBoot != "verified" || !r.DeviceLocked || r.PackageName != "io.tima.app.v2" {
		t.Fatalf("запись разобрана не так: %+v", r)
	}
	if len(r.RootSPKI) != 64 {
		t.Fatalf("отпечаток корня: %q", r.RootSPKI)
	}
	// Чужой вызов — повтор старой аттестации.
	if VerifyAndroid(chain, []byte("старый вызов"), signed, sig).ChallengeOK {
		t.Fatal("чужой вызов принят")
	}
	// Подпись не над теми ключами.
	if VerifyAndroid(chain, challenge, []byte("другие ключи"), sig).SignatureOK {
		t.Fatal("подпись над другими ключами принята")
	}
}
