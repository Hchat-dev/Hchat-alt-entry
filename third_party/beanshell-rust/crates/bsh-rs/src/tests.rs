use super::*;

#[test]
fn ast_method_errors_do_not_quote_the_callers_source() {
    let engine = NEXT_ENGINE.fetch_add(1, Ordering::Relaxed);
    let ast = decode_ast(&compile_ast("fail() { missingFunction(); }").unwrap()).unwrap();
    eval_ast_session(engine, &ast, "saved-method.bsh").1.unwrap();
    let error = eval_session(engine, "fail();", None).1.unwrap_err();
    assert!(error.contains("saved-method.bsh:1:"), "{error}");
    assert!(!error.contains("\n    fail();"), "{error}");
    lock_engines().remove(&engine);
}


#[test]
fn ast_rejects_excessive_nesting_before_evaluation() {
    std::thread::Builder::new().stack_size(16 * 1024 * 1024).spawn(|| {
        let span = bsh_syntax::Span::new(0, 1, 1, 1);
        let mut stmt = bsh_syntax::Stmt::new(bsh_syntax::StmtKind::Empty, span);
        for _ in 0..160 {
            stmt = bsh_syntax::Stmt::new(bsh_syntax::StmtKind::Block(vec![stmt]), span);
        }
        let program = bsh_syntax::Program { stmts: vec![stmt], span };
        let mut bytes = AST_HEADER.to_vec();
        bytes.extend(ast_options().serialize(&program).unwrap());
        let error = decode_ast(&bytes).unwrap_err();
        assert!(error.contains("nesting"), "{error}");
    }).unwrap().join().unwrap();
}


#[test]
fn unset_removes_the_binding_instead_of_assigning_null() {
    let mut interpreter = Interpreter::detached();
    interpreter.eval_source("final int removed = 7; int retained = 8;").unwrap();
    interpreter.unset_variable("removed");
    assert!(interpreter.get_variable_value("removed").is_none());
    assert!(!interpreter.global.borrow().vars.contains_key("removed"));
    assert_eq!(interpreter.get_variable_value("retained").unwrap().to_display_string(), "8");
    interpreter.unset_variable("removed");
    interpreter.eval_source("String removed = \"replacement\";").unwrap();
    assert_eq!(interpreter.get_variable_value("removed").unwrap().to_display_string(), "replacement");
}

