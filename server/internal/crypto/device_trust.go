package crypto

import "encoding/base64"

// Байты подписей доверия к устройствам (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ §2а). Зеркало —
// `messenger-crypto/.../DeviceTrust.kt`: строка в строку, иначе подпись клиента не сойдётся.
//
// `user_id` в байтах нет намеренно: при заведении аккаунта его ещё нет, а к аккаунту
// свидетельство привязывает подписавший ключ — ключ личности у аккаунта свой.

// AskCertBytes — что ключ личности подписывает, заверяя ключ подписи устройств.
func AskCertBytes(askPub []byte) []byte {
	return []byte("tima.ask.v1|" + b64url(askPub))
}

// DeviceCertBytes — что КПУ или ключ личности подписывает, заверяя устройство.
func DeviceCertBytes(encryptionPub, signingPub []byte) []byte {
	return []byte("tima.device.v1|" + b64url(encryptionPub) + "|" + b64url(signingPub))
}

// AskRevokeBytes — что ключ личности подписывает, отзывая КПУ (ДУ4).
func AskRevokeBytes(askID string) []byte {
	return []byte("tima.ask-revoke.v1|" + askID)
}

// DeviceEpochKeyBytes — что ключ подписи устройства подписывает, публикуя ключ шифрования на
// эпоху (ПЛАН-(ПС) ПС3). device_id и эпоха в байтах — чтобы подпись нельзя было перенести на
// другое устройство или другой месяц.
func DeviceEpochKeyBytes(deviceID, epoch string, encryptionPub []byte) []byte {
	return []byte("tima.device-epoch.v1|" + deviceID + "|" + epoch + "|" + b64url(encryptionPub))
}

func b64url(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }
