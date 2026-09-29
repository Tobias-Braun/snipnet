plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    jacoco
}

kotlin {
    jvmToolchain(21)

    // Desktop is the only target for now; Android and iOS join later on the same common code.
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            api(libs.sqldelight.runtime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotest.property)
        }
        // The SQLite JDBC driver is JVM-only; other targets bring their own driver later.
        val desktopMain by getting {
            dependencies {
                api(libs.sqldelight.sqlite.driver)
            }
        }
    }
}

// SQLDelight generates Kotlin into the build directory in its own style; only hand-written sources are linted.
ktlint {
    filter {
        exclude { it.file.path.contains("/build/generated/") }
    }
}

sqldelight {
    databases {
        create("SnipnetDatabase") {
            packageName.set("app.snipnet.shared.store.db")
        }
    }
}

// The editing core is pure logic that mobile will share later, so its line coverage is enforced in `check`.
// JaCoCo is wired by hand because the Kotlin Multiplatform plugin does not expose the desktop target's class
// directories to it.
val editingClasses =
    layout.buildDirectory
        .dir("classes/kotlin/desktop/main")
        .map { it.asFileTree.matching { include("app/snipnet/shared/editing/**") } }

val execFile = layout.buildDirectory.file("jacoco/desktopTest.exec")

tasks.named<Test>("desktopTest") {
    extensions.configure<JacocoTaskExtension> {
        destinationFile = execFile.get().asFile
    }
}

val coverageReport =
    tasks.register<JacocoReport>("editingCoverageReport") {
        dependsOn("desktopTest")
        executionData(execFile)
        classDirectories.setFrom(editingClasses)
        sourceDirectories.setFrom(layout.projectDirectory.dir("src/commonMain/kotlin"))
        reports {
            xml.required = true
            html.required = true
        }
    }

val coverageVerification =
    tasks.register<JacocoCoverageVerification>("editingCoverageVerification") {
        dependsOn("desktopTest")
        executionData(execFile)
        classDirectories.setFrom(editingClasses)
        sourceDirectories.setFrom(layout.projectDirectory.dir("src/commonMain/kotlin"))
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    minimum = "0.90".toBigDecimal()
                }
            }
        }
    }

tasks.named("check") {
    dependsOn(coverageVerification)
}
