package com.sleepysoong.bramble

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class GlassUiTest {
    @get:Rule val compose = createComposeRule()

    @Before fun reset() {
        RelayState.enabled = false
        RelayState.running = false
        RelayState.busy = false
        RelayState.pending = null
        RelayState.outboundClipboard = null
        RelayState.status = "연결 정보를 입력하세요"
    }

    @After fun cleanup() = reset()

    private fun screen(dark: Boolean, pending: Boolean = true) {
        if (pending) {
            RelayState.enabled = true
            RelayState.running = true
            RelayState.status = "휴대폰에서 요청을 확인해 주세요"
            RelayState.pending = PhoneRequest("ui-request", "file", "작업에 필요한 사진을 선택해 주세요.", System.currentTimeMillis() + 120_000)
        }
        compose.setContent {
            BrambleTheme(dark) {
                BrambleScreen("http://192.168.0.10:8787", "a".repeat(64), if (dark) "dark" else "light", false,
                    {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.waitForIdle()
    }

    private fun shot(name: String, node: SemanticsNodeInteraction = compose.onRoot()): Bitmap {
        val bitmap = node.captureToImage().asAndroidBitmap()
        val directory = File("build/test-artifacts/glass").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun themeContrast(dark: Boolean, name: String) {
        screen(dark)
        shot("$name-request")
        assertReadable(shot("$name-heading", compose.onNodeWithText("파일을 보내주세요")), "$name heading")
        compose.onNodeWithTag("theme-control").performScrollTo()
        val bitmap = shot("$name-theme", compose.onNodeWithTag("theme-control"))
        shot("$name-settings")
        val index = if (dark) 2 else 1
        val pixels = mutableListOf<Int>()
        for (x in bitmap.width * index / 3 + 12 until bitmap.width * (index + 1) / 3 - 12)
            for (y in 10 until bitmap.height - 10) pixels += bitmap.getPixel(x, y)
        val bg = pixels.groupingBy { it }.eachCount().maxBy { it.value }.key
        val background = Color(bg).luminance()
        val text = pixels.map { Color(it).luminance() }.maxBy { abs(it - background) }
        val contrast = (maxOf(background, text) + .05) / (minOf(background, text) + .05)
        assertTrue("$name selected label contrast=$contrast", contrast >= 4.5)
        // The recorded label copy must be absent from the accessibility tree.
        compose.onAllNodesWithText("라이트").assertCountEquals(1)
    }

    private fun assertReadable(bitmap: Bitmap, description: String) {
        val pixels = (0 until bitmap.width).flatMap { x -> (0 until bitmap.height).map { y -> bitmap.getPixel(x, y) } }
        val background = Color(pixels.groupingBy { it }.eachCount().maxBy { it.value }.key).luminance()
        val text = pixels.map { Color(it).luminance() }.maxBy { abs(it - background) }
        val contrast = (maxOf(background, text) + .05) / (minOf(background, text) + .05)
        assertTrue("$description contrast=$contrast", contrast >= 4.5)
    }

    @Test @Config(qualifiers = "w411dp-h891dp-notnight-xhdpi")
    fun darkAppOnLightPhone() = themeContrast(true, "dark-app-light-phone")

    @Test @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun lightAppOnDarkPhone() = themeContrast(false, "light-app-dark-phone")

    @Test fun headerStaysFixedWhileSettingsScroll() {
        screen(false)
        val before = compose.onNodeWithTag("top-bar").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("배터리 설정 열기").performScrollTo().assertIsDisplayed()
        val after = compose.onNodeWithTag("top-bar").fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        shot("settings-bottom")
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi", fontScale = 1.3f)
    fun actionsAndFieldsFitNarrowPhoneWithLargeText() {
        screen(false)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        for (label in listOf("파일 선택", "요청 거절", "프록시 주소", "연결 토큰")) {
            val node = if (label.endsWith("주소") || label == "연결 토큰")
                compose.onNodeWithContentDescription(label) else compose.onNodeWithText(label)
            node.performScrollTo().assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$label exceeds viewport: $bounds / $root", bounds.left >= root.left && bounds.right <= root.right)
        }
        shot("narrow-large-text")
    }

    @Test fun themeCapsuleMovesWithTapAndDrag() {
        compose.setContent {
            var selected by remember { mutableStateOf(0) }
            BrambleTheme(false) {
                GlassSegmentedControl(listOf("시스템", "라이트", "다크"), selected, { selected = it },
                    Modifier.fillMaxWidth().padding(20.dp).testTag("segment"))
            }
        }
        compose.onNodeWithText("라이트").performClick().assertIsSelected()
        compose.waitForIdle()
        val middle = compose.onNodeWithTag("theme-thumb", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("theme-thumb", useUnmergedTree = true).performTouchInput {
            swipe(center, center.copy(x = center.x + middle.width * .8f), durationMillis = 400)
        }
        compose.onNodeWithText("다크").assertIsSelected()
        compose.waitForIdle()
        val end = compose.onNodeWithTag("theme-thumb", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("pill should follow selection", end.left > middle.left)
        shot("theme-after-drag")
    }

    @Test fun sharedButtonPressUsesSpringRelease() {
        compose.setContent {
            BrambleTheme(false) {
                Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
                    GlassButton("공유", {}, Modifier.size(180.dp, 52.dp).testTag("press-button"), accent = true)
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val node = compose.onNodeWithTag("press-button")
        val rest = shot("button-rest", node)
        node.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(250)
        val held = shot("button-held", node)
        fun textScale(): Float {
            val label = compose.onNodeWithText("공유", useUnmergedTree = true).fetchSemanticsNode()
            return label.boundsInRoot.width / label.size.width
        }
        assertEquals("button sinks while held", .955f, textScale(), .005f)
        node.performTouchInput { up() }
        val frames = (1..40).map { compose.mainClock.advanceTimeByFrame(); textScale() }
        assertTrue("release should overshoot: $frames", frames.max() > 1.003f)
        compose.mainClock.advanceTimeBy(1000)
        assertEquals("button settles at rest", 1f, textScale(), .003f)
        val released = shot("button-released", node)
        // Native rendering proves the press changes the material/geometry, not just state.
        var changed = 0
        for (x in 0 until minOf(rest.width, held.width)) for (y in 0 until minOf(rest.height, held.height))
            if (rest.getPixel(x, y) != held.getPixel(x, y)) changed++
        assertTrue("press must be visible", changed > rest.width)
        assertEquals(rest.width, released.width)
    }
}
