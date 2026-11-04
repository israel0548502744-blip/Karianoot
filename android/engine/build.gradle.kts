// The speech engine: plain Kotlin, no Android classes, so CI can test it on the JVM
// against the output of the original Python packages (see tools/reference.py).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    // Provided at runtime: by onnxruntime-android-qnn in the app, by the desktop build in tests.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.29.0")
    // org.json is part of Android; the JVM tests need the jar.
    compileOnly("org.json:json:20240303")

    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.29.0")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    environment("KARIANOOT_MODELS", System.getenv("KARIANOOT_MODELS") ?: "")
    environment("KARIANOOT_GOLDEN", System.getenv("KARIANOOT_GOLDEN") ?: "")
    testLogging { events("passed", "failed", "standard_out", "standard_error"); showStandardStreams = true }
    maxHeapSize = "6g"
}
