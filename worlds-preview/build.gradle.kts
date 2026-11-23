// The app's pure-Kotlin parts on the plain JVM. Living Worlds: the world engine (LivingWorld.kt) and the
// analysis it hears (SoundShape.kt, BeatGrid.kt) are pure Kotlin, compiled
// here straight from the app's own source files, so the tests and the
// PNG/GIF previews run with the full JDK (AWT, ImageIO). Android unit tests
// compile against android.jar, which has no java.awt.
//
// Also the chapter reader (ChapterReader.kt, on TagReader.kt's ByteSource).
//
//   ./gradlew :worlds-preview:test     -> worlds-preview/build/living-worlds
plugins {
    id("org.jetbrains.kotlin.jvm")
}

// The JDK Gradle runs on (17 in CI); no toolchain download.
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    main {
        kotlin.srcDir("../app/src/main/java")
        kotlin.include(
            "com/media/app/LivingWorld.kt",
            "com/media/app/SoundShape.kt",
            "com/media/app/BeatGrid.kt",
            // Chapters for podcasts and audiobooks, on TagReader's byte source.
            "com/media/app/ChapterReader.kt",
            "com/media/app/TagReader.kt"
        )
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    systemProperty("java.awt.headless", "true")
    // The previews are the point: always run, never "up to date".
    outputs.upToDateWhen { false }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
