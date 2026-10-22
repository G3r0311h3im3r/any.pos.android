import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Mock einer Epson-TSE (ePOS Device XML über TCP) für Entwicklung und Tests – kein Android, keine echte TSE.
// Start: ./gradlew :tse-mock:run --args="--port 8009 --scenario JTL_TAKEOVER"
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("de.anypos.tsemock.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
