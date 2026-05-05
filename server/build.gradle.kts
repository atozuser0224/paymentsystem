val exposed_version: String by project
val h2_version: String by project
val kotlin_version: String by project
val logback_version: String by project

plugins {
    alias(libs.plugins.kotlinJvm) // 또는 kotlin("jvm") version "2.3.0"
    alias(libs.plugins.ktor)      // id("io.ktor.plugin") version "3.4.2"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0"
    application
}

group = "org.gang.paymentsystem"
version = "1.0.0"

application {
    mainClass.set("org.gang.paymentsystem.ApplicationKt")

    // EngineMain 사용 시 설정
    // mainClass.set("io.ktor.server.netty.EngineMain")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // 공통 모듈 라이브러리 연동
    implementation(projects.shared)

    // Ktor Server Core & Netty
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")

    // API Documentation (Swagger/OpenAPI)
    implementation("io.ktor:ktor-server-swagger")
    implementation("io.ktor:ktor-server-routing-openapi")
    implementation("io.ktor:ktor-server-routing-openapi:3.4.2")

    // HTML DSL for admin dashboard
    implementation("io.ktor:ktor-server-html-builder")
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.9.1")

    // CORS
    implementation("io.ktor:ktor-server-cors")

    // Content Negotiation & Serialization
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")

    // Database (Exposed & H2)
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("com.h2database:h2:$h2_version")

    // Logging & Config
    implementation(libs.logback)
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("io.ktor:ktor-server-config-yaml")

    // Security
    implementation("org.mindrot:jbcrypt:0.4")

    // Test
    testImplementation(libs.ktor.serverTestHost)
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation(libs.kotlin.testJunit)
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
}

ktor {
    openApi {
        enabled = true
        codeInferenceEnabled = true
        onlyCommented = false
    }
}