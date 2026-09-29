plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
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
