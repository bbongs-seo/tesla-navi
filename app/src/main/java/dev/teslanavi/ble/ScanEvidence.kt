package dev.teslanavi.ble

/** Counts observations, not unique devices. Never persists names, addresses or VINs. */
internal class ScanEvidence(private val expectedName: String) {
    private var packets = 0
    private var otherTesla = false
    private var targetUnavailable = false

    @Synchronized
    fun observe(name: String?, connectable: Boolean): Boolean {
        packets++
        if (name == expectedName) {
            if (!connectable) targetUnavailable = true
            return connectable
        }
        if (name?.matches(Regex("S[0-9a-f]{16}C")) == true) otherTesla = true
        return false
    }

    @Synchronized
    fun timeoutMessage(): String = when {
        targetUnavailable -> "등록한 차량 신호는 있지만 연결 가능한 상태가 아닙니다. 다른 휴대폰 키·키팝 연결 수를 확인하고 다시 시도해 주세요."
        otherTesla -> "Tesla 형식의 신호는 있지만 등록한 VIN과 일치하지 않습니다. 차량 화면의 VIN 17자리와 앱에 입력한 VIN을 비교해 주세요."
        packets > 0 -> "주변 Bluetooth 신호는 수신했지만 등록한 차량 신호를 찾지 못했습니다. 차량 화면의 VIN을 확인해 주세요."
        else -> "차량 검색 중 Bluetooth 신호를 수신하지 못했습니다. 위치 기능·근처 기기 권한과 차량 상태를 확인해 주세요."
    }
}
