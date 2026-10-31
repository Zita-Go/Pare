package com.pare.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal data class PareRunningAction(val label:String,val onClick:()->Unit)
internal val LocalPareRunningAction=staticCompositionLocalOf<PareRunningAction?> {null}

/** Shared roles: a page goes back; a short action may close. Real labels remain accessible. */
@Composable
internal fun ParePageHeader(title:String,onBack:()->Unit,modifier:Modifier=Modifier,
    backLabel:String="返回",enabled:Boolean=true,backModifier:Modifier=Modifier,actions:@Composable RowScope.()->Unit={}) {
    Row(modifier.fillMaxWidth().heightIn(min=56.dp).padding(start=4.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick=onBack,enabled=enabled,modifier=Modifier.size(48.dp).then(backModifier)) {
            Icon(Icons.Default.ArrowBack,backLabel,Modifier.size(22.dp))
        }
        Text(title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f).padding(vertical=8.dp))
        LocalPareRunningAction.current?.let {running ->
            TextButton(onClick=running.onClick,shape=MaterialTheme.shapes.small,modifier=Modifier.heightIn(min=48.dp).testTag("page_execution_status"),contentPadding=PaddingValues(horizontal=8.dp)) {Text(running.label,style=MaterialTheme.typography.labelLarge)}
        }
        actions()
    }
}

/** Ordinary selections use green. Gold remains an explicit daily-priority treatment. */
@Composable
internal fun pareChoiceColors():SelectableChipColors=FilterChipDefaults.filterChipColors(
    selectedContainerColor=MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor=MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor=MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTrailingIconColor=MaterialTheme.colorScheme.onPrimaryContainer
)

@Composable internal fun pareActionShape():Shape=MaterialTheme.shapes.medium
