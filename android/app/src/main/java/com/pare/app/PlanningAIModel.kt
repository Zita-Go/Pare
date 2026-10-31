package com.pare.app

import androidx.compose.runtime.*

/** Retained by the same Activity model as service credentials and all other AI workflows. */
class PlanningAIDraft(val kind:PlanningAIKind,val subjectId:String?,val planId:String,initial:PlanningAIOptions) {
    var options by mutableStateOf(initial)
    var input by mutableStateOf<PlanningAIRequest?>(null)
    var proposal by mutableStateOf<PlanningAIProposal?>(null)
    var selected by mutableStateOf<Set<String>>(emptySet())
    var undo by mutableStateOf<PlanningAIUndo?>(null)
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")
    fun change(value:PlanningAIOptions) {if(!busy && undo==null) {options=value;input=null;proposal=null;selected=emptySet();status=""}}
}
