package dev.teslanavi.navigation

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.teslanavi.MainActivity
import dev.teslanavi.TeslaNaviApp
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** The voice section on a phone-sized screen, with the model installed before the app reads it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TeslaNaviApp::class, qualifiers = "w384dp-h832dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuidanceVoiceDesignTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val installedModel = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
            val dir = File(app.noBackupFilesDir, "supertonic2").apply { mkdirs() }
            SupertonicModel.FILES.forEach { File(dir, it).writeText("model") }
            app.features.setSettingsTab(true)
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(installedModel).around(compose)

    @Test fun installedModelShowsShortStatusAndDetailsOnlyInInfoDialog() {
        compose.onNodeWithContentDescription("설정").performClick()
        compose.onNodeWithText("안내 설정").performScrollTo().performClick()
        compose.onNodeWithText("음성 모델 삭제").performScrollTo()
        compose.onNode(hasText("Supertonic 2 AI 음성") and isSelectable()).assertIsEnabled().assertIsNotSelected()
        compose.onNodeWithText("설치됨 · 인터넷 없이 합성").assertIsDisplayed()
        compose.onNodeWithText("OpenRAIL-M", substring = true).assertDoesNotExist()
        saveScreenshot("voice-choice-installed")
        compose.onNodeWithContentDescription("Supertonic 2 AI 음성 안내").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText("85MB", substring = true).assertIsDisplayed()
        compose.onNodeWithText("OpenRAIL-M", substring = true).assertIsDisplayed()
        save("voice-info-dialog", compose.runOnIdle {
            val view = checkNotNull(ShadowDialog.getLatestDialog().window).decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        })
        compose.onNodeWithText("닫기").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNode(hasText("Supertonic 2 AI 음성") and isSelectable()).performClick().assertIsSelected()
    }

    private fun saveScreenshot(name: String) = save(name, compose.runOnIdle {
        val view = compose.activity.window.decorView
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    })

    private fun save(name: String, bitmap: Bitmap) {
        val directory = File("build/reports/screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
