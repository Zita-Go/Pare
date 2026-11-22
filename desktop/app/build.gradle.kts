import org.jetbrains.compose.desktop.application.dsl.TargetFormat
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
tasks.test { systemProperty("java.awt.headless", "true") }
compose.desktop {
    application {
        mainClass = "com.step.desktop.MainKt"
        jvmArgs("-Dstep.version=0.2.0")
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "Step"
            packageVersion = "0.2.0"
            description = "Step — one step at a time"
            vendor = "Step"
            modules("java.net.http", "java.sql", "jdk.crypto.ec", "jdk.unsupported")
            windows {
                iconFile.set(project.file("src/main/resources/step.ico"))
                menuGroup = "Step"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "e7044f96-d76c-4bce-a17b-9d8eb6b39984"
            }
        }
    }
}
