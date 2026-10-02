package crypto

import "testing"

// Те же строки проверяет `DeviceTrustTest` в messenger-crypto: расхождение одной буквы —
// подпись клиента не сходится на сервере, и сказать об этом некому.
func TestDeviceTrustBytes(t *testing.T) {
	enc := make([]byte, 32)
	sig := make([]byte, 32)
	for i := range enc {
		enc[i] = byte(i)
		sig[i] = byte(255 - i)
	}
	cases := []struct{ got, want string }{
		{string(AskCertBytes(enc)), "tima.ask.v1|AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"},
		{string(DeviceCertBytes(enc, sig)), "tima.device.v1|AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8|__79_Pv6-fj39vX08_Lx8O_u7ezr6uno5-bl5OPi4eA"},
		{string(AskRevokeBytes("6f1c2b4e-0000-4000-8000-000000000001")), "tima.ask-revoke.v1|6f1c2b4e-0000-4000-8000-000000000001"},
	}
	for _, c := range cases {
		if c.got != c.want {
			t.Fatalf("байты разошлись:\n  есть  %s\n  ждали %s", c.got, c.want)
		}
	}
}
