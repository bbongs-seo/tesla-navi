package dev.teslanavi.drive

import androidx.test.core.app.ApplicationProvider
import dev.teslanavi.TeslaNaviApp
import dev.teslanavi.data.VehicleKeyStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.content.Context

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TeslaNaviApp::class)
class ConnectionHistoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<TeslaNaviApp>()

    @Test fun failureSurvivesControllerRecreationWithoutRestoringLiveConnection() {
        val controller = DriveController(context, VehicleKeyStore(context))
        controller.prepare("5YJYGDEE1NF123456")
        controller.fail(IllegalStateException("주변 신호는 있지만 대상 차량이 없습니다"))
        controller.clear()
        controller.prepare("5YJ3E1EA1MF123456")
        val restored = DriveController(context, VehicleKeyStore(context)).state.value
        assertTrue(restored.events.any { it.contains("실패 단계: CONNECTING") && it.contains("대상 차량이 없습니다") })
        assertEquals(DrivePhase.IDLE, restored.phase)
        assertFalse(restored.active)
        assertNull(restored.snapshot)
        assertNull(restored.vin)
    }

    @Test fun repeatedAutomaticFailuresNearAParkedCarDoNotFloodTheHistory() {
        val controller = DriveController(context, VehicleKeyStore(context))
        val before = ConnectionHistory(context).read().size
        repeat(20) {
            controller.prepare("5YJYGDEE1NF123456", automatic = true)
            controller.fail(VehicleAsleepException())
        }
        val written = ConnectionHistory(context).read().drop(before)
        assertEquals(2, written.size)
        assertTrue(written[0].endsWith("자동 연결 준비"))
        assertTrue(written[1].contains("VehicleAsleepException"))
        val state = controller.state.value
        assertTrue(state.asleep)
        assertEquals("차량 잠듦 · 깨어나면 연결", state.status)
        controller.prepare("5YJYGDEE1NF123456")
        assertTrue(ConnectionHistory(context).read().any { it.endsWith("이전 자동 연결 결과가 19회 더 반복됨") })
        assertFalse(controller.state.value.asleep)
    }

    @Test fun diskHistoryIsBoundedAndRedactsVinAcrossReload() {
        val history = ConnectionHistory(context)
        repeat(165) { history.append("기록 $it") }
        history.append("차량 5YJYGDEE1NF123456 오류\n한 줄")
        val restored = ConnectionHistory(context).read()
        assertEquals(160, restored.size)
        assertFalse(restored.any { it.endsWith("기록 0") })
        assertTrue(restored.last().contains("[VIN]"))
        assertFalse(restored.last().contains("\n"))
        assertFalse(context.getSharedPreferences("connection-history", Context.MODE_PRIVATE)
            .getString("entries", "")!!.contains("5YJYGDEE1NF123456"))
        assertTrue(restored.last().matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*")))
    }

    @Test fun damagedDiagnosticFileDoesNotBlockAppStartup() {
        context.getSharedPreferences("connection-history", Context.MODE_PRIVATE).edit()
            .putString("entries", "{incomplete").commit()
        val history = ConnectionHistory(context)
        assertTrue(history.read().isEmpty())
        history.append("다음 연결")
        assertEquals(history.read(), ConnectionHistory(context).read())
    }
}
