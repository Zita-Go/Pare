plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
}

kotlin { jvmToolchain(17) }

// A reviewed allowlist shares the exact Android domain implementation without
// pulling Android UI, lifecycle, SDK, credentials or platform storage into JVM.
val sharedDomainFiles = listOf(
    "CompletionActions",
    "ContentActions",
    "DayTaskActions",
    "DayTaskSelection",
    "DevicePresence",
    "DevicePresenceController",
    "DevicePresenceSequenceStore",
    "ExecutionController",
    "ExecutionState",
    "GoalsActions",
    "HttpSyncRemote",
    "IdeaManagementActions",
    "IdeaPresentation",
    "ImplementationPlanActions",
    "LibraryActions",
    "LocalTrash",
    "MobileAI",
    "MobileAIClient",
    "MobileReview",
    "NextStepActions",
    "OrganizeActions",
    "OrganizeUndo",
    "PayloadValidation",
    "PlanningAI",
    "PlanningAIClient",
    "PlanningAIGoalActions",
    "PlanningReview",
    "PreferencesActions",
    "Presentation",
    "ReminderPlanner",
    "ReviewAnalytics",
    "Session",
    "SupabaseApi",
    "SyncEngine",
    "SyncRequestLoop",
    "SyncRetryPolicy",
    "SyncState",
    "TaskMetadataActions",
    "TaskReminderActions",
    "TimelineActions",
    "TimelineDomain",
    "TimelineUndo",
    "TodayProjection",
    "VisualPresentation",
    "WorkflowActions",
    "WorkflowModels",
    "WorkflowPresentation",
    "WorkspaceChanges",
)
val sharedDomainTests = listOf(
    "AuthTest",
    "ContentActionsTest",
    "ContentEditGuardTest",
    "DayTaskActionsTest",
    "DayTaskSelectionTest",
    "DevicePresenceTest",
    "ExecutionControllerTest",
    "LibraryManagementActionsTest",
    "LocalTrashTest",
    "MobileAITest",
    "MobileWorkflowTest",
    "NextStepActionsTest",
    "OrganizeActionsTest",
    "OrganizeUndoTest",
    "PlanningAITest",
    "PlanningGoalAITest",
    "PresentationTest",
    "ReminderPlannerTest",
    "ResponseBoundsTest",
    "SyncEngineTest",
    "SyncRequestLoopTest",
    "SyncRetryPolicyTest",
    "SyncStateTest",
    "TimelineDomainTest",
    "TimelineMoveTest",
    "TimelineUndoTest",
    "TodayEffortTest",
    "TodayOverviewTest",
    "VisualPresentationTest",
    "Workflow09CompatibilityTest",
    "WorkflowActionsTest",
    "WorkspaceChangesTest",
)
kotlin.sourceSets {
    main {
        kotlin.srcDir("../../android/app/src/main/java")
        kotlin.setIncludes(sharedDomainFiles.map { "com/pare/app/$it.kt" })
    }
    test {
        kotlin.srcDir("../../android/app/src/test/java")
        kotlin.setIncludes(sharedDomainTests.map { "com/pare/app/$it.kt" })
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    useJUnit()
    val fixtures = layout.buildDirectory.dir("fictional-fixtures")
    systemProperty("pare.fixture.outputDir", fixtures.get().asFile.absolutePath)
    doFirst { fixtures.get().asFile.mkdirs() }
}
