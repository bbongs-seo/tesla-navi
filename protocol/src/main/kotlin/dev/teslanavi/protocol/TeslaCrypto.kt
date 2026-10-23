// Kotlin adaptation of Tesla vehicle-command/internal/authentication (Apache-2.0).
// Modified: Java cryptography, strict response validation, monotonic session time.
package dev.teslanavi.protocol

import com.google.protobuf.ByteString
import com.tesla.generated.signatures.AES_GCM_Personalized_Signature_Data
import com.tesla.generated.signatures.KeyIdentity
import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.signatures.Session_Info_Status
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.universalmessage.RoutableMessage
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class KeyNotRegisteredException : IllegalStateException("차량에 앱 키가 등록되지 않았습니다. 키 등록을 먼저 진행해 주세요.")

internal fun ByteArray.bytes(): ByteString = ByteString.copyFrom(this)
internal fun uint32(value: Long): ByteArray = ByteBuffer.allocate(4).putInt(value.toInt()).array()
internal fun randomBytes(count: Int) = ByteArray(count).also { SecureRandom().nextBytes(it) }

internal fun metadata(vararg fields: Pair<Int, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    var previous = -1
    for ((tag, value) in fields) {
        require(tag > previous && tag in 0..254 && value.size <= 255)
        out.write(tag); out.write(value.size); out.write(value)
        previous = tag
    }
    out.write(255)
    return out.toByteArray()
}

object TeslaKeys {
    fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
    }
    fun publicBytes(publicKey: PublicKey): ByteArray {
        val ec = publicKey as ECPublicKey
        fun coordinate(value: BigInteger): ByteArray = value.toByteArray().let {
            if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it
        }
        return byteArrayOf(4) + coordinate(ec.w.affineX) + coordinate(ec.w.affineY)
    }
    fun decodePublic(raw: ByteArray): PublicKey {
        require(raw.size == 65 && raw[0] == 4.toByte()) { "차량 공개키 형식 오류" }
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters))
    }
    fun sharedKey(privateKey: PrivateKey, remotePublic: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(decodePublic(remotePublic), true)
        return MessageDigest.getInstance("SHA-1").digest(agreement.generateSecret()).copyOf(16)
    }
    fun fingerprint(publicKey: PublicKey): String = MessageDigest.getInstance("SHA-256")
        .digest(publicBytes(publicKey)).take(8).joinToString(":") { "%02X".format(it.toInt() and 255) }
}

class TeslaSession private constructor(
    private val vin: String,
    private val publicKey: ByteArray,
    private val key: ByteArray,
    private val info: SessionInfo,
    private val nanoTime: () -> Long,
) {
    private val startedAt = nanoTime()
    private var counter = Integer.toUnsignedLong(info.counter)

    data class Request(val message: RoutableMessage, val responseId: ByteArray)

    fun encrypt(message: RoutableMessage): Request {
        check(counter < 0xffffffffL) { "인증 세션을 다시 연결해 주세요." }
        counter++
        val expires = Integer.toUnsignedLong(info.clockTime) + (nanoTime() - startedAt) / 1_000_000_000 + 10
        require(expires in 0..(1L shl 30)) { "차량 인증 시간 범위 오류" }
        val fields = mutableListOf(
            0 to byteArrayOf(5), 1 to byteArrayOf(message.toDestination.domainValue.toByte()),
            2 to vin.toByteArray(Charsets.US_ASCII), 3 to info.epoch.toByteArray(),
            4 to uint32(expires), 5 to uint32(counter),
        )
        if (message.flags != 0) fields += 7 to uint32(Integer.toUnsignedLong(message.flags))
        val aad = MessageDigest.getInstance("SHA-256").digest(metadata(*fields.toTypedArray()))
        val nonce = randomBytes(12)
        val sealed = crypt(Cipher.ENCRYPT_MODE, key, nonce, message.protobufMessageAsBytes.toByteArray(), aad)
        val tag = sealed.takeLast(16).toByteArray()
        val signature = AES_GCM_Personalized_Signature_Data.newBuilder()
            .setEpoch(info.epoch).setNonce(nonce.bytes()).setCounter(counter.toInt())
            .setExpiresAt(expires.toInt()).setTag(tag.bytes())
        return Request(message.toBuilder()
            .setProtobufMessageAsBytes(sealed.copyOf(sealed.size - 16).bytes())
            .setSignatureData(SignatureData.newBuilder()
                .setSignerIdentity(KeyIdentity.newBuilder().setPublicKey(publicKey.bytes()))
                .setAESGCMPersonalizedData(signature)).build(), byteArrayOf(5) + tag)
    }

    fun decrypt(message: RoutableMessage, responseId: ByteArray): ByteArray {
        require(message.signatureData.hasAESGCMResponseData()) { "암호화되지 않은 차량 응답을 거부했습니다." }
        val signature = message.signatureData.getAESGCMResponseData()
        val aad = MessageDigest.getInstance("SHA-256").digest(metadata(
            0 to byteArrayOf(9), 1 to byteArrayOf(message.fromDestination.domainValue.toByte()),
            2 to vin.toByteArray(Charsets.US_ASCII),
            5 to uint32(Integer.toUnsignedLong(signature.counter)),
            7 to uint32(Integer.toUnsignedLong(message.flags)), 8 to responseId,
            9 to uint32(message.signedMessageStatus.signedMessageFaultValue.toLong()),
        ))
        require(signature.nonce.size() == 12 && signature.tag.size() == 16) { "응답 암호 형식 오류" }
        return crypt(Cipher.DECRYPT_MODE, key, signature.nonce.toByteArray(),
            message.protobufMessageAsBytes.toByteArray() + signature.tag.toByteArray(), aad)
    }

    companion object {
        fun authenticate(vin: String, keys: KeyPair, challenge: ByteArray, encoded: ByteArray, tag: ByteArray,
            nanoTime: () -> Long = System::nanoTime): TeslaSession {
            val info = SessionInfo.parseFrom(encoded)
            if (info.status != Session_Info_Status.SESSION_INFO_STATUS_OK) throw KeyNotRegisteredException()
            require(info.epoch.size() == 16 && challenge.size == 16 && tag.size == 32) { "차량 인증 응답 형식 오류" }
            val key = TeslaKeys.sharedKey(keys.private, info.publicKey.toByteArray())
            val subkey = hmac(key, "session info".toByteArray(Charsets.US_ASCII))
            val expected = hmac(subkey, metadata(0 to byteArrayOf(6),
                2 to vin.toByteArray(Charsets.US_ASCII), 6 to challenge) + encoded)
            require(MessageDigest.isEqual(expected, tag)) { "차량 인증 응답 서명 검증에 실패했습니다." }
            return TeslaSession(vin, TeslaKeys.publicBytes(keys.public), key, info, nanoTime)
        }

        internal fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
        }
        internal fun crypt(mode: Int, key: ByteArray, nonce: ByteArray, data: ByteArray, aad: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad); doFinal(data)
            }
    }
}
