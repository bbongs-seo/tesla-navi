package dev.teslanavi

import android.app.Application
import dev.teslanavi.data.VehicleKeyStore
import dev.teslanavi.data.VehicleStore
import dev.teslanavi.drive.DriveController
import dev.teslanavi.drive.AutoConnectSettings
import dev.teslanavi.navigation.*

class TeslaNaviApp : Application() {
    val vehicles by lazy { VehicleStore(this) }
    val keys by lazy { VehicleKeyStore(this) }
    val drive by lazy { DriveController(this, keys) }
    val autoConnect by lazy { AutoConnectSettings(this) }
    val kakao by lazy { KakaoSettings(this) }
    val guidance by lazy { GuidanceSettings(this) }
    val cameras by lazy {
        CameraController(KakaoNavigationEngine(this, kakao), AndroidCameraSpeaker(this), guidance.options, android.os.SystemClock::elapsedRealtime)
            .also { it.configured(kakao.hasKey.value) }
    }
}
