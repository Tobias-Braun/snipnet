import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

/**
 * JavaCPP platform classifiers of the natives we ship: macOS arm64/x64, Windows x64 and Linux x64. The ffmpeg
 * natives use the `-gpl` flavour because the proxy transcode and the export need libx264, which the LGPL-only
 * flavour lacks (see docs/adr/0001-video-engine.md). The `-gpl` suffix is selected at runtime by `FfmpegRuntime`.
 */
val allNativePlatforms = listOf("macosx-arm64", "macosx-x86_64", "windows-x86_64", "linux-x86_64")

/**
 * The platform of the machine running Gradle, so a normal build (and the packaged app built on that machine) only
 * pulls in its own natives instead of all four (~130 MB). `-PallNativePlatforms` adds every supported platform, for
 * example when one machine builds the installers for all systems.
 */
val hostNativePlatform: String =
    run {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val isArm = arch == "aarch64" || arch == "arm64"
        when {
            os.contains("mac") -> if (isArm) "macosx-arm64" else "macosx-x86_64"
            os.contains("win") -> "windows-x86_64"
            else -> "linux-x86_64"
        }
    }

val nativePlatforms =
    if (providers.gradleProperty("allNativePlatforms").isPresent) allNativePlatforms else listOf(hostNativePlatform)

dependencies {
    implementation(project(":shared"))

    // javacv itself is only needed for FFmpegFrameGrabber and the Java2D frame converter. Its POM drags in the
    // Java wrappers of OpenCV, OpenBLAS, and a dozen other libraries that nothing here uses, so they are excluded.
    implementation(libs.javacv) { isTransitive = false }
    implementation(libs.javacpp)
    implementation(libs.ffmpeg)
    nativePlatforms.forEach { platform ->
        runtimeOnly("${libs.javacpp.get().module}:${libs.javacpp.get().versionConstraint.requiredVersion}:$platform")
        runtimeOnly("${libs.ffmpeg.get().module}:${libs.ffmpeg.get().versionConstraint.requiredVersion}:$platform-gpl")
    }

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.foundation)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.ktor.client.cio)

    testImplementation(kotlin("test"))
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

/**
 * Version of the installers, taken from the git tag: the release workflow passes `-PappVersion=<tag>` (for example
 * `v1.2.3`). jpackage only accepts numeric `MAJOR.MINOR.PATCH`, so the leading `v` and any pre-release suffix such
 * as `-rc.1` are dropped. Local builds without the property use `1.0.0`.
 */
val installerVersion: String =
    providers.gradleProperty("appVersion").orNull?.let { tag ->
        Regex("""^v?(\d+\.\d+\.\d+)""").find(tag)?.groupValues?.get(1)
            ?: throw GradleException("appVersion '$tag' must look like v1.2.3")
    } ?: "1.0.0"

compose.desktop {
    application {
        mainClass = "app.snipnet.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Snipnet"
            packageVersion = installerVersion
            // Installers are unsigned for now; signing and notarization are tracked separately.
            macOS {
                iconFile.set(project.file("packaging/icon.icns"))
                // jpackage rejects a macOS app whose major version is 0, so 0.x tags are packaged as 1.x there.
                packageVersion = installerVersion.replace(Regex("^0\\."), "1.")
            }
            windows {
                iconFile.set(project.file("packaging/icon.ico"))
                menuGroup = "Snipnet"
                // Fixed so that installing a newer version upgrades the previous one instead of installing beside it.
                upgradeUuid = "6f4d1c2e-8a3b-4f57-9d0e-5b7a2c91e348"
            }
            linux {
                iconFile.set(project.file("packaging/icon.png"))
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    // Native loading failures (missing system libraries on a CI image) only show their cause in the full trace.
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
