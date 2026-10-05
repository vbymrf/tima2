// Package attest — разбор аттестации ключа Android (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ДУ8, закладка Р20–Р23).
//
// Телефон заводит в защищённой части ключ P-256 с аттестацией и подписывает им открытые ключи
// своего устройства. Здесь — проверка цепочки сертификатов, вызова сервера в ней, подписи и
// разбор записи аттестации (уровень защиты, состояние загрузки, приложение). Решения «пускать
// или нет» тут нет — это делает режим сервера; сейчас он только записывает итог.
//
// Корни Google здесь не зашиты: в режиме «записывать» сохраняется отпечаток корня цепочки, и
// список разрешённых корней задаётся настройкой, когда дойдёт до «требовать».
package attest

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/sha256"
	"encoding/asn1"
	"encoding/hex"
	"errors"
	"fmt"
)

// oidKeyDescription — расширение записи аттестации ключа Android.
var oidKeyDescription = asn1.ObjectIdentifier{1, 3, 6, 1, 4, 1, 11129, 2, 1, 17}

// Result — что удалось узнать из аттестации.
type Result struct {
	ChainOK         bool   // каждый сертификат подписан следующим, корень самоподписан
	ChallengeOK     bool   // в записи — вызов сервера
	SignatureOK     bool   // ключ аттестации подписал ключи устройства
	RootSPKI        string // sha256 открытого ключа корня, hex — для списка разрешённых корней
	SecurityLevel   string // software | tee | strongbox
	VerifiedBoot    string // verified | self-signed | unverified | failed | ""
	DeviceLocked    bool
	PackageName     string
	SignatureDigest string // sha256 подписи APK, hex
	Problem         string // первая найденная беда, словами
}

// Ok — всё сошлось, кроме решения о корне и состоянии загрузки.
func (r Result) Ok() bool { return r.ChainOK && r.ChallengeOK && r.SignatureOK }

// VerifyAndroid разбирает цепочку (листовой первым), сверяет вызов и подпись ключом
// аттестации над signed.
func VerifyAndroid(chainDER [][]byte, challenge, signed, signature []byte) Result {
	var r Result
	if len(chainDER) < 2 {
		r.Problem = "цепочка короче двух сертификатов"
		return r
	}
	certs := make([]*cert, 0, len(chainDER))
	for i, der := range chainDER {
		c, err := parseLenient(der)
		if err != nil {
			r.Problem = fmt.Sprintf("сертификат %d не разобран: %v", i, err)
			return r
		}
		certs = append(certs, c)
	}
	r.ChainOK = true
	for i := 0; i < len(certs)-1; i++ {
		if err := certs[i].checkSignedBy(certs[i+1]); err != nil {
			r.ChainOK = false
			r.Problem = fmt.Sprintf("сертификат %d не подписан следующим: %v", i, err)
			break
		}
	}
	root := certs[len(certs)-1]
	if err := root.checkSignedBy(root); err != nil && r.ChainOK {
		r.ChainOK = false
		r.Problem = "корень не самоподписан"
	}
	sum := sha256.Sum256(root.spki)
	r.RootSPKI = hex.EncodeToString(sum[:])

	leaf := certs[0]
	if pub, ok := leaf.publicKey.(*ecdsa.PublicKey); ok {
		digest := sha256.Sum256(signed)
		r.SignatureOK = ecdsa.VerifyASN1(pub, digest[:], signature)
	}
	if !r.SignatureOK && r.Problem == "" {
		r.Problem = "ключ аттестации не подписал ключи устройства"
	}

	var ext []byte
	for _, e := range leaf.extensions {
		if e.Id.Equal(oidKeyDescription) {
			ext = e.Value
		}
	}
	if ext == nil {
		if r.Problem == "" {
			r.Problem = "в сертификате нет записи аттестации"
		}
		return r
	}
	if err := parseKeyDescription(ext, challenge, &r); err != nil && r.Problem == "" {
		r.Problem = "запись аттестации не разобрана: " + err.Error()
	}
	return r
}

// keyDescription — начало записи: версии, уровни, вызов; списки разбираются отдельно.
type keyDescription struct {
	AttestationVersion       int
	AttestationSecurityLevel asn1.Enumerated
	KeymasterVersion         int
	KeymasterSecurityLevel   asn1.Enumerated
	AttestationChallenge     []byte
	UniqueID                 []byte
	SoftwareEnforced         asn1.RawValue
	TeeEnforced              asn1.RawValue
}

func parseKeyDescription(der, challenge []byte, r *Result) error {
	var kd keyDescription
	if _, err := asn1.Unmarshal(der, &kd); err != nil {
		return err
	}
	r.ChallengeOK = bytes.Equal(kd.AttestationChallenge, challenge)
	if !r.ChallengeOK && r.Problem == "" {
		r.Problem = "вызов в записи не тот"
	}
	r.SecurityLevel = map[asn1.Enumerated]string{0: "software", 1: "tee", 2: "strongbox"}[kd.AttestationSecurityLevel]
	for _, list := range []asn1.RawValue{kd.TeeEnforced, kd.SoftwareEnforced} {
		walkAuthorizations(list.Bytes, r)
	}
	return nil
}

// walkAuthorizations — поля списка полномочий: [704] корень доверия, [709] приложение.
func walkAuthorizations(b []byte, r *Result) {
	for len(b) > 0 {
		var el asn1.RawValue
		rest, err := asn1.Unmarshal(b, &el)
		if err != nil {
			return
		}
		b = rest
		switch el.Tag {
		case 704: // rootOfTrust
			var rot struct {
				VerifiedBootKey   []byte
				DeviceLocked      bool
				VerifiedBootState asn1.Enumerated
				Rest              asn1.RawContent `asn1:"optional"`
			}
			if _, err := asn1.Unmarshal(el.Bytes, &rot); err == nil {
				r.DeviceLocked = rot.DeviceLocked
				r.VerifiedBoot = map[asn1.Enumerated]string{0: "verified", 1: "self-signed", 2: "unverified", 3: "failed"}[rot.VerifiedBootState]
			}
		case 709: // attestationApplicationId — OCTET STRING с вложенной записью
			var raw []byte
			if _, err := asn1.Unmarshal(el.Bytes, &raw); err != nil {
				continue
			}
			var app struct {
				Packages []struct {
					Name    []byte
					Version int64
				} `asn1:"set"`
				Signatures [][]byte `asn1:"set"`
			}
			if _, err := asn1.Unmarshal(raw, &app); err == nil {
				if len(app.Packages) > 0 {
					r.PackageName = string(app.Packages[0].Name)
				}
				if len(app.Signatures) > 0 {
					r.SignatureDigest = hex.EncodeToString(app.Signatures[0])
				}
			}
		}
	}
}

// ErrNoChain — аттестации не прислали.
var ErrNoChain = errors.New("аттестации нет")
