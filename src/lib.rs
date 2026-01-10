use std::path::Path;
use std::sync::Arc;

use fea_rs::compile::{NopFeatureProvider, NopVariationInfo, Opts};
use fea_rs::parse::SourceLoadError;

/// Severity level for diagnostics
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Severity {
    Error,
    Warning,
}

/// Source range for a diagnostic
#[derive(Debug, Clone, uniffi::Record)]
pub struct SourceRange {
    /// 1-based start line
    pub start_line: u32,
    /// 1-based start column
    pub start_column: u32,
    /// 1-based end line
    pub end_line: u32,
    /// 1-based end column
    pub end_column: u32,
}

/// A diagnostic message from the FEA compiler
#[derive(Debug, Clone, uniffi::Record)]
pub struct Diagnostic {
    /// Severity of the diagnostic
    pub severity: Severity,
    /// Human-readable message
    pub message: String,
    /// Source range (if available)
    pub range: Option<SourceRange>,
}

/// Compiled OpenType feature tables
#[derive(Debug, Clone, uniffi::Record)]
pub struct FeatureTables {
    /// GSUB table bytes (substitution rules)
    pub gsub: Option<Vec<u8>>,
    /// GPOS table bytes (positioning rules)
    pub gpos: Option<Vec<u8>>,
    /// GDEF table bytes (glyph definitions)
    pub gdef: Option<Vec<u8>>,
}

/// Result of FEA compilation
#[derive(Debug, Clone, uniffi::Record)]
pub struct CompileResult {
    /// Compiled tables (None if there were errors)
    pub tables: Option<FeatureTables>,
    /// Diagnostics (errors and warnings)
    pub diagnostics: Vec<Diagnostic>,
}

/// Errors that can occur during FEA compilation
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FeaCompileError {
    #[error("Internal error: {reason}")]
    InternalError { reason: String },
}

/// Convert byte offset to line/column position
fn offset_to_position(source: &str, offset: usize) -> (u32, u32) {
    let mut line = 1u32;
    let mut col = 1u32;
    
    for (i, c) in source.char_indices() {
        if i >= offset {
            break;
        }
        if c == '\n' {
            line += 1;
            col = 1;
        } else {
            col += 1;
        }
    }
    
    (line, col)
}

/// Convert fea-rs diagnostics to our format
fn convert_diagnostics(
    diag_set: &fea_rs::DiagnosticSet,
    source: &str,
) -> Vec<Diagnostic> {
    diag_set.diagnostics().iter().map(|d| {
        let severity = if d.is_error() {
            Severity::Error
        } else {
            Severity::Warning
        };
        let span = d.span();
        let (start_line, start_column) = offset_to_position(source, span.start);
        let (end_line, end_column) = offset_to_position(source, span.end);
        Diagnostic {
            severity,
            message: d.text().to_string(),
            range: Some(SourceRange {
                start_line,
                start_column,
                end_line,
                end_column,
            }),
        }
    }).collect()
}

/// A custom source resolver that serves FEA from memory
struct InMemoryResolver {
    source: Arc<str>,
}

impl fea_rs::parse::SourceResolver for InMemoryResolver {
    fn get_contents(&self, path: &Path) -> Result<Arc<str>, SourceLoadError> {
        // We only handle the root path, no includes
        if path == Path::new("memory.fea") {
            Ok(self.source.clone())
        } else {
            Err(SourceLoadError::new(
                path.to_path_buf(),
                "includes are not supported in memory compilation",
            ))
        }
    }
}

