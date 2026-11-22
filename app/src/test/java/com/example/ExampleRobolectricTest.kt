package com.example

import android.content.Context
import android.content.Intent
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.example.data.OverlayRuntimeBridge
import com.example.data.ShaderVariableCodec
import com.example.model.BuiltInTexture
import com.example.model.ClockTimeUnit
import com.example.model.ShaderVariable
import com.example.model.UniformType
import com.example.model.ValueMode
import com.example.overlay.ScreenCaptureService
import com.example.overlay.ShaderOverlayService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("ShaderVeil", appName)
    }

    @Test
    fun `variable codec round-trips all uniform types`() {
        val original = listOf(
            ShaderVariable(name = "u_slot_1", type = UniformType.NONE),
            ShaderVariable(
                name = "u_const_1",
                type = UniformType.VALUE,
                valueMode = ValueMode.CONSTANT,
                constantValue = 3.14f,
                minValue = 0f,
                maxValue = 10f,
                stepValue = 0.05f,
            ),
            ShaderVariable(
                name = "u_clock_time_1",
                type = UniformType.VALUE,
                valueMode = ValueMode.CLOCK_TIME,
                clockUnit = ClockTimeUnit.HOURS_24,
            ),
            ShaderVariable(name = "u_tex_1", type = UniformType.TEXTURE, builtInTexture = BuiltInTexture.SCREEN_CAPTURE),
            ShaderVariable(name = "u_check_1", type = UniformType.CHECKBOX, checked = true, checkboxAsFloat = true),
            ShaderVariable(name = "MY_DEF", isAutoNamed = false, type = UniformType.DEFINITION, defineEnabled = true, defineValue = "42"),
        )

        val json = ShaderVariableCodec.encodeList(original)
        val decoded = ShaderVariableCodec.decodeList(json)
        assertEquals(original, decoded)
    }

    @Test
    fun `studio launches with blank variables menu and allows adding all types and navigating tabs`() {
        composeRule.onNodeWithTag("variables_empty_state").assertIsDisplayed()

        // Add Value from empty state -> auto-named u_const_1
        composeRule.onNodeWithTag("empty_add_value").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithTag("variables_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("variables_list").performScrollToNode(hasTestTag("variable_card_u_const_1"))
        composeRule.onNodeWithTag("variable_card_u_const_1").assertExists()

        // Add Texture, Checkbox, #define, None via Add Variable menu
        composeRule.onNodeWithTag("variables_list").performScrollToNode(hasTestTag("add_variable_button"))
        composeRule.onNodeWithTag("add_variable_button").performClick()
        composeRule.onNodeWithTag("menu_add_texture").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("variables_list").performScrollToNode(hasTestTag("variable_card_u_tex_1"))
        composeRule.onNodeWithTag("variable_card_u_tex_1").assertExists()

        composeRule.onNodeWithTag("variables_list").performScrollToNode(hasTestTag("add_variable_button"))
        composeRule.onNodeWithTag("add_variable_button").performClick()
        composeRule.onNodeWithTag("menu_add_checkbox").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("variables_list").performScrollToNode(hasTestTag("variable_card_u_check_1"))
        composeRule.onNodeWithTag("variable_card_u_check_1").assertExists()

        composeRule.onNodeWithTag("variables_list").performScrollToNode(hasTestTag("add_variable_button"))
        composeRule.onNodeWithTag("add_variable_button").performClick()
        composeRule.onNodeWithTag("menu_add_definition").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("variables_list").performScrollToNode(hasTestTag("variable_card_DEF_1"))
        composeRule.onNodeWithTag("variable_card_DEF_1").assertExists()

        composeRule.onNodeWithTag("variables_list").performScrollToNode(hasTestTag("add_variable_button"))
        composeRule.onNodeWithTag("add_variable_button").performClick()
        composeRule.onNodeWithTag("menu_add_none").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("variables_list").performScrollToNode(hasTestTag("variable_card_u_slot_1"))
        composeRule.onNodeWithTag("variable_card_u_slot_1").assertExists()

        // Switch through GLSL Code, Diagnostics, Library, and Engine tabs
        composeRule.onNodeWithTag("studio_tab_glsl").performClick()
        composeRule.onNodeWithTag("glsl_code_editor").assertIsDisplayed()

        composeRule.onNodeWithTag("studio_tab_diagnostics").performClick()
        composeRule.onNodeWithTag("diagnostics_panel").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_library").performClick()
        composeRule.onNodeWithTag("library_screen").assertIsDisplayed()

        // Load Chromatic Glitch template and verify pre-wired uniforms
        composeRule.onNodeWithTag("library_screen").performScrollToNode(hasTestTag("load_template_tpl_chromatic_glitch"))
        composeRule.onNodeWithTag("load_template_tpl_chromatic_glitch").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithTag("variables_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("variables_list").performScrollToNode(hasTestTag("variable_card_sTexture"))
        composeRule.onNodeWithTag("variable_card_sTexture").assertExists()
        composeRule.onNodeWithTag("variables_list").performScrollToNode(hasTestTag("screen_capture_status_sTexture"))
        composeRule.onNodeWithTag("start_screen_capture_sTexture").assertExists()
        composeRule.onNodeWithTag("variables_list").performScrollToNode(hasTestTag("variable_card_iTime"))
        composeRule.onNodeWithTag("variable_card_iTime").assertExists()
        composeRule.onNodeWithTag("variables_list").performScrollToNode(hasTestTag("variable_card_GLITCH"))
        composeRule.onNodeWithTag("variable_card_GLITCH").assertExists()

        composeRule.onNodeWithTag("nav_engine").performClick()
        composeRule.onNodeWithTag("engine_settings_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("engine_settings_screen").performScrollToNode(hasTestTag("switch_anti_echo"))
        composeRule.onNodeWithTag("switch_anti_echo").assertExists()
    }

    @Test
    fun `foreground service notification icon is not an adaptive icon and services handle edge cases safely`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val statIcon = ContextCompat.getDrawable(context, R.drawable.ic_stat_shaderveil)
        assertNotNull(statIcon)
        assertFalse(
            "Notification small icon must not be an AdaptiveIconDrawable",
            statIcon is AdaptiveIconDrawable
        )

        // Calling stop/refresh when services are not active must not throw
        ShaderOverlayService.stop(context)
        ShaderOverlayService.refreshCleanScreen(context)
        ScreenCaptureService.stop(context)

        // Starting ShaderOverlayService without overlay permission stops cleanly without throwing
        val overlayController = Robolectric.buildService(ShaderOverlayService::class.java).create()
        overlayController.startCommand(0, 0)
        assertFalse(OverlayRuntimeBridge.isOverlayRunning.value)
        overlayController.destroy()

        // Starting ScreenCaptureService without MediaProjection result stops cleanly without throwing
        val captureController = Robolectric.buildService(
            ScreenCaptureService::class.java,
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
            }
        ).create()
        captureController.startCommand(0, 0)
        captureController.destroy()
    }
}
