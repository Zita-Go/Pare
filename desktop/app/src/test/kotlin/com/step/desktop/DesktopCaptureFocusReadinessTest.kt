package com.step.desktop

import androidx.compose.ui.test.*
import com.pare.app.MobileAIClient
import org.junit.Test
import java.nio.file.Files

/** Compose readiness regression; the packaged native harness separately checks real keyboard input. */
@OptIn(ExperimentalTestApi::class)
class DesktopCaptureFocusReadinessTest {
    @Test fun captureOpenedBeforeInitializationFocusesTheInputOnceItBecomesEditable()=runDesktopComposeUiTest(width=460,height=520) {
        val runtime=DesktopRuntime(Files.createTempDirectory("step-capture-focus-readiness-"),DesktopConfig(),{_,_->},
            TestProtector(),NoNetwork,FixtureClock(),MobileAIClient {_,_,_->error("No model access")})
        try {
            setContent {StepCaptureUi(runtime,{})}
            onNodeWithTag("capture_input").assertIsNotEnabled()
            runtime.initialize()
            waitForIdle()
            // No click, semantic text input or visibility toggle may repair the missed focus.
            onNodeWithTag("capture_input").assertIsEnabled().assertIsFocused()
        } finally {runtime.close()}
    }
}
