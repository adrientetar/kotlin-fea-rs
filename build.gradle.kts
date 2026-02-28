plugins {
    alias(libs.plugins.kotlin.jvm)
    id("java-library")
    alias(libs.plugins.maven.publish)
}

val GROUP: String by project
val VERSION_NAME: String by project

group = GROUP
// Pass -Prelease to publish the clean version; otherwise builds use -SNAPSHOT
// to avoid conflicting with Maven Central releases.
version = if (providers.gradleProperty("release").isPresent) VERSION_NAME else "$VERSION_NAME-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    sourceSets {
        main {
            kotlin.srcDirs("bindings")
        }
    }
}

dependencies {
    implementation(libs.jna)

    testImplementation(kotlin("test"))
    testImplementation(libs.truth)
}

tasks.test {
    useJUnitPlatform()
}

// Platform detection
val osName = providers.systemProperty("os.name").map { it.lowercase() }
val osArch = providers.systemProperty("os.arch").map { it.lowercase() }

val currentOs = osName.map { name ->
    when {
        name.contains("mac") || name.contains("darwin") -> "macos"
        name.contains("windows") -> "windows"
        else -> "linux"
    }
}

val currentArch = osArch.map { arch ->
    when {
        arch == "aarch64" || arch == "arm64" -> "arm64"
        else -> "x64"
    }
}

val platformClassifier = providers.zip(currentOs, currentArch) { os, arch -> "$os-$arch" }

val nativeLibName = currentOs.map { os ->
    when (os) {
        "windows" -> "kotlin_fea_rs.dll"
        "macos" -> "libkotlin_fea_rs.dylib"
        else -> "libkotlin_fea_rs.so"
    }
}

val nativeLibExtension = currentOs.map { os ->
    when (os) {
        "windows" -> "dll"
        "macos" -> "dylib"
        else -> "so"
    }
}

// Directory for platform-specific native library
val nativeLibDir = layout.buildDirectory.dir(platformClassifier.map { "native/$it" })

// Cargo - resolve from PATH (optionally override via CARGO_PATH)
val cargoPath = providers.environmentVariable("CARGO_PATH").orElse("cargo")

// Custom tasks for building Rust library and generating Kotlin bindings
tasks.register<Exec>("buildRustLibrary") {
    description = "Build the Rust library"
    executable = cargoPath.get()
    args("build", "--release")
    workingDir = projectDir
}

tasks.register<Exec>("generateKotlinBindings") {
    description = "Generate Kotlin bindings using UniFFI"
    dependsOn("buildRustLibrary")

    val outputDir = file("bindings")
    outputs.dir(outputDir)

    doFirst {
        outputDir.mkdirs()
    }

    val libPath = "target/release/${nativeLibName.get()}"
    commandLine(
        cargoPath.get(), "run",
        "--features=uniffi/cli", "--bin", "uniffi-bindgen", "generate",
        "--library", libPath,
        "--language", "kotlin",
        "--no-format",
        "--config", "uniffi.toml",
        "--out-dir", "bindings"
    )
    workingDir = projectDir
}

tasks.register<Copy>("copyNativeLibrary") {
    description = "Copy the built native library to platform-specific directory"
    dependsOn("buildRustLibrary")

    from({ "target/release/${nativeLibName.get()}" })
    into(nativeLibDir)
}

tasks.register("prepareKotlin") {
    description = "Prepare Kotlin sources and native libraries"
    dependsOn("generateKotlinBindings", "copyNativeLibrary")
}

tasks.named("compileKotlin") {
    dependsOn("prepareKotlin")
}

tasks.named("compileTestKotlin") {
    dependsOn("prepareKotlin")
}

// For tests, we need the native library in a location JNA can find
tasks.register<Copy>("copyNativeLibraryForTest") {
    description = "Copy native library to resources for testing"
    dependsOn("buildRustLibrary")
    from({ "target/release/${nativeLibName.get()}" })
    into(layout.buildDirectory.dir("resources/main"))
}

tasks.named("processResources") {
    dependsOn("copyNativeLibraryForTest")
}

tasks.test {
    dependsOn("copyNativeLibraryForTest")
}

tasks.withType<Jar>().configureEach {
    if (name == "sourcesJar") {
        dependsOn("generateKotlinBindings")
    }
}

// Base JAR with only Kotlin code (no native libraries)
tasks.jar {
    archiveClassifier.set("")
    exclude("**/*.so", "**/*.dll", "**/*.dylib")
}

// Platform-specific JAR containing only the native library
val nativeJar by tasks.registering(Jar::class) {
    dependsOn("copyNativeLibrary")
    archiveClassifier.set(platformClassifier)
    from(nativeLibDir) {
        include("*.${nativeLibExtension.get()}")
    }
}

// Platform-specific native JARs (used by CI publish job when multiple native libs are present)
val currentNativeClassifier = platformClassifier.get()
val allNativeClassifiers = listOf(
    "macos-arm64",
    "macos-x64",
    "linux-x64",
    "linux-arm64",
    "windows-x64",
    "windows-arm64",
)

val nativeJarsByClassifier: Map<String, TaskProvider<Jar>> =
    allNativeClassifiers
        // The current platform is already covered by `nativeJar`; avoid duplicate artifacts and task wiring issues.
        .filter { it != currentNativeClassifier }
        .associateWith { classifier ->
    tasks.register<Jar>("nativeJar_${classifier.replace('-', '_')}") {
        archiveClassifier.set(classifier)
        val dir = layout.buildDirectory.dir("native/$classifier")
        from(dir)
        // Only publish if the files exist (local dev builds only one)
        onlyIf { dir.get().asFile.exists() && (dir.get().asFile.listFiles()?.isNotEmpty() == true) }
    }
}

tasks.named("build") {
    dependsOn(nativeJar)
}

// Hook into vanniktech maven-publish to add native JARs as additional artifacts
publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(nativeJar)
        // Only publish additional platform artifacts when CI has produced them.
        if (providers.environmentVariable("CI").isPresent) {
            nativeJarsByClassifier.values.forEach { artifact(it) }
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    // Signing is required for Maven Central, but should not block local development.
    if (providers.environmentVariable("CI").isPresent) {
        signAllPublications()
    }
}
