package dev.teslanavi.protocol

import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.GetDriveState
import com.tesla.generated.carserver.server.GetVehicleData
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.server.VehicleAction
import com.tesla.generated.keys.Role
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.universalmessage.SessionInfoRequest
import com.tesla.generated.vcsec.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.KeyPair

interface VehicleTransport {
    val incoming: ReceiveChannel<ByteArray>
    suspend fun send(message: ByteArray)
    fun close()
    fun diagnostics(): String = ""
}

class VehicleFault(val code: Int) : Exception(when (code) {
    1, 2, 11 -> "차량이 준비 중입니다. 잠시 후 다시 연결해 주세요. (코드 $code)"
    3 -> "차량에 앱 키가 없습니다. 키 등록을 먼저 진행해 주세요."
    4 -> "앱 키가 비활성화되어 있습니다. 차량의 키 목록을 확인해 주세요."
    7 -> "차량이 데이터 조회 권한을 허용하지 않았습니다."
    21 -> "차량 설정에서 모바일 액세스를 켜 주세요."
    else -> "차량 인증 또는 조회 오류 (코드 $code). 다시 연결해 주세요."
})

/** One serialized request at a time; no unrelated or unauthenticated response becomes vehicle data. */
class TeslaClient(private val vin: String, private val keys: KeyPair, private val transport: VehicleTransport,
    private val diagnostic: (String) -> Unit = {}) {
    private val mutex = Mutex()
    private var session: TeslaSession? = null
    private var enrollmentPending = false
    private val address = randomBytes(16).bytes()

    suspend fun requestKeyEnrollment() = mutex.withLock {
        val payload = UnsignedMessage.newBuilder().setWhitelistOperation(
            WhitelistOperation.newBuilder()
                .setAddKeyToWhitelistAndAddPermissions(PermissionChange.newBuilder()
                    .setKey(PublicKey.newBuilder().setPublicKeyRaw(TeslaKeys.publicBytes(keys.public).bytes()))
                    .setKeyRole(Role.ROLE_VEHICLE_MONITOR))
                .setMetadataForKey(KeyMetadata.newBuilder().setKeyFormFactor(KeyFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE))
        ).build()
        val envelope = ToVCSECMessage.newBuilder().setSignedMessage(SignedMessage.newBuilder()
            .setSignatureType(SignatureType.SIGNATURE_TYPE_PRESENT_KEY)
            .setProtobufMessageAsBytes(payload.toByteString())).build()
        // Raw VCSEC envelope is intentional: this step is approved with the physical key card.
        transport.send(envelope.toByteArray())
        enrollmentPending = true
    }

    suspend fun authenticate() = mutex.withLock { handshake() }

    private suspend fun handshake() {
        session = null
        val request = baseMessage().setSessionInfoRequest(SessionInfoRequest.newBuilder()
            .setPublicKey(TeslaKeys.publicBytes(keys.public).bytes())).build()
        val startedAt = System.nanoTime()
        transport.send(request.toByteArray())
        val reply = awaitReply(request, 4000) { !it.sessionInfo.isEmpty }
        require((System.nanoTime() - startedAt) < 4_000_000_000L) { "차량 인증 응답이 지연되었습니다. 다시 연결해 주세요." }
        require(reply.signatureData.hasSessionInfoTag()) { "차량 인증 서명이 누락되었습니다." }
        session = TeslaSession.authenticate(vin, keys, request.uuid.toByteArray(),
            reply.sessionInfo.toByteArray(), reply.signatureData.sessionInfoTag.tag.toByteArray())
        enrollmentPending = false
    }

    suspend fun readDriveState(): DriveSnapshot = mutex.withLock {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val activeSession = session ?: error("차량 인증을 먼저 진행해 주세요.")
                val action = Action.newBuilder().setVehicleAction(VehicleAction.newBuilder()
                    .setGetVehicleData(GetVehicleData.newBuilder().setGetDriveState(GetDriveState.getDefaultInstance()))).build()
                val request = activeSession.encrypt(baseMessage().setFlags(2)
                    .setProtobufMessageAsBytes(action.toByteString()).build())
                transport.send(request.message.toByteArray())
                val reply = awaitReply(request.message, 8000) { !it.protobufMessageAsBytes.isEmpty }
                val plaintext = activeSession.decrypt(reply, request.responseId)
                val response = Response.parseFrom(plaintext)
                check(response.actionStatus.resultValue != 2) {
                    "차량 조회 실패: ${response.actionStatus.resultReason.plainText.ifBlank { "상세 사유 없음" }}"
                }
                check(response.hasVehicleData() && response.vehicleData.hasDriveState()) { "차량에서 주행 상태를 제공하지 않았습니다." }
                return@withLock DriveSnapshot.from(response.vehicleData.driveState)
            } catch (error: VehicleFault) {
                if (attempt == 0 && error.code in setOf(5, 6, 15, 17, 26)) handshake() else throw error
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("차량 상태 조회 실패")
    }

    private fun baseMessage() = RoutableMessage.newBuilder()
        .setToDestination(Destination.newBuilder().setDomain(Domain.DOMAIN_INFOTAINMENT))
        .setFromDestination(Destination.newBuilder().setRoutingAddress(address))
        .setUuid(randomBytes(16).bytes())

    private suspend fun awaitReply(request: RoutableMessage, timeoutMs: Long,
        accept: (RoutableMessage) -> Boolean): RoutableMessage {
        var received = 0
        var malformed = 0
        var unrelated = 0
        var empty = 0
        try { return withTimeout(timeoutMs) {
        while (true) {
            val bytes = transport.incoming.receive()
            received++
            val response = try { RoutableMessage.parseFrom(bytes) } catch (_: Exception) { malformed++; continue }
            if (enrollmentPending) {
                // Legacy enrollment is sent without a routing envelope. Some replies are raw
                // VCSEC; routed replies must originate from VCSEC and target this connection.
                val enrollmentPayload = when {
                    !response.hasFromDestination() && !response.hasToDestination() -> bytes
                    response.fromDestination.domain == Domain.DOMAIN_VEHICLE_SECURITY &&
                        response.toDestination.routingAddress == address -> response.protobufMessageAsBytes.toByteArray()
                    else -> null
                }
                enrollmentPayload?.let { enrollmentFailure(it)?.let { error -> throw error } }
            }
            if (response.requestUuid != request.uuid || response.toDestination.routingAddress != address ||
                response.fromDestination.domain != Domain.DOMAIN_INFOTAINMENT) { unrelated++; continue }
            val fault = response.signedMessageStatus.signedMessageFaultValue
            if (fault != 0) throw VehicleFault(fault)
            if (accept(response)) return@withTimeout response
            empty++
        }
        @Suppress("UNREACHABLE_CODE") error("응답 없음")
        } } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            val stage = if (request.hasSessionInfoRequest()) "차량 인증" else "주행 정보"
            diagnostic("$stage 응답 시간 초과 · 수신=$received, 형식오류=$malformed, 다른요청=$unrelated, 내용없음=$empty · ${transport.diagnostics()}")
            throw error
        }
    }

    fun close() { session = null; transport.close() }
}
