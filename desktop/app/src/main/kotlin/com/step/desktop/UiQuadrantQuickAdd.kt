package com.step.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.pare.app.*

/** A draft belongs to its quadrant; it is cleared only after durable local storage succeeds. */
internal class QuadrantInputDraft {
    var value by mutableStateOf(TextFieldValue())
    var id=uid()
    var focused by mutableStateOf(false)
}

@Composable internal fun QuadrantQuickAdd(quadrant:Int,draft:QuadrantInputDraft,work:UiWork) {
    val value=draft.value
    DisposableEffect(draft){onDispose {draft.focused=false}}
    fun save() {
        // Read the live draft: a second Return may arrive before Compose replaces the
        // old callback after the first save has already cleared its text.
        val current=draft.value
        if(work.busy||current.composition!=null||current.text.isBlank())return
        val submitted=current.text;val submittedId=draft.id
        work.edit({draft.value=TextFieldValue();draft.id=uid()}) {state->
            checkedContentSave(state,null,"task",submittedId,submitted,"",quadrant,ContentEditBaseline.from(null))
        }
    }
    Column(Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value,{draft.value=it},singleLine=true,readOnly=work.busy,
            modifier=Modifier.fillMaxWidth().heightIn(min=40.dp).testTag("quadrant_add_$quadrant").onFocusChanged {draft.focused=it.isFocused}.onPreviewKeyEvent {
                if(it.type==KeyEventType.KeyDown&&it.key==Key.Enter&&value.composition==null){save();true}else false
            },
            placeholder={Text("添加任务",style=MaterialTheme.typography.bodyMedium)},
            leadingIcon={Icon(Icons.Outlined.Add,null,Modifier.size(18.dp),tint=LocalStepPalette.current.quadrants[quadrant])},
            textStyle=MaterialTheme.typography.bodyMedium,shape=MaterialTheme.shapes.small,
            keyboardActions=KeyboardActions(onDone={save()}),
            colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=StepLine,unfocusedContainerColor=LocalStepPalette.current.field)
        )
        if(work.failed)WorkFeedback(work)
    }
}
