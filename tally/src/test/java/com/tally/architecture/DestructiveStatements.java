package com.tally.architecture;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Finds the statements in a migration that would break the previous release during a rolling deploy, or lose
 * data, and that carry no {@code -- contract: <reason>} comment above them (ADR-009).
 *
 * <p>It reads PostgreSQL text without a parser: comments and quoted text are set aside first, so a word inside a
 * string, a comment or a {@code $$} body is never taken for a statement. It does not look for slow changes that
 * lock a table (an index built without {@code CONCURRENTLY}), and it does not look at constraints and indexes
 * that are dropped: the previous release keeps working without them. A dropped duplicate guard is caught by
 * the tests of the work it guards (CLAUDE.md rule 11).
 */
final class DestructiveStatements {

    record Finding(int line, String rule) {}

    private record Rule(String name, Pattern pattern) {}

    private record Statement(int line, String code, List<String> comments) {}

    private static final Pattern MARKER = Pattern.compile("^contract:\\s*\\S.*");

    private static final List<Rule> RULES = List.of(
            rule("drop table", "\\bDROP TABLE\\b"),
            rule("drop schema", "\\bDROP SCHEMA\\b"),
            rule("drop view", "\\bDROP (MATERIALIZED )?VIEW\\b"),
            rule("drop column", "^ALTER TABLE\\b.*\\bDROP (?!CONSTRAINT\\b|DEFAULT\\b|NOT NULL\\b|IDENTITY\\b|EXPRESSION\\b)"),
            rule("rename", "\\bRENAME\\b"),
            rule("change column type", "^ALTER TABLE\\b.*\\bALTER (COLUMN )?\\S+ (SET DATA )?TYPE\\b"),
            rule("set not null", "\\bSET NOT NULL\\b"),
            rule("add not-null column without default",
                    "^ALTER TABLE\\b.*\\bADD (COLUMN )?(?!CONSTRAINT\\b|PRIMARY\\b|UNIQUE\\b|CHECK\\b|FOREIGN\\b)"
                            + "(IF NOT EXISTS )?\\S+ [^,]*\\bNOT NULL\\b(?![^,]*\\bDEFAULT\\b)[^,]*(,|$)"),
            rule("truncate", "^TRUNCATE\\b"));

    private DestructiveStatements() {}

    static List<Finding> unmarked(String sql) {
        List<Finding> findings = new ArrayList<>();
        for (Statement statement : split(sql)) {
            if (statement.comments().stream().anyMatch(c -> MARKER.matcher(c).matches())) {
                continue;
            }
            String code = statement.code().strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
            for (Rule rule : RULES) {
                if (rule.pattern().matcher(code).find()) {
                    findings.add(new Finding(statement.line(), rule.name()));
                }
            }
        }
        return findings;
    }

    private static Rule rule(String name, String regex) {
        return new Rule(name, Pattern.compile(regex));
    }

    /**
     * Splits on semicolons outside quotes and comments. Each statement keeps the comments written since the
     * previous statement ended, and the line its code starts on. Quoted text is replaced by empty quotes.
     */
    private static List<Statement> split(String sql) {
        List<Statement> statements = new ArrayList<>();
        StringBuilder code = new StringBuilder();
        List<String> comments = new ArrayList<>();
        int line = 1;
        int start = 0;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (sql.startsWith("--", i)) {
                int end = sql.indexOf('\n', i);
                end = end < 0 ? sql.length() : end;
                comments.add(sql.substring(i + 2, end).strip());
                i = end;
            } else if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                end = end < 0 ? sql.length() : end + 2;
                comments.add(sql.substring(i + 2, Math.max(i + 2, end - 2)).strip());
                line += count(sql, i, end);
                i = end;
            } else if (c == '\'') {
                int end = i + 1;
                while (end < sql.length() && !(sql.charAt(end) == '\'' && !sql.startsWith("''", end))) {
                    end += sql.startsWith("''", end) ? 2 : 1;
                }
                end = Math.min(end + 1, sql.length());
                start = start == 0 ? line : start;
                code.append("''");
                line += count(sql, i, end);
                i = end;
            } else if (c == '$' && dollarTag(sql, i) != null) {
                String tag = dollarTag(sql, i);
                int end = sql.indexOf(tag, i + tag.length());
                end = end < 0 ? sql.length() : end + tag.length();
                start = start == 0 ? line : start;
                code.append("$$");
                line += count(sql, i, end);
                i = end;
            } else if (c == ';') {
                if (!code.isEmpty()) {
                    statements.add(new Statement(start, code.toString(), List.copyOf(comments)));
                }
                code.setLength(0);
                comments.clear();
                start = 0;
                i++;
            } else {
                if (c == '\n') {
                    line++;
                } else if (!Character.isWhitespace(c) && start == 0) {
                    start = line;
                }
                if (start != 0) {
                    code.append(c);
                }
                i++;
            }
        }
        if (!code.toString().isBlank()) {
            statements.add(new Statement(start, code.toString(), List.copyOf(comments)));
        }
        return statements;
    }

    private static String dollarTag(String sql, int i) {
        int end = i + 1;
        while (end < sql.length() && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
            end++;
        }
        return end < sql.length() && sql.charAt(end) == '$' ? sql.substring(i, end + 1) : null;
    }

    private static int count(String sql, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) {
            if (sql.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }
}
