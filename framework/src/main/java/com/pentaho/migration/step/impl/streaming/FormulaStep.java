package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluates a Pentaho OpenFormula expression and appends the result as a new field.
 *
 * <p>Supported syntax:
 * <ul>
 *   <li>{@code IF(condition; trueExpr; falseExpr)} — conditional expression</li>
 *   <li>{@code CONCATENATE(expr; expr; ...)} — string concatenation</li>
 *   <li>{@code [fieldName]} — field reference (resolved via {@code fieldNames} param)</li>
 *   <li>String literals: {@code "text"}</li>
 *   <li>Numeric literals: {@code 10000}, {@code 5000.0}</li>
 *   <li>Comparison operators: {@code >  <  >=  <=  =  <>}</li>
 * </ul>
 *
 * <p>Params:
 * <ul>
 *   <li>{@code fieldName}     — name of the output field to append</li>
 *   <li>{@code formulaString} — OpenFormula expression</li>
 *   <li>{@code fieldNames}    — comma-separated list of upstream field names (injected by KtrParser)</li>
 * </ul>
 */
public class FormulaStep extends AbstractStreamingStep {

    private String   newFieldName;
    private String[] inputFieldNames;
    private Expr     compiledExpr;

    @Override
    public void configure(Map<String, String> params) {
        newFieldName    = params.getOrDefault("fieldName", "result");
        String raw      = params.get("formulaString");
        String namesRaw = params.get("fieldNames");
        inputFieldNames = (namesRaw != null && !namesRaw.isBlank())
                          ? namesRaw.split(",", -1) : new String[0];
        compiledExpr = (raw != null && !raw.isBlank())
                       ? new FormulaParser(raw.trim()).parse()
                       : (row, names) -> null;
    }

    @Override
    protected Row transform(Row row) {
        String result = compiledExpr.eval(row, inputFieldNames);
        String[] vals = new String[row.fieldCount() + 1];
        for (int i = 0; i < row.fieldCount(); i++) vals[i] = row.getString(i);
        vals[row.fieldCount()] = result;
        return new Row(vals);
    }

    // -------------------------------------------------------------------------
    // Expression types
    // -------------------------------------------------------------------------

    @FunctionalInterface
    interface Expr {
        String eval(Row row, String[] fieldNames);
    }

    /** Looks up a field by name in the fieldNames array and returns its value from the row. */
    static Expr fieldRef(String name) {
        return (row, names) -> {
            for (int i = 0; i < names.length; i++) {
                if (name.equals(names[i])) return i < row.fieldCount() ? row.getString(i) : null;
            }
            return null;
        };
    }

    /** A condition that evaluates to a boolean for use in IF(). */
    static class Condition {
        final Expr  left, right;
        final String op;

        Condition(Expr l, String op, Expr r) { left = l; this.op = op; right = r; }

        boolean test(Row row, String[] names) {
            String l = left.eval(row, names);
            String r = right.eval(row, names);
            if (l == null || r == null) return false;
            try {
                double ld = Double.parseDouble(l);
                double rd = Double.parseDouble(r);
                return switch (op) {
                    case ">"  -> ld > rd;
                    case "<"  -> ld < rd;
                    case ">=" -> ld >= rd;
                    case "<=" -> ld <= rd;
                    case "="  -> ld == rd;
                    case "<>" -> ld != rd;
                    default   -> false;
                };
            } catch (NumberFormatException ex) {
                int cmp = l.compareTo(r);
                return switch (op) {
                    case ">"  -> cmp > 0;
                    case "<"  -> cmp < 0;
                    case ">=" -> cmp >= 0;
                    case "<=" -> cmp <= 0;
                    case "="  -> cmp == 0;
                    case "<>" -> cmp != 0;
                    default   -> false;
                };
            }
        }
    }

    // -------------------------------------------------------------------------
    // Recursive-descent parser
    // -------------------------------------------------------------------------

    static final class FormulaParser {

        private final String input;
        private int pos;

        FormulaParser(String input) { this.input = input; this.pos = 0; }

        Expr parse() {
            Expr e = parseExpr();
            skipWs();
            return e;
        }

        // expr := if_expr | concat_expr | atom
        private Expr parseExpr() {
            skipWs();
            if (matchKeyword("IF("))          return parseIf();
            if (matchKeyword("CONCATENATE(")) return parseConcat();
            return parseAtom();
        }

        // IF( condition ; trueExpr ; falseExpr )
        private Expr parseIf() {
            Condition cond = parseCondition();
            skipWs(); expect(';');
            Expr then = parseExpr();
            skipWs(); expect(';');
            Expr else_ = parseExpr();
            skipWs(); expect(')');
            return (row, names) -> cond.test(row, names) ? then.eval(row, names) : else_.eval(row, names);
        }

        // CONCATENATE( expr ; expr ; ... )
        private Expr parseConcat() {
            List<Expr> args = new ArrayList<>();
            args.add(parseExpr());
            skipWs();
            while (pos < input.length() && input.charAt(pos) == ';') {
                pos++; // consume ';'
                args.add(parseExpr());
                skipWs();
            }
            expect(')');
            return (row, names) -> {
                StringBuilder sb = new StringBuilder();
                for (Expr e : args) { String v = e.eval(row, names); if (v != null) sb.append(v); }
                return sb.toString();
            };
        }

        // condition := atom OP atom
        private Condition parseCondition() {
            Expr left = parseAtom();
            skipWs();
            String op = parseOp();
            skipWs();
            Expr right = parseAtom();
            return new Condition(left, op, right);
        }

        // atom := [fieldName] | "string" | number | nested IF/CONCAT
        private Expr parseAtom() {
            skipWs();
            if (pos >= input.length()) return (row, names) -> null;
            char c = input.charAt(pos);
            if (c == '[')  return parseFieldRef();
            if (c == '"')  return parseStringLiteral();
            if (c == '(')  { pos++; Expr inner = parseExpr(); skipWs(); expect(')'); return inner; }
            // keyword expressions inside atom context
            if (matchKeyword("IF("))          return parseIf();
            if (matchKeyword("CONCATENATE(")) return parseConcat();
            return parseNumericLiteral();
        }

        private Expr parseFieldRef() {
            expect('[');
            int start = pos;
            while (pos < input.length() && input.charAt(pos) != ']') pos++;
            String name = input.substring(start, pos);
            expect(']');
            return fieldRef(name);
        }

        private Expr parseStringLiteral() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < input.length() && input.charAt(pos) != '"') {
                if (input.charAt(pos) == '\\' && pos + 1 < input.length()) pos++; // escape
                sb.append(input.charAt(pos++));
            }
            expect('"');
            String s = sb.toString();
            return (row, names) -> s;
        }

        private Expr parseNumericLiteral() {
            int start = pos;
            if (pos < input.length() && input.charAt(pos) == '-') pos++;
            while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) pos++;
            String s = input.substring(start, pos).trim();
            if (s.isEmpty()) {
                // Unrecognised — consume until next semicolon, closing paren, or end
                int ustart = pos;
                while (pos < input.length() && ";)".indexOf(input.charAt(pos)) < 0) pos++;
                String token = input.substring(ustart, pos).trim();
                return (row, names) -> token.isEmpty() ? null : token;
            }
            return (row, names) -> s;
        }

        // Parses >=, <=, <>, >, <, =
        private String parseOp() {
            if (pos + 1 < input.length()) {
                String two = input.substring(pos, pos + 2);
                if (two.equals(">=") || two.equals("<=") || two.equals("<>")) { pos += 2; return two; }
            }
            char c = input.charAt(pos++);
            return String.valueOf(c);
        }

        /** Case-insensitive keyword match — consumes the keyword if matched. */
        private boolean matchKeyword(String kw) {
            if (pos + kw.length() > input.length()) return false;
            if (input.substring(pos, pos + kw.length()).equalsIgnoreCase(kw)) {
                pos += kw.length();
                return true;
            }
            return false;
        }

        private void skipWs() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
        }

        private void expect(char c) {
            if (pos >= input.length() || input.charAt(pos) != c) {
                throw new IllegalArgumentException(
                        "Formula parse error: expected '" + c + "' at position " + pos
                        + " in: " + input);
            }
            pos++;
        }
    }
}