#[test]
fn named_source_and_ast_use_temporary_parent_directory() {
    let engine = NEXT_ENGINE.fetch_add(1, Ordering::Relaxed);
    let root = std::path::PathBuf::from(std::env::var("CARGO_TARGET_DIR").unwrap()).join(format!("source-test-{engine}"));
    std::fs::create_dir_all(root.join("plugin")).unwrap();
    std::fs::write(root.join("plugin/relative.bsh"), "int imported = 42; imported;").unwrap();
    let previous = with_engine(engine, |runtime| runtime.interpreter.cwd.clone());
    let source_name = root.join("plugin/main.bsh").to_string_lossy().into_owned();
    let source = "source(\"relative.bsh\"); imported;";
    assert_eq!(eval_source_session(engine, source, &source_name).1.unwrap().to_display_string(), "42");
    with_engine(engine, |runtime| assert_eq!(runtime.interpreter.cwd, previous));
    let program = decode_ast(&compile_ast(source).unwrap()).unwrap();
    assert_eq!(eval_ast_session(engine, &program, &source_name).1.unwrap().to_display_string(), "42");
    with_engine(engine, |runtime| {
        assert_eq!(runtime.interpreter.cwd, previous);
        assert!(runtime.interpreter.source_file.is_none());
        assert!(runtime.interpreter.source_text.is_none());
    });
    let error = eval_source_session(engine, "\nmissingFunction();", &source_name).1.unwrap_err();
    assert!(error.contains(&format!("{source_name}:2:")), "{error}");
    assert!(error.contains("missingFunction();"), "{error}");
    let syntax_error = eval_source_session(engine, "int bad = ;", &source_name).1.unwrap_err();
    assert!(syntax_error.contains(&source_name), "{syntax_error}");
    with_engine(engine, |runtime| assert_eq!(runtime.interpreter.cwd, previous));
    lock_engines().remove(&engine);
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn engine_context_is_restored_after_a_caught_panic() {
    let engine = NEXT_ENGINE.fetch_add(1, Ordering::Relaxed);
    let result = std::panic::catch_unwind(|| with_engine(engine, |_| panic!("test panic")));
    assert!(result.is_err());
    assert!(CURRENT_SESSION.with(|cell| cell.get()).is_null());
    assert!(CURRENT_INTERPRETER.with(|cell| cell.get()).is_null());
    assert_eq!(CURRENT_ENGINE.with(|cell| cell.get()), 0);
    lock_engines().remove(&engine);
}


#[test]
fn ast_rejects_corruption_versions_trailing_and_oversize() {
    let bytes = compile_ast("int x = 42;").unwrap();
    assert!(bytes.starts_with(b"BSHAST\0\x01"), "missing versioned AST header");
    let mut trailing = bytes.clone();
    trailing.push(0);
    assert!(decode_ast(&trailing).is_err());
    for length in 0..bytes.len() {
        assert!(decode_ast(&bytes[..length]).is_err(), "accepted truncated length {length}");
    }
    let mut bad_version = bytes.clone();
    bad_version[7] = 2;
    assert!(decode_ast(&bad_version).is_err());
    let mut corrupt = bytes.clone();
    corrupt[16..20].fill(255);
    assert!(decode_ast(&corrupt).is_err());
    assert!(decode_ast(&vec![0; 16 * 1024 * 1024 + 1]).is_err());
    assert!(compile_ast("int x = ;").is_err());
}

#[test]
fn validate_rejects_top_level_native() {
    let error = validate_source("native void danger();").unwrap_err();
    assert!(error.contains("native"), "{error}");
    assert!(validate_source("native int unsafeField;").is_err());
    assert!(validate_source("native class UnsafeClass {}").is_err());
}

#[test]
fn ast_errors_preserve_source_and_restore_engine_context() {
    let engine = NEXT_ENGINE.fetch_add(1, Ordering::Relaxed);
    with_engine(engine, |runtime| {
        runtime.interpreter.source_file = Some("outer.bsh".into());
        runtime.interpreter.source_text = Some("outer source".into());
    });
    let program = decode_ast(&compile_ast("\nmissingFunction();").unwrap()).unwrap();
    let error = eval_ast_session(engine, &program, "plugin-failure.bsh").1.unwrap_err();
    assert!(error.contains("plugin-failure.bsh:2:"), "{error}");
    assert!(!error.contains("outer source"), "{error}");
    with_engine(engine, |runtime| {
        assert_eq!(runtime.interpreter.source_file.as_deref(), Some("outer.bsh"));
        assert_eq!(runtime.interpreter.source_text.as_deref(), Some("outer source"));
    });
    lock_engines().remove(&engine);
}


#[test]
fn ast_roundtrip_keeps_methods_and_variables_in_the_same_engine() {
    let engine = NEXT_ENGINE.fetch_add(1, Ordering::Relaxed);
    assert!(eval_session(engine, "int seed = 40; int prior() { return seed; }", None).1.is_ok());
    let source = "// secret comment must not be persisted\nint increment(int x) { return prior() + x; }\nseed = increment(2);\nseed;";
    let bytes = compile_ast(source).unwrap();
    assert!(!bytes.windows(b"secret comment".len()).any(|s| s == b"secret comment"));
    let program = decode_ast(&bytes).unwrap();
    assert_eq!(program.stmts.len(), 3);
    assert!(matches!(program.stmts[0].kind, bsh_syntax::StmtKind::Method(_)));
    assert_eq!(program.stmts[0].span.line, 2);
    assert_eq!(eval_ast_session(engine, &program, "plugin-snapshot.bsh").1.unwrap().to_display_string(), "42");
    assert_eq!(eval_session(engine, "increment(1);", None).1.unwrap().to_display_string(), "43");
    with_engine(engine, |runtime| {
        assert_eq!(runtime.interpreter.get_variable_value("seed").unwrap().to_display_string(), "42");
    });
    lock_engines().remove(&engine);
}

#[test]
fn validate_parses_without_running_side_effects() {
    let mut runtime = new_runtime(0);
    runtime.interpreter.set_variable_value("sentinel", bsh_runtime::Value::Int(7));
    assert!(validate_source("sentinel = 99; print(\"must not run\"); unknownHost(); throw new RuntimeException();").is_ok());
    assert_eq!(runtime.interpreter.get_variable_value("sentinel").unwrap().to_display_string(), "7");
    assert!(runtime.output.borrow().is_empty());
    assert!(!runtime.interpreter.java_used.get());
    assert!(validate_source("").is_ok());
}

#[test]
fn validate_reports_full_parser_diagnostic() {
    let error = validate_source("int x = 1;\nint y = ;").unwrap_err();
    assert!(error.contains("line 2:"), "{error}");
    assert!(error.contains("int y = ;"), "{error}");
    assert!(error.contains('^'), "{error}");
    assert!(validate_source("\"unterminated").is_err());
    assert!(validate_source("if (true) {").is_err());
}
