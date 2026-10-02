package attest

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/sha512"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/asn1"
	"errors"
	"math/big"
)

// Мягкий разбор сертификата. `x509.ParseCertificate` строг к расширениям и отвергает
// сертификаты, в которых производитель записал что-то не по букве стандарта: у Honor
// (Huawei) — «invalid CRL distribution points» (стенд 2026-10-02). Для аттестации нам из
// сертификата нужны четыре вещи: подписываемая часть, алгоритм, подпись, открытый ключ — и
// расширение с записью аттестации. Их и берём, не разбирая остального.

type rawCertificate struct {
	TBS       asn1.RawValue
	Algorithm pkix.AlgorithmIdentifier
	Signature asn1.BitString
}

type rawTBS struct {
	Version    int `asn1:"optional,explicit,default:0,tag:0"`
	Serial     *big.Int
	Algorithm  pkix.AlgorithmIdentifier
	Issuer     asn1.RawValue
	Validity   asn1.RawValue
	Subject    asn1.RawValue
	PublicKey  asn1.RawValue
	IssuerUID  asn1.BitString   `asn1:"optional,tag:1"`
	SubjectUID asn1.BitString   `asn1:"optional,tag:2"`
	Extensions []pkix.Extension `asn1:"optional,explicit,tag:3"`
}

// cert — то, что нужно аттестации.
type cert struct {
	tbs        []byte
	algorithm  asn1.ObjectIdentifier
	signature  []byte
	publicKey  any
	spki       []byte
	extensions []pkix.Extension
}

func parseLenient(der []byte) (*cert, error) {
	var rc rawCertificate
	if _, err := asn1.Unmarshal(der, &rc); err != nil {
		return nil, err
	}
	var t rawTBS
	if _, err := asn1.Unmarshal(rc.TBS.FullBytes, &t); err != nil {
		return nil, err
	}
	pub, err := x509.ParsePKIXPublicKey(t.PublicKey.FullBytes)
	if err != nil {
		return nil, err
	}
	return &cert{
		tbs: rc.TBS.FullBytes, algorithm: rc.Algorithm.Algorithm, signature: rc.Signature.RightAlign(),
		publicKey: pub, spki: t.PublicKey.FullBytes, extensions: t.Extensions,
	}, nil
}

var (
	oidECDSASHA256 = asn1.ObjectIdentifier{1, 2, 840, 10045, 4, 3, 2}
	oidECDSASHA384 = asn1.ObjectIdentifier{1, 2, 840, 10045, 4, 3, 3}
	oidECDSASHA512 = asn1.ObjectIdentifier{1, 2, 840, 10045, 4, 3, 4}
	oidRSASHA256   = asn1.ObjectIdentifier{1, 2, 840, 113549, 1, 1, 11}
	oidRSASHA384   = asn1.ObjectIdentifier{1, 2, 840, 113549, 1, 1, 12}
	oidRSASHA512   = asn1.ObjectIdentifier{1, 2, 840, 113549, 1, 1, 13}
)

// checkSignedBy — подписан ли c ключом parent.
func (c *cert) checkSignedBy(parent *cert) error {
	var h crypto.Hash
	switch {
	case c.algorithm.Equal(oidECDSASHA256), c.algorithm.Equal(oidRSASHA256):
		h = crypto.SHA256
	case c.algorithm.Equal(oidECDSASHA384), c.algorithm.Equal(oidRSASHA384):
		h = crypto.SHA384
	case c.algorithm.Equal(oidECDSASHA512), c.algorithm.Equal(oidRSASHA512):
		h = crypto.SHA512
	default:
		return errors.New("алгоритм подписи сертификата не знаем: " + c.algorithm.String())
	}
	var digest []byte
	switch h {
	case crypto.SHA256:
		d := sha256.Sum256(c.tbs)
		digest = d[:]
	case crypto.SHA384:
		d := sha512.Sum384(c.tbs)
		digest = d[:]
	default:
		d := sha512.Sum512(c.tbs)
		digest = d[:]
	}
	switch pub := parent.publicKey.(type) {
	case *ecdsa.PublicKey:
		if !ecdsa.VerifyASN1(pub, digest, c.signature) {
			return errors.New("подпись ECDSA не сходится")
		}
	case *rsa.PublicKey:
		if err := rsa.VerifyPKCS1v15(pub, h, digest, c.signature); err != nil {
			return err
		}
	default:
		return errors.New("ключ издателя не ECDSA и не RSA")
	}
	return nil
}