/// Compile FEA source code to OpenType tables
///
/// # Arguments
/// * `fea_source` - The FEA feature code as a string
/// * `glyph_order` - List of glyph names in the order they appear in the font
///
/// # Returns
/// A CompileResult containing the compiled tables (if successful) and any diagnostics
#[uniffi::export]
pub fn compile_fea(
    fea_source: String,
    glyph_order: Vec<String>,
) -> Result<CompileResult, FeaCompileError> {
    // Create a GlyphMap from glyph order
    let glyph_map = fea_rs::GlyphMap::from_iter(glyph_order.iter().map(|s| s.as_str()));

    // Create the resolver
    let source: Arc<str> = fea_source.clone().into();
    let resolver = InMemoryResolver { source };

    // Use the Compiler API
    let compile_result = fea_rs::Compiler::<NopFeatureProvider, NopVariationInfo>::new(
        "memory.fea",
        &glyph_map,
    )
    .with_resolver(resolver)
    .with_opts(Opts::default())
    .compile();

    match compile_result {
        Ok(compilation) => {
            // Build the tables using dump_table
            let gsub = compilation.gsub.as_ref().and_then(|table| {
                write_fonts::dump_table(table).ok()
            });
            let gpos = compilation.gpos.as_ref().and_then(|table| {
                write_fonts::dump_table(table).ok()
            });
            let gdef = compilation.gdef.as_ref().and_then(|table| {
                write_fonts::dump_table(table).ok()
            });

            Ok(CompileResult {
                tables: Some(FeatureTables { gsub, gpos, gdef }),
                diagnostics: vec![],
            })
        }
        Err(err) => {
            // Convert the error to diagnostics
            let diagnostics = match err {
                fea_rs::compile::error::CompilerError::SourceLoad(e) => {
                    vec![Diagnostic {
                        severity: Severity::Error,
                        message: e.to_string(),
                        range: None,
                    }]
                }
                fea_rs::compile::error::CompilerError::ParseFail(diag_set) => {
                    convert_diagnostics(&diag_set, &fea_source)
                }
                fea_rs::compile::error::CompilerError::ValidationFail(diag_set) => {
                    convert_diagnostics(&diag_set, &fea_source)
                }
                fea_rs::compile::error::CompilerError::CompilationFail(diag_set) => {
                    convert_diagnostics(&diag_set, &fea_source)
                }
                fea_rs::compile::error::CompilerError::WriteFail(e) => {
                    vec![Diagnostic {
                        severity: Severity::Error,
                        message: e.to_string(),
                        range: None,
                    }]
                }
            };

            Ok(CompileResult {
                tables: None,
                diagnostics,
            })
        }
    }
}

/// Get the version of the kotlin-fea-rs library
#[uniffi::export]
pub fn fea_rs_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

// Generate the UniFFI scaffolding
uniffi::setup_scaffolding!();

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_empty_fea() {
        let result = compile_fea("".to_string(), vec![".notdef".to_string()]).unwrap();
        // Empty FEA should compile without errors
        let errors: Vec<_> = result.diagnostics.iter()
            .filter(|d| d.severity == Severity::Error)
            .collect();
        assert!(errors.is_empty(), "Unexpected errors: {:?}", errors);
    }

    #[test]
    fn test_simple_gsub() {
        let fea = r#"
feature liga {
    sub f i by fi;
} liga;
        "#;
        let glyphs = vec![
            ".notdef".to_string(),
            "f".to_string(),
            "i".to_string(),
            "fi".to_string(),
        ];
        let result = compile_fea(fea.to_string(), glyphs).unwrap();
        
        // Should have no errors
        let errors: Vec<_> = result.diagnostics.iter()
            .filter(|d| d.severity == Severity::Error)
            .collect();
        assert!(errors.is_empty(), "Unexpected errors: {:?}", errors);
        
        // Should produce GSUB table
        assert!(result.tables.is_some());
        let tables = result.tables.unwrap();
        assert!(tables.gsub.is_some());
    }

    #[test]
    fn test_syntax_error() {
        let fea = r#"
feature liga {
    sub f i by
} liga;
        "#;
        let glyphs = vec![
            ".notdef".to_string(),
            "f".to_string(),
            "i".to_string(),
        ];
        let result = compile_fea(fea.to_string(), glyphs).unwrap();
        
        // Should have parse errors
        let errors: Vec<_> = result.diagnostics.iter()
            .filter(|d| d.severity == Severity::Error)
            .collect();
        assert!(!errors.is_empty(), "Expected parse errors");
        
        // Should not produce tables
        assert!(result.tables.is_none());
    }

    #[test]
    fn test_simple_gpos() {
        let fea = r#"
feature kern {
    pos A V -50;
} kern;
        "#;
        let glyphs = vec![
            ".notdef".to_string(),
            "A".to_string(),
            "V".to_string(),
        ];
        let result = compile_fea(fea.to_string(), glyphs).unwrap();
        
        // Should have no errors
        let errors: Vec<_> = result.diagnostics.iter()
            .filter(|d| d.severity == Severity::Error)
            .collect();
        assert!(errors.is_empty(), "Unexpected errors: {:?}", errors);
        
        // Should produce GPOS table
        assert!(result.tables.is_some());
        let tables = result.tables.unwrap();
        assert!(tables.gpos.is_some());
    }

    #[test]
    fn test_undefined_glyph() {
        let fea = r#"
feature liga {
    sub x y by xy;
} liga;
        "#;
        // Glyphs referenced in FEA don't exist in glyph order
        let glyphs = vec![".notdef".to_string()];
        let result = compile_fea(fea.to_string(), glyphs).unwrap();
        
        // Should have errors about undefined glyphs
        let errors: Vec<_> = result.diagnostics.iter()
            .filter(|d| d.severity == Severity::Error)
            .collect();
        assert!(!errors.is_empty(), "Expected errors about undefined glyphs");
    }
}
