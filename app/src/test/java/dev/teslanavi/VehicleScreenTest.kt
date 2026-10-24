package dev.teslanavi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import dev.teslanavi.data.VehicleStore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TeslaNaviApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VehicleScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun soundChecksAreAvailableWithoutVehicleAndExplainZeroVolume() {
        compose.onNodeWithText("안내 설정").performClick()
        compose.onNodeWithText("카메라 음성 테스트").assertIsEnabled()
        compose.onNodeWithText("과속 경고음 테스트").assertIsEnabled()
        compose.onNodeWithContentDescription("안내 음량").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithText("카메라 음성 테스트").performClick()
        compose.onNodeWithText("안내 음량이 0%입니다. 음량을 올린 뒤 테스트해 주세요.").assertExists()
        compose.onNodeWithText("과속 경고음 테스트").performClick()
        compose.onNodeWithText("안내 음량이 0%입니다. 음량을 올린 뒤 테스트해 주세요.").assertExists()
    }

    @Test fun guidanceControlsSaveAndReopenAtSelectedValues() {
        compose.onNodeWithText("안내 설정").performClick()
        compose.onNodeWithContentDescription("안내 음량").performSemanticsAction(SemanticsActions.SetProgress) { it(35f) }
        compose.onNodeWithContentDescription("고속도로 안내 거리").performSemanticsAction(SemanticsActions.SetProgress) { it(1200f) }
        compose.onNodeWithContentDescription("일반도로 안내 거리").performSemanticsAction(SemanticsActions.SetProgress) { it(600f) }
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithText("안내 설정").performClick()
        compose.onNodeWithText("안내 음량 35%").assertIsDisplayed()
        saveScreenshot("guidance-settings")
        compose.onNodeWithText("고속도로 1200 m 전").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("일반도로 600 m 전").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("기본값 복원").performScrollTo().performClick()
        compose.onNodeWithText("고속도로 500 m 전").performScrollTo().assertIsDisplayed()
    }

    @Test fun firstLaunchShowsRegistrationAndDisabledConnection() {
        compose.onNodeWithText("첫 차량을 등록해 주세요").assertIsDisplayed()
        compose.onNode(hasText("차량 연결") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("처음 연결하나요? 앱 키 등록").assertIsNotEnabled()
        compose.onNodeWithContentDescription("자동 연결").assertIsNotEnabled()
        saveScreenshot("first-launch")
    }

    @Test fun registersModelYAndShowsOnlyMaskedVinInList() {
        compose.onNodeWithText("차량 등록하기").performClick()
        compose.onNodeWithText("Model Y").performClick()
        compose.onNodeWithText("차대번호(VIN) 17자리").performTextInput("5YJYGDEE1NF123456")
        compose.onNodeWithText("등록", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Model Y").assertIsDisplayed()
        compose.onNodeWithText("••• 123456  ✓").assertIsDisplayed()
        compose.onNodeWithText("처음 연결하나요? 앱 키 등록").assertIsEnabled()
        saveScreenshot("vehicle-registered")
    }

    @Test fun malformedVinStaysInDialogWithoutSavingVehicle() {
        compose.onNodeWithText("차량 등록하기").performClick()
        compose.onNodeWithText("차대번호(VIN) 17자리").performTextInput("INVALID")
        compose.onNodeWithText("등록", useUnmergedTree = true).performClick()
        compose.onNodeWithText("VIN은 I·O·Q를 제외한 영문과 숫자 17자리입니다.").assertIsDisplayed()
    }

    @Test fun selectedVehiclePersistsAndDuplicateVinDoesNotCreateSecondRecord() {
        val app = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        app.vehicles.add("Model 3", "5YJ3E1EA1MF111111")
        app.vehicles.add("Model Y", "5YJYGDEE1NF222222")
        app.vehicles.add("Model Y", "5YJYGDEE1NF222222")
        app.vehicles.select("5YJ3E1EA1MF111111")
        val reloaded = VehicleStore(app)
        org.junit.Assert.assertEquals(2, reloaded.vehicles.value.size)
        org.junit.Assert.assertEquals("5YJ3E1EA1MF111111", reloaded.selected.value)
    }

    private fun saveScreenshot(name: String) {
        val directory = File("build/reports/screenshots").apply { mkdirs() }
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
