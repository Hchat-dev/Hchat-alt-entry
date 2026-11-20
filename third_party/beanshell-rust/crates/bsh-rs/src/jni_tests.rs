use std::path::PathBuf;
use std::process::Command;

#[test]
fn real_jvm_native_extensions() {
    let target = PathBuf::from(std::env::var("CARGO_TARGET_DIR").expect("CARGO_TARGET_DIR"));
    let library = target.join("debug/libbsh_rs.so");
    assert!(library.exists(), "run cargo build --offline -p bsh-rs before JNI tests");
    let dir = target.join("jni-extension-tests");
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("Native.java"), r#"
package me.yun.bsh.rust;
public final class Native {
    public static native long createEngine();
    public static native void destroyEngine(long engine);
    public static native String eval(long engine, String source);
    public static native Object evalValue(long engine, String source);
    public static native Object getVariable(long engine, String name);
    public static native String validate(String source);
    public static native byte[] compileAst(String source);
    public static native Object evalAst(long engine, byte[] ast, String sourceName);
    public static native Object evalSource(long engine, String source, String sourceName);
    public static native void unsetVariable(long engine, String name);
}
"#).unwrap();
    std::fs::write(dir.join("NativeTest.java"), r#"
import me.yun.bsh.rust.Native;
import java.util.Arrays;
public class NativeTest {
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    static RuntimeException failure(Runnable action) {
        try { action.run(); } catch (RuntimeException e) { return e; }
        throw new AssertionError("Expected RuntimeException");
    }
    public static void main(String[] args) {
        System.load(args[0]);
        long engine = Native.createEngine();
        try {
            String valid = Native.validate("System.setProperty(\"bsh.validation.sideeffect\", \"bad\");");
            check(valid == null || valid.isEmpty());
            check(System.getProperty("bsh.validation.sideeffect") == null);
            check(Native.validate("int x = ;").contains("int x = ;"));
            check(Native.validate("native void bad();").contains("native"));
            Native.evalValue(engine, "int base = 40; int existing(int x) { return base + x; }");
            byte[] ast = Native.compileAst("int saved() { return existing(2); } base = saved(); base;");
            check(Native.evalAst(engine, ast, "保存脚本.bsh").equals(42));
            check(Native.evalValue(engine, "saved();").equals(44));
            check(Native.evalSource(engine, "base += 1; base;", "source.bsh").equals(43));
            Native.unsetVariable(engine, "base");
            check(Native.getVariable(engine, "base") == null);
            failure(() -> Native.evalValue(engine, "base;"));
            RuntimeException runtime = failure(() -> Native.evalAst(engine, Native.compileAst("\nmissingFunction();"), "保存脚本.bsh"));
            check(runtime.getMessage().contains("保存脚本.bsh:2:"));
            RuntimeException unicode = failure(() -> Native.evalSource(engine, "missingFunction();", "脚本🧪.bsh"));
            check(unicode.getMessage().contains("脚本🧪.bsh"));
            RuntimeException syntax = failure(() -> Native.compileAst("int x = ;"));
            check(syntax.getMessage().contains("int x = ;"));
            failure(() -> Native.evalAst(engine, Arrays.copyOf(ast, ast.length + 1), "bad.bsh"));
            failure(() -> Native.evalAst(engine, new byte[16 * 1024 * 1024 + 1], "huge.bsh"));
            failure(() -> Native.evalAst(engine, null, "null.bsh"));
            failure(() -> Native.evalSource(engine, "missingFunction();", "source-error.bsh"));
            check(Native.evalValue(engine, "class SnapshotList extends java.util.ArrayList { public int size() { return super.size() + 100; } } SnapshotList list = new SnapshotList(); list.add(\"entry\"); list.size();").equals(101));
            check(Native.eval(engine, "print(123);").contains("123"));
            System.out.println("JNI_EXTENSIONS_OK");
        } finally { Native.destroyEngine(engine); }
    }
}
"#).unwrap();
    let compiled = Command::new("javac").args(["-encoding", "UTF-8", "-d"])
        .arg(&dir).arg(dir.join("Native.java")).arg(dir.join("NativeTest.java"))
        .output().unwrap();
    assert!(compiled.status.success(), "{}", String::from_utf8_lossy(&compiled.stderr));
    let bridge_classes = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../bsh-jvm/java-classes");
    let classpath = std::env::join_paths([&dir, &bridge_classes]).unwrap();
    let output = Command::new("java").arg("-Xcheck:jni").arg("-cp").arg(classpath)
        .arg("NativeTest").arg(&library).output().unwrap();
    assert!(output.status.success(), "stdout: {}\nstderr: {}", String::from_utf8_lossy(&output.stdout), String::from_utf8_lossy(&output.stderr));
    assert!(String::from_utf8_lossy(&output.stdout).contains("JNI_EXTENSIONS_OK"));
}
