package com.pare.app

import org.junit.Assert.*
import org.junit.Test
import androidx.lifecycle.SavedStateHandle

class HomeWidgetPinModelTest {
    @Test fun restoredRequestRetainsPhaseAndBaselineInsteadOfAutomaticallyRequestingAgain() {
        val saved=SavedStateHandle();var calls=0
        HomeWidgetPinModel(saved).request({setOf(5)}){calls++;true}
        val restored=HomeWidgetPinModel(saved)
        assertEquals(WidgetPinPhase.REQUESTED,restored.phase);assertEquals(1,calls)
        restored.refresh {setOf(5)};assertEquals(WidgetPinPhase.UNCONFIRMED,restored.phase)
        restored.refresh {setOf(5,6)};assertEquals(WidgetPinPhase.ADDED,restored.phase)
        assertEquals(1,calls)
    }
    @Test fun supportOnlyMeansRequestedAndOldWidgetsDoNotCountAsSuccess() {
        val model=HomeWidgetPinModel();var calls=0
        model.request({setOf(10)}){calls++;true}
        assertEquals(WidgetPinPhase.REQUESTED,model.phase)
        model.refresh {setOf(10)}
        assertEquals(WidgetPinPhase.UNCONFIRMED,model.phase);assertEquals(1,calls)
        model.refresh {setOf(10,11)}
        assertEquals(WidgetPinPhase.ADDED,model.phase)
    }
    @Test fun unsupportedAndExceptionsHaveDistinctVisibleOutcomes() {
        val model=HomeWidgetPinModel()
        model.request({emptySet()}){false};assertEquals(WidgetPinPhase.UNSUPPORTED,model.phase)
        model.refresh {setOf(2)};assertEquals(WidgetPinPhase.UNSUPPORTED,model.phase)
        model.request({emptySet()}){error("launcher unavailable")};assertEquals(WidgetPinPhase.FAILED,model.phase)
        model.reset();assertEquals(WidgetPinPhase.IDLE,model.phase)
    }
    @Test fun retryTakesNewBaselineRatherThanConfirmingAnOldAddition() {
        val model=HomeWidgetPinModel();model.request({setOf(1)}){true}
        model.refresh {setOf(1,2)};assertEquals(WidgetPinPhase.ADDED,model.phase)
        model.request({setOf(1,2)}){true};model.refresh {setOf(1,2)}
        assertEquals(WidgetPinPhase.UNCONFIRMED,model.phase)
        model.refresh {error("read failed")};assertEquals(WidgetPinPhase.FAILED,model.phase)
    }
}
