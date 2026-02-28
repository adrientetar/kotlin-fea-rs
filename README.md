<div align="center">

kotlin-fea-rs
=============

**Kotlin bindings for [fea-rs](https://github.com/googlefonts/fontc/tree/main/fea-rs), a Rust library for compiling Adobe FEA feature code to OpenType tables.**

[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7f52ff.svg)](https://kotlinlang.org/)
[![Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE.txt)
[![Maven central](https://img.shields.io/maven-central/v/io.github.adrientetar/kotlin-fea-rs?color=brightgreen)](https://central.sonatype.com/artifact/io.github.adrientetar/kotlin-fea-rs)

</div>

[fea-rs](https://github.com/googlefonts/fontc/tree/main/fea-rs) is a Rust library for parsing and compiling Adobe OpenType Feature File (.fea) syntax into binary OpenType tables (GSUB, GPOS, GDEF).

This library provides Kotlin bindings to fea-rs using Mozilla's [UniFFI](https://github.com/mozilla/uniffi-rs) toolchain, enabling in-process FEA compilation for live preview shaping in font editors.

Maven library
-------------

```kotlin
repositories {
    mavenCentral()
}

val feaRsVersion = "0.1.0"
val feaRsTarget = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()

    val osPart = when {
        "mac" in os || "darwin" in os -> "macos"
        "windows" in os -> "windows"
        else -> "linux"
    }
    val archPart = when (arch) {
        "aarch64", "arm64" -> "arm64"
        else -> "x64"
    }
    "$osPart-$archPart"
}

dependencies {
    implementation("io.github.adrientetar:kotlin-fea-rs:$feaRsVersion")
    runtimeOnly("io.github.adrientetar:kotlin-fea-rs:$feaRsVersion:$feaRsTarget")
}
```

Usage
-----

```kotlin
import io.github.adrientetar.fears.*

// Define your glyph order (must match the font being shaped)
val glyphOrder = listOf(".notdef", "space", "A", "V", "f", "i", "fi")

// Your FEA feature code
val feaSource = """
    feature liga {
        sub f i by fi;
    } liga;

    feature kern {
        pos A V -50;
    } kern;
"""

// Compile the FEA code
val result = compileFea(feaSource, glyphOrder)

// Check for errors
val errors = result.diagnostics.filter { it.severity == Severity.ERROR }
if (errors.isNotEmpty()) {
    for (error in errors) {
        val location = error.range?.let { " at line ${it.startLine}" } ?: ""
        println("Error$location: ${error.message}")
    }
} else {
    // Use the compiled tables
    result.tables?.let { tables ->
        tables.gsub?.let { gsub ->
            println("GSUB table: ${gsub.size} bytes")
            // Pass to HarfBuzz for shaping...
        }
        tables.gpos?.let { gpos ->
            println("GPOS table: ${gpos.size} bytes")
        }
        tables.gdef?.let { gdef ->
            println("GDEF table: ${gdef.size} bytes")
        }
    }
}

// Warnings are also available
val warnings = result.diagnostics.filter { it.severity == Severity.WARNING }
for (warning in warnings) {
    println("Warning: ${warning.message}")
}
```

API Reference
-------------

### Main Function

```kotlin
fun compileFea(feaSource: String, glyphOrder: List<String>): CompileResult
```

Compiles FEA source code to OpenType tables.

**Parameters:**
- `feaSource` - The FEA feature code as a string
- `glyphOrder` - List of glyph names in the order they appear in the font

**Returns:** A `CompileResult` containing compiled tables and diagnostics.

### Data Types

```kotlin
data class CompileResult(
    val tables: FeatureTables?,      // null if compilation failed
    val diagnostics: List<Diagnostic>
)

data class FeatureTables(
    val gsub: ByteArray?,  // GSUB table bytes
    val gpos: ByteArray?,  // GPOS table bytes
    val gdef: ByteArray?   // GDEF table bytes
)

data class Diagnostic(
    val severity: Severity,
    val message: String,
    val range: SourceRange?
)

data class SourceRange(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int
)

enum class Severity { ERROR, WARNING }
```

Development
-----------

To build this library, you need:

- [Rust](https://rustup.rs/) (latest stable version) with the `cargo` build tool in your PATH
- JDK 21 or higher

```bash
# Build
./gradlew build

# Run Rust tests
cargo test

# Run Kotlin tests (after building bindings)
./gradlew test
```

Integration with HarfBuzz
-------------------------

The compiled tables can be passed directly to kotlin-harfbuzz for text shaping with custom OpenType features:

```kotlin
// After compiling FEA...
val feaTables = result.tables

// Pass to shaping engine
val tableOverrides = TableOverrides(
    gsub = feaTables?.gsub,
    gpos = feaTables?.gpos,
    gdef = feaTables?.gdef
)
shapingService.shape(text, font, tableOverrides)
```

