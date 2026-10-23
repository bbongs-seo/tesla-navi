package dev.teslanavi.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import dev.teslanavi.protocol.TeslaKeys
import dev.teslanavi.protocol.VehicleIdentity
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** P-256 keys are AES-GCM wrapped at rest by a non-exportable Android Keystore key.
 * ECDH runs in memory for compatibility with Android 8+. No plaintext key is persisted. */
class VehicleKeyStore(private val context: Context) {
    private val alias = "tesla-navi-key-wrap-v1"
    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    @Synchronized
    fun getOrCreate(vin: String): KeyPair {
        require(VehicleIdentity.isValidVin(vin))
        val id = MessageDigest.getInstance("SHA-256").digest(vin.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val file = AtomicFile(File(context.noBackupFilesDir, "key-$id.bin"))
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            // If decryption fails, preserve the existing key rather than silently replacing it.
            val encoded = file.openRead().use { it.readBytes() }
            require(encoded.size > 28) { "저장된 앱 키 파일이 손상되었습니다." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, encoded.copyOfRange(0, 12)))
            cipher.updateAAD(vin.toByteArray())
            val data = JSONObject(String(cipher.doFinal(encoded.copyOfRange(12, encoded.size)), Charsets.UTF_8))
            val factory = KeyFactory.getInstance("EC")
            return KeyPair(factory.generatePublic(X509EncodedKeySpec(Base64.decode(data.getString("public"), Base64.NO_WRAP))),
                factory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(data.getString("private"), Base64.NO_WRAP))))
        }
        val pair = TeslaKeys.generate()
        val data = JSONObject().put("public", Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
            .put("private", Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP)).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        cipher.updateAAD(vin.toByteArray())
        val encoded = cipher.iv + cipher.doFinal(data)
        val stream = file.startWrite()
        try { stream.write(encoded); file.finishWrite(stream) } catch (error: Throwable) { file.failWrite(stream); throw error }
        return pair
    }
}
