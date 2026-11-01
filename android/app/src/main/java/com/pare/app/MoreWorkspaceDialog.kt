package com.pare.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable fun MoreWorkspaceDialog(onSelect:(String)->Unit,onDismiss:()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text("更多")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            listOf("library" to "资料库 · 查找、归档与回收站","goals" to "长期目标","preferences" to "我的偏好","devices" to "设备运行状态","account" to "账号与同步","widget" to "添加桌面小组件").forEach {(key,label)->
                TextButton(onClick={onSelect(key)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("more_$key")) {Text(label,modifier=Modifier.fillMaxWidth())}
            }
        }
    },confirmButton={TextButton(onClick=onDismiss,modifier=Modifier.heightIn(min=48.dp)){Text("关闭")}})
}
