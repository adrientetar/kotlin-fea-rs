package io.github.adrientetar.fears

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class FeaCompilerTest {

    @Test
    fun `empty FEA produces no errors`() {
        val result = compileFea("", listOf(".notdef"))
        
        assertThat(result.diagnostics.filter { it.severity == Severity.ERROR }).isEmpty()
    }

    @Test
    fun `simple GSUB ligature produces GSUB table`() {
        val fea = """
            feature liga {
                sub f i by fi;
            } liga;
        """.trimIndent()
        
        val glyphs = listOf(".notdef", "f", "i", "fi")
        val result = compileFea(fea, glyphs)
        
        val errors = result.diagnostics.filter { it.severity == Severity.ERROR }
        assertThat(errors).isEmpty()
        assertThat(result.tables).isNotNull()
        assertThat(result.tables?.gsub).isNotNull()
    }

    @Test
    fun `simple GPOS kerning produces GPOS table`() {
        val fea = """
            feature kern {
                pos A V -50;
            } kern;
        """.trimIndent()
        
        val glyphs = listOf(".notdef", "A", "V")
        val result = compileFea(fea, glyphs)
        
        val errors = result.diagnostics.filter { it.severity == Severity.ERROR }
        assertThat(errors).isEmpty()
        assertThat(result.tables).isNotNull()
        assertThat(result.tables?.gpos).isNotNull()
    }

    @Test
    fun `syntax error produces error diagnostic`() {
        val fea = """
            feature liga {
                sub f i by
            } liga;
        """.trimIndent()
        
        val glyphs = listOf(".notdef", "f", "i")
        val result = compileFea(fea, glyphs)
        
        val errors = result.diagnostics.filter { it.severity == Severity.ERROR }
        assertThat(errors).isNotEmpty()
        assertThat(result.tables).isNull()
    }

    @Test
    fun `undefined glyph produces error`() {
        val fea = """
            feature liga {
                sub x y by xy;
            } liga;
        """.trimIndent()
        
        // Glyphs referenced in FEA don't exist
        val glyphs = listOf(".notdef")
        val result = compileFea(fea, glyphs)
        
        val errors = result.diagnostics.filter { it.severity == Severity.ERROR }
        assertThat(errors).isNotEmpty()
    }

    @Test
    fun `error diagnostic has source range`() {
        val fea = """
            feature liga {
                sub unknown_glyph by other_unknown;
            } liga;
        """.trimIndent()
        
        val glyphs = listOf(".notdef")
        val result = compileFea(fea, glyphs)
        
        val errors = result.diagnostics.filter { it.severity == Severity.ERROR }
        assertThat(errors).isNotEmpty()
        
        // At least one error should have a source range
        val errorWithRange = errors.find { it.range != null }
        assertThat(errorWithRange).isNotNull()
        assertThat(errorWithRange?.range?.startLine).isAtLeast(1u)
    }

    @Test
    fun `version returns non-empty string`() {
        val version = feaRsVersion()
        assertThat(version).isNotEmpty()
    }

    @Test
    fun `complex FEA with multiple features`() {
        val fea = """
            @vowels = [a e i o u];
            
            feature liga {
                sub f i by fi;
                sub f l by fl;
            } liga;
            
            feature kern {
                pos A V -50;
                pos V A -50;
            } kern;
        """.trimIndent()
        
        val glyphs = listOf(".notdef", "a", "e", "i", "o", "u", "f", "l", "fi", "fl", "A", "V")
        val result = compileFea(fea, glyphs)
        
        val errors = result.diagnostics.filter { it.severity == Severity.ERROR }
        assertThat(errors).isEmpty()
        assertThat(result.tables).isNotNull()
        assertThat(result.tables?.gsub).isNotNull()
        assertThat(result.tables?.gpos).isNotNull()
    }
}
