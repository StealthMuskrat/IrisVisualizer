package dev.irisvisualizer.editor;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Strict JSON parsing for source spans. Only the requested scalar token is replaced. */
public final class LosslessJson {
    private final String text;
    private int at;
    private final Map<String, Span> root = new LinkedHashMap<>();
    private record Span(int start, int end) {}
    public LosslessJson(byte[] bytes) {
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException failure) { throw new IllegalArgumentException("JSON is not valid UTF-8", failure); }
        space(); if (peek() != '{') throw error(); object(true, 0); space(); if (at != text.length()) throw error();
    }
    public Set<String> fields() { return Collections.unmodifiableSet(root.keySet()); }
    byte[] sourceBytes() { return text.getBytes(StandardCharsets.UTF_8); }
    public String token(String field) { var span = root.get(field); return span == null ? null : text.substring(span.start(), span.end()); }
    public byte[] number(String field, double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Finite number required");
        var span = root.get(field); if (span == null) throw new IllegalArgumentException("Absent field must be explicitly enabled first");
        String old = token(field);
        if (!numericToken(old)) throw new IllegalArgumentException("Stored field is not numeric");
        String next = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        return (text.substring(0, span.start()) + next + text.substring(span.end())).getBytes(StandardCharsets.UTF_8);
    }
    public String stringToken(String field) {
        Span span = root.get(field);
        if (span == null) return null;
        at = span.start(); return string();
    }
    byte[] stringValue(String field, String value) {
        Span span = root.get(field);
        if (span == null) throw new IllegalArgumentException("String field is absent");
        at = span.start(); string();
        return splice(span.start(), span.end(), quote(value));
    }
    /** Explicit optional-string assignment; only absent, null or string values are supported. */
    byte[] nullableString(String field, String value) {
        Span span = root.get(field);
        if (span == null) {
            if (value == null) return sourceBytes(); // Preserve an absent null default.
            return enableStringPath(field, value);
        }
        if (!"null".equals(token(field))) stringToken(field);
        return splice(span.start(), span.end(), value == null ? "null" : quote(value));
    }
    static boolean numericToken(String token) {
        return token != null && token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
    }
    /** Nullable numeric override; absence is retained when resetting an absent field. */
    byte[] nullableNumber(String field, Double value) {
        String old = token(field);
        if (old != null && !old.equals("null") && !numericToken(old)) throw new IllegalArgumentException("Stored override is not numeric or null");
        if (value != null && !Double.isFinite(value)) throw new IllegalArgumentException("Finite number required");
        String next = value == null ? "null" : java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        return nullableScalar(field, next);
    }
    byte[] nullableBoolean(String field, Boolean value) {
        String old = token(field);
        if (old != null && !old.equals("null") && !old.equals("true") && !old.equals("false")) throw new IllegalArgumentException("Stored override is not boolean or null");
        return nullableScalar(field, value == null ? "null" : value.toString());
    }
    private byte[] nullableScalar(String field, String next) {
        Span span = root.get(field);
        if (span == null) return next.equals("null") ? sourceBytes() : enable(field, next);
        return splice(span.start(), span.end(), next);
    }
    /** Explicit inheritance/empty selection states; malformed or mixed arrays remain read-only. */
    byte[] nullableStringArray(String field, boolean inherit) {
        String old = token(field);
        if (old != null && !old.equals("null")) stringEntries(field);
        return nullableScalar(field, inherit ? "null" : "[]");
    }
    public byte[] integer(String field, long value) {
        Span span = root.get(field);
        if (span == null) throw new IllegalArgumentException("Absent field must be explicitly enabled first");
        try { new java.math.BigDecimal(token(field)).longValueExact(); }
        catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Stored field is not a 64-bit integer", invalid); }
        return splice(span.start(), span.end(), Long.toString(value));
    }
    public byte[] bool(String field, boolean value) {
        String old = token(field);
        if (!"true".equals(old) && !"false".equals(old)) throw new IllegalArgumentException("Stored field is not boolean");
        Span span = root.get(field);
        return splice(span.start(), span.end(), Boolean.toString(value));
    }
    /** Explicitly inserts one absent numeric/boolean field; existing source text is retained. */
    public byte[] enable(String field, String value) {
        if (root.containsKey(field)) throw new IllegalArgumentException("Field is already present");
        if (!field.matches("[A-Za-z][A-Za-z0-9]*") || !value.equals("[]") && !value.matches("true|false|-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))
            throw new IllegalArgumentException("Invalid scalar field definition");
        int position;
        String prefix;
        if (root.isEmpty()) { position = text.indexOf('{') + 1; prefix = ""; }
        else { position = root.values().stream().mapToInt(Span::end).max().orElseThrow(); prefix = ","; }
        String spacing = text.contains("\r\n") ? "\r\n  " : text.contains("\n") ? "\n  " : " ";
        return splice(position, position, prefix + spacing + "\"" + field + "\": " + value);
    }
    /** Audited dotted paths address object members only, never array indices or snippet references. */
    public String tokenPath(String path) {
        int dot = path.indexOf('.');
        if (dot < 0) return token(path);
        String parent = token(path.substring(0, dot));
        return parent == null ? null : new LosslessJson(parent.getBytes(StandardCharsets.UTF_8)).tokenPath(path.substring(dot + 1));
    }
    public byte[] integerPath(String path, long value) { return updatePath(path, (doc, field) -> doc.integer(field, value), false); }
    public byte[] numberPath(String path, double value) { return updatePath(path, (doc, field) -> doc.number(field, value), false); }
    public byte[] boolPath(String path, boolean value) { return updatePath(path, (doc, field) -> doc.bool(field, value), false); }
    public byte[] stringPath(String path, String value) { return updatePath(path, (doc, field) -> doc.stringValue(field, value), false); }
    public String stringPath(String path) {
        int dot = path.indexOf('.');
        if (dot < 0) return stringToken(path);
        String parent = token(path.substring(0, dot));
        return parent == null ? null : new LosslessJson(parent.getBytes(StandardCharsets.UTF_8)).stringPath(path.substring(dot+1));
    }
    public byte[] enableStringPath(String path, String value) {
        return updatePath(path, (doc, field) -> {
            var inserted = new LosslessJson(doc.enable(field, "0"));
            Span span = inserted.root.get(field);
            return inserted.splice(span.start(), span.end(), quote(value));
        }, true);
    }
    public byte[] enablePath(String path, String value) { return updatePath(path, (doc, field) -> doc.enable(field, value), true); }
    private record StringEntry(Span span, String value) {}
    private List<StringEntry> stringEntries(String field) {
        Span container = root.get(field);
        if (container == null) throw new IllegalArgumentException("Absent array must be explicitly enabled first");
        at = container.start(); require('['); space();
        List<StringEntry> entries = new ArrayList<>();
        if (peek() == ']') return entries;
        do {
            space(); int start = at; String value = string();
            entries.add(new StringEntry(new Span(start, at), value));
            space(); if (peek() == ']') return entries; require(',');
        } while (true);
    }
    public List<String> stringArrayPath(String path) {
        int dot = path.indexOf('.');
        if (dot >= 0) {
            String parent = token(path.substring(0, dot));
            if (parent == null) throw new IllegalArgumentException("Absent parent");
            return new LosslessJson(parent.getBytes(StandardCharsets.UTF_8)).stringArrayPath(path.substring(dot + 1));
        }
        return stringEntries(path).stream().map(StringEntry::value).toList();
    }
    /** index == size appends; null removes an existing item. Unchanged item tokens remain intact. */
    public byte[] stringArrayPath(String path, int index, String value) {
        return updatePath(path, (doc, field) -> doc.stringArray(field, index, value), false);
    }
    private byte[] stringArray(String field, int index, String value) {
        List<StringEntry> entries = stringEntries(field); int count = entries.size();
        if (index < 0 || index > count || (value == null && index == count)) throw new IllegalArgumentException("Array index out of range");
        if (value != null) {
            String quoted = quote(value);
            if (index < count) { Span span = entries.get(index).span(); return splice(span.start(), span.end(), quoted); }
            int position = count == 0 ? root.get(field).start() + 1 : entries.get(count-1).span().end();
            return splice(position, position, (count == 0 ? "" : ", ") + quoted);
        }
        Span span = entries.get(index).span();
        if (count == 1) return splice(span.start(), span.end(), "");
        if (index < count-1) return splice(span.start(), entries.get(index+1).span().start(), "");
        return splice(entries.get(index-1).span().end(), span.end(), "");
    }
    byte[] stringArrayDuplicate(String field, int index) {
        List<StringEntry> entries = stringEntries(field);
        if (index < 0 || index >= entries.size()) throw new IllegalArgumentException("Array index out of range");
        Span span = entries.get(index).span(); String token = text.substring(span.start(),span.end());
        if (index+1 < entries.size()) {
            int position = entries.get(index+1).span().start();
            return splice(position,position,token+", ");
        }
        return splice(span.end(),span.end(),", "+token);
    }
    byte[] stringArrayMove(String field, int from, int to) {
        return moveSpans(stringEntries(field).stream().map(StringEntry::span).toList(),from,to);
    }
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i=0;i<value.length();i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') out.append('\\').append(c);
            else if (c < 32) out.append(String.format("\\u%04x", (int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
    private List<Span> arraySpans(String field) {
        Span container = root.get(field);
        if (container == null) throw new IllegalArgumentException("Array is absent");
        at = container.start(); require('['); space(); List<Span> spans = new ArrayList<>();
        if (peek() == ']') return spans;
        do {
            space(); int start = at; value(0); spans.add(new Span(start, at));
            space(); if (peek() == ']') return spans; require(',');
        } while (true);
    }
    public List<LosslessJson> objectArray(String field) {
        return arraySpans(field).stream().map(span -> new LosslessJson(text.substring(span.start(), span.end()).getBytes(StandardCharsets.UTF_8))).toList();
    }
    /** Inserts a validated object; mixed arrays/snippets remain read-only. */
    byte[] objectArrayInsert(String field, int index, byte[] object) {
        new LosslessJson(object); objectArray(field);
        List<Span> spans = arraySpans(field);
        if (index < 0 || index > spans.size()) throw new IllegalArgumentException("Array index out of range");
        String value = new String(object, StandardCharsets.UTF_8);
        if (index < spans.size()) return splice(spans.get(index).start(), spans.get(index).start(), value + ", ");
        int position = spans.isEmpty() ? root.get(field).start() + 1 : spans.getLast().end();
        return splice(position, position, (spans.isEmpty() ? "" : ", ") + value);
    }
    byte[] objectArrayRemove(String field, int index) {
        objectArray(field); List<Span> spans = arraySpans(field);
        if (index < 0 || index >= spans.size()) throw new IllegalArgumentException("Array index out of range");
        Span span = spans.get(index);
        if (spans.size() == 1) return splice(span.start(), span.end(), "");
        if (index < spans.size()-1) return splice(span.start(), spans.get(index+1).start(), "");
        return splice(spans.get(index-1).end(), span.end(), "");
    }
    /** Moves whole object tokens, retaining separators/whitespace at each list position. */
    byte[] objectArrayMove(String field, int from, int to) {
        objectArray(field); List<Span> spans = arraySpans(field);
        return moveSpans(spans,from,to);
    }
    private byte[] moveSpans(List<Span> spans, int from, int to) {
        if (from < 0 || from >= spans.size() || to < 0 || to >= spans.size()) throw new IllegalArgumentException("Array index out of range");
        List<String> tokens = new ArrayList<>(spans.stream().map(span -> text.substring(span.start(), span.end())).toList());
        tokens.add(to, tokens.remove(from));
        StringBuilder next = new StringBuilder(); int previous = 0;
        for (int i=0;i<spans.size();i++) {
            Span span = spans.get(i);
            next.append(text, previous, span.start()).append(tokens.get(i)); previous = span.end();
        }
        return next.append(text, previous, text.length()).toString().getBytes(StandardCharsets.UTF_8);
    }
    public byte[] objectArrayNumber(String field, int index, String property, double value) {
        return objectArrayEdit(field, index, object -> object.number(property, value));
    }
    /** Updates one object member; absent parents are created only for explicit enable operations. */
    byte[] objectEdit(String field, java.util.function.Function<LosslessJson, byte[]> operation, boolean create) {
        String original = token(field);
        if (original == null && !create) throw new IllegalArgumentException("Absent parent must be explicitly enabled first");
        byte[] updated = operation.apply(new LosslessJson((original == null ? "{}" : original).getBytes(StandardCharsets.UTF_8)));
        new LosslessJson(updated);
        String next = new String(updated, StandardCharsets.UTF_8);
        if (original == null) return insertObject(field, next);
        Span span = root.get(field);
        return splice(span.start(), span.end(), next);
    }
    /** Explicitly enables an absent or null optional object; other stored types remain untouched. */
    byte[] enableObject(String field) {
        String original = token(field);
        if (original == null) return insertObject(field,"{}");
        if (!original.equals("null")) throw new IllegalArgumentException("Optional object is already present or has an unsupported type");
        Span span = root.get(field);
        return splice(span.start(),span.end(),"{}");
    }
    /** Explicit optional-object disable. Preserve absence/null; unsupported stored types remain untouched. */
    byte[] nullObject(String field) {
        String original = token(field);
        if (original == null || original.equals("null")) return sourceBytes();
        new LosslessJson(original.getBytes(StandardCharsets.UTF_8));
        return nullableScalar(field,"null");
    }
    byte[] objectArrayEdit(String field, int index, java.util.function.Function<LosslessJson, byte[]> operation) {
        List<LosslessJson> objects = objectArray(field);
        if (index < 0 || index >= objects.size()) throw new IllegalArgumentException("Array index out of range");
        Span span = arraySpans(field).get(index);
        byte[] updated = operation.apply(objects.get(index));
        new LosslessJson(updated); // Keep the array element an unambiguous JSON object.
        return splice(span.start(), span.end(), new String(updated, StandardCharsets.UTF_8));
    }
    private byte[] updatePath(String path, java.util.function.BiFunction<LosslessJson, String, byte[]> operation, boolean create) {
        int dot = path.indexOf('.');
        if (dot < 0) return operation.apply(this, path);
        String field = path.substring(0, dot), rest = path.substring(dot + 1);
        String parent = token(field);
        if (parent == null && !create) throw new IllegalArgumentException("Absent parent must be explicitly enabled first");
        LosslessJson child = new LosslessJson((parent == null ? "{}" : parent).getBytes(StandardCharsets.UTF_8));
        String next = new String(child.updatePath(rest, operation, create), StandardCharsets.UTF_8);
        if (parent == null) return insertObject(field, next);
        Span span = root.get(field);
        return splice(span.start(), span.end(), next);
    }
    private byte[] insertObject(String field, String value) {
        // Reuse the validated insertion position/format; replace its temporary scalar by the object.
        byte[] inserted = enable(field, "0");
        LosslessJson document = new LosslessJson(inserted);
        Span span = document.root.get(field);
        return document.splice(span.start(), span.end(), value);
    }
    private byte[] splice(int start, int end, String value) {
        return (text.substring(0, start) + value + text.substring(end)).getBytes(StandardCharsets.UTF_8);
    }
    private void value(int depth) {
        if (depth > 128) throw new IllegalArgumentException("JSON nesting exceeds editor limit");
        space(); char c = peek();
        if (c == '{') object(false, depth + 1);
        else if (c == '[') { at++; space(); if (peek() == ']') { at++; return; } do { value(depth + 1); space(); if (peek() == ']') { at++; return; } require(','); } while (true); }
        else if (c == '"') string();
        else {
            int start = at; while (at < text.length() && ",]} \r\n\t".indexOf(text.charAt(at)) < 0) at++;
            String token = text.substring(start, at);
            if (!token.matches("true|false|null|-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw error();
        }
    }
    private void object(boolean capture, int depth) {
        require('{'); space(); if (peek() == '}') { at++; return; }
        Set<String> keys = new HashSet<>();
        do {
            space(); String key = string(); if (!keys.add(key)) throw new IllegalArgumentException("Duplicate JSON property: " + key);
            space(); require(':'); space(); int start = at; value(depth + 1); int end = at;
            if (capture) root.put(key, new Span(start, end));
            space(); if (peek() == '}') { at++; return; } require(',');
        } while (true);
    }
    private String string() {
        require('"'); StringBuilder out = new StringBuilder();
        while (at < text.length()) {
            char c = text.charAt(at++); if (c == '"') return out.toString(); if (c < 32) throw error();
            if (c != '\\') { out.append(c); continue; }
            char escape = peek(); at++;
            switch (escape) {
                case '"', '\\', '/' -> out.append(escape);
                case 'b' -> out.append('\b'); case 'f' -> out.append('\f'); case 'n' -> out.append('\n'); case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                case 'u' -> { if (at + 4 > text.length()) throw error(); String hex = text.substring(at, at + 4); if (!hex.matches("[0-9a-fA-F]{4}")) throw error(); out.append((char) Integer.parseInt(hex, 16)); at += 4; }
                default -> throw error();
            }
        }
        throw error();
    }
    private void space() { while (at < text.length() && " \r\n\t".indexOf(text.charAt(at)) >= 0) at++; }
    private char peek() { return at < text.length() ? text.charAt(at) : 0; }
    private void require(char c) { if (peek() != c) throw error(); at++; }
    private IllegalArgumentException error() { return new IllegalArgumentException("Invalid JSON near character " + at + "; original bytes remain available"); }
}
