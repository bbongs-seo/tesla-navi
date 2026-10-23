package dev.teslanavi.protocol

import com.tesla.generated.vcsec.FromVCSECMessage

class EnrollmentRejectedException(val code: Int) : IllegalStateException(
    when (code) {
        3, 4 -> "차량 키 목록이 가득 찼습니다. 차량의 제어 > 잠금장치에서 키 목록을 확인해 주세요."
        5, 9, 19, 20 -> "차량이 이 앱 키의 등록 권한을 허용하지 않았습니다."
        6 -> "차량이 앱 공개키 형식을 거부했습니다."
        14 -> "승인용 키 카드가 인식되지 않았습니다. 센터 콘솔 카드 리더에 키 카드를 대고 다시 시도해 주세요."
        23 -> "차량이 키 등록 승인을 시작하지 못했습니다. 차량 화면을 켜고 다시 시도해 주세요."
        24 -> "차량 화면에서 앱 키 등록이 거절되었습니다. 다시 요청한 뒤 차량 화면에서 승인해 주세요."
        25 -> "키 카드 인식 대기 시간이 초과되었습니다. 등록 요청 후 카드 리더에 키 카드를 대 주세요."
        26 -> "차량 화면의 승인 대기 시간이 초과되었습니다. 다시 요청한 뒤 차량 화면에서 승인해 주세요."
        27 -> "발렛 모드에서는 앱 키를 등록할 수 없습니다. 발렛 모드를 해제한 뒤 다시 시도해 주세요."
        28 -> "차량에서 앱 키 등록이 취소되었습니다."
        else -> "차량이 앱 키 등록을 거부했습니다."
    } + " (키 등록 코드 $code)"
)

/** Unauthenticated enrollment replies provide diagnostics only, never proof of enrollment. */
internal fun enrollmentFailure(payload: ByteArray): EnrollmentRejectedException? {
    val reply = try { FromVCSECMessage.parseFrom(payload) } catch (_: Exception) { return null }
    if (!reply.hasCommandStatus() || !reply.commandStatus.hasWhitelistOperationStatus()) return null
    val status = reply.commandStatus.whitelistOperationStatus
    val code = status.whitelistOperationInformationValue
    // An existing key still needs the normal signed infotainment handshake.
    if (code == 0 || code == 13) return null
    return EnrollmentRejectedException(code)
}
