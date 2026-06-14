import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // compose.* accessors are plugin-managed (version = the Compose MP plugin, no drift).
            // They emit a deprecation notice in CMP 1.11.1, but the "direct coordinate" form
            // (org.jetbrains.compose.material3:material3:<v>) is not published — accessors are correct.
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation(libs.coroutines.core)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.coroutines.swing)
                implementation(libs.sqldelight.sqlite)
            }
        }
    }
}

sqldelight {
    databases {
        create("F1db") {
            packageName.set("dev.pitwall.db")
            verifyMigrations.set(false)
        }
    }
}

android {
    namespace = "dev.pitwall"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.pitwall"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidCompileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "dev.pitwall.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            // The packaged app ships a jlink-minimized JRE. jlink can't see the JDBC SQLite driver's
            // runtime use of java.sql.DriverManager, so without this the module is stripped and the
            // first DB query throws NoClassDefFoundError (java.sql.DriverManager) — fine under tests
            // and `run` (full JDK), but a hang in the installed .app. Bundle java.sql explicitly.
            modules("java.sql")
            packageName = "PitWall"
            // macOS/jpackage requires the major version to be >= 1; the app is conceptually 0.1.0
            // (see Android versionName + the git tag).
            packageVersion = "1.0.0"
            macOS {
                bundleID = "dev.pitwall"
            }
        }
    }
}
