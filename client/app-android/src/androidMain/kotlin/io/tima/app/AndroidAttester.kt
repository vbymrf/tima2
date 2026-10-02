package io.tima.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.tima.shared.AttestedKey
import io.tima.shared.DeviceAttester
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Аттестация ключа телефона (ПЛАН-УСТРОЙСТВ-И-ИСТОРИИ ДУ8, Р20).
 *
 * Отдельный ключ P-256 в хранилище ключей Android — наши ключи (Ed25519, X25519) там не живут, а
 * `KodiumPrivateKey.fromRaw` не трогаем. Ключ заводится заново под каждый вызов сервера: вызов
 * вшивается в запись аттестации при рождении ключа. StrongBox — где он есть, иначе TEE.
 */
class AndroidAttester(private val context: Context) : DeviceAttester {

    override fun attest(challengeToken: String, signed: ByteArray): AttestedKey? {
        val challenge = MessageDigest.getInstance("SHA-256").digest(challengeToken.encodeToByteArray())
        val strongBox = Build.VERSION.SDK_INT >= 28 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        val keys = KeyStore.getInstance(STORE).apply { load(null) }
        val made = runCatching { make(challenge, strongBox) }.recoverCatching { make(challenge, false) }.getOrNull() ?: return null
        val chain = keys.getCertificateChain(ALIAS)?.map { it.encoded } ?: return null
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(made.private)
            update(signed)
            sign()
        }
        return AttestedKey(chain, sig)
    }

    private fun make(challenge: ByteArray, strongBox: Boolean) =
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE).run {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)
            if (strongBox && Build.VERSION.SDK_INT >= 28) spec.setIsStrongBoxBacked(true)
            initialize(spec.build())
            generateKeyPair()
        }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val ALIAS = "tima.attest.v1"
    }
}
