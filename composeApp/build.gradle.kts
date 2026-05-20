import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0"
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm()

    js {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)

        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(projects.shared)

            // Ktor Core & Serialization
            implementation("io.ktor:ktor-client-core:3.4.2")
            implementation("io.ktor:ktor-client-content-negotiation:3.4.2")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }

        jvmMain.dependencies {
            // ARM64 대응: Windows ARM64만 compose.desktop.windows_arm64 아티팩트가
            // 존재하지 않으므로 windows_x64로 폴백 (x86_64 JDK + Windows 에뮬레이션)
            // Linux ARM64, macOS ARM64는 currentOs가 올바른 아티팩트를 찾음
            val osName = System.getProperty("os.name").lowercase()
            val osArch = System.getProperty("os.arch").lowercase()
            if (osName.contains("win") && (osArch.contains("aarch64") || osArch.contains("arm"))) {
                implementation(compose.desktop.windows_x64)
            } else {
                implementation(compose.desktop.currentOs)
            }
            implementation(libs.kotlinx.coroutinesSwing)

            // 🔥 이 부분이 반드시 추가되어야 합니다 (데스크톱/Hot Reload용 엔진)
            implementation("io.ktor:ktor-client-cio:3.4.2")

            // Serial port for Arduino communication (ARM64 Windows 네이티브 라이브러리 포함)
            implementation("com.fazecast:jSerialComm:2.11.0")
        }

        // 안드로이드에서 실행할 때 필요한 엔진도 확인하세요
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            // 안드로이드용 엔진 (보통 OkHttp를 많이 씁니다)
            implementation("io.ktor:ktor-client-okhttp:3.4.2")
            // Google Play Services Location
            implementation("com.google.android.gms:play-services-location:21.3.0")
        }
    }
}

android {
    namespace = "org.gang.paymentsystem"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.gang.paymentsystem"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "org.gang.paymentsystem.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "org.gang.paymentsystem"
            packageVersion = "1.0.0"
        }
    }
}
