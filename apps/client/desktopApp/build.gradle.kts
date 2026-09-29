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
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

compose.desktop {
    application {
        mainClass = "app.snipnet.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Snipnet"
            packageVersion = "1.0.0"
        }
    }
}

tasks.withType<Test>().configureEach {
    // Native loading failures (missing system libraries on a CI image) only show their cause in the full trace.
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
