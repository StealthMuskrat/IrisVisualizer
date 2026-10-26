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
    public String token(String field) { var span = root.get(field); return span == null ? null : text.substring(span.start(), span.end()); }
    public byte[] number(String field, double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Finite number required");
        var span = root.get(field); if (span == null) throw new IllegalArgumentException("Absent field must be explicitly enabled first");
        String old = token(field);
        if (!old.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw new IllegalArgumentException("Stored field is not numeric");
        String next = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        return (text.substring(0, span.start()) + next + text.substring(span.end())).getBytes(StandardCharsets.UTF_8);
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
