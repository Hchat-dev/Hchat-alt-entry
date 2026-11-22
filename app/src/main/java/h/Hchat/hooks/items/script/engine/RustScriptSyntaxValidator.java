package h.Hchat.hooks.items.script.engine;

import h.Hchat.hooks.items.script.engine.preprocess.AnnotationIgnorePreprocess;
import h.Hchat.hooks.items.script.engine.preprocess.DefaultArgsDesugar;
import h.Hchat.hooks.items.script.engine.preprocess.ImplicitDefaultConstructorPreprocess;
import h.Hchat.hooks.items.script.engine.preprocess.KtStringTemplate;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.yun.bsh.rust.BshEngine;

public final class RustScriptSyntaxValidator {
    private static final Pattern POSITION = Pattern.compile("^.*: line ([0-9]+):([0-9]+)$");

    private RustScriptSyntaxValidator() {}

    public static final class Result {
        public final String error;
        public final int line;
        public final int column;
        public final String parsedSource;
        public final boolean originalLineNumbers;

        private Result(String error, int line, int column, String parsedSource, boolean originalLineNumbers) {
            this.error = error;
            this.line = line;
            this.column = column;
            this.parsedSource = parsedSource;
            this.originalLineNumbers = originalLineNumbers;
        }
    }

    public static Result validate(String source) {
        Result parsed = preprocess(source);
        return withError(parsed, BshEngine.validate(terminated(parsed)));
    }

    static Result preprocess(String source) {
        Objects.requireNonNull(source, "source");
        String annotations = AnnotationIgnorePreprocess.rewrite(source);
        String defaults = DefaultArgsDesugar.rewrite(annotations);
        boolean originalLineNumbers = defaults.equals(annotations);
        String templates = KtStringTemplate.rewrite(defaults);
        originalLineNumbers &= lineCount(templates) == lineCount(defaults);
        String rewritten = ImplicitDefaultConstructorPreprocess.rewrite(templates);
        return new Result(null, 0, 0, rewritten, originalLineNumbers);
    }

    static String terminated(Result parsed) {
        return parsed.parsedSource.endsWith(";") ? parsed.parsedSource : parsed.parsedSource + ";";
    }

    static Result withError(Result parsed, String error) {
        if (error == null) return parsed;
        int line = 0;
        int column = 0;
        int newline = error.indexOf('\n');
        Matcher position = POSITION.matcher(newline < 0 ? error : error.substring(0, newline));
        if (position.matches()) {
            try {
                int parsedLine = Integer.parseInt(position.group(1));
                int parsedColumn = Integer.parseInt(position.group(2));
                if (parsedLine > 0 && parsedColumn > 0 && parsedLine <= lineCount(parsed.parsedSource)) {
                    line = parsedLine;
                    column = parsedColumn;
                }
            } catch (NumberFormatException ignored) {
                // 不可靠的位置保持为 0。
            }
        }
        return new Result("Rust 脚本语法错误：" + error, line, column,
                parsed.parsedSource, parsed.originalLineNumbers);
    }

    private static int lineCount(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n' || (ch == '\r' && (i + 1 == text.length() || text.charAt(i + 1) != '\n'))) lines++;
        }
        return lines;
    }
}
