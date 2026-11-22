package com.step.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.nio.file.Path

@Composable internal fun FilePathField(value:String,onChange:(String)->Unit,label:String,save:Boolean=false,enabled:Boolean=true) {
    var failure by remember {mutableStateOf<String?>(null)}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Field(value,onChange,label,Modifier.weight(1f),enabled=enabled)
        StepOutlinedButton({
            runCatching {
                val owner=KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
                val mode=if(save)FileDialog.SAVE else FileDialog.LOAD
                val dialog=when(owner){is Dialog->FileDialog(owner,"Step 内容备份",mode);is Frame->FileDialog(owner,"Step 内容备份",mode);else->FileDialog(null as Frame?,"Step 内容备份",mode)}
                try {
                    if(save)dialog.file="step-backup-${java.time.LocalDate.now()}.json"
                    value.takeIf {it.isNotBlank()}?.let {runCatching {Path.of(it)}.getOrNull()}?.let {path->dialog.directory=path.parent?.toString();dialog.file=path.fileName?.toString()}
                    dialog.isVisible=true
                    dialog.file?.let {file->onChange(Path.of(dialog.directory,file).toString())}
                    failure=null
                } finally {dialog.dispose()}
            }.onFailure {failure="文件选择器暂不可用，可以直接输入路径。"}
        },enabled=enabled,modifier=Modifier.padding(top=23.dp)){Text(if(save)"选择位置"else"选择文件")}
    }
    failure?.let {Text(it,color=StepMuted)}
}
