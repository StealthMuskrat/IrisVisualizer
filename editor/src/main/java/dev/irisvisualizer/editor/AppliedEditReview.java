package dev.irisvisualizer.editor;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Bounded read-only source text for retained byte snapshots. No JSON/native parsing or evaluation. */
public final class AppliedEditReview {
    public static final int MAX_TEXT_BYTES=65_536,MAX_TEXT_CHARACTERS=16_384;
    public enum Kind { TEXT, TOO_LARGE, INVALID_UTF8, BINARY }
    public record Text(int bytes,String text,Kind kind,boolean truncated) {}
    /** Next restore first; steps includes this edit and all intervening edits in that direction. */
    public record Step(long id,String path,int beforeBytes,int afterBytes,boolean forward,int steps) {}
    public record Comparison(long id,String path,Text before,Text after) {}
    static Text text(byte[] bytes) {
        if (bytes.length>MAX_TEXT_BYTES) return new Text(bytes.length,"",Kind.TOO_LARGE,false);
        try {
            String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (value.chars().anyMatch(c -> Character.isISOControl(c) && c!='\r' && c!='\n' && c!='\t')) return new Text(bytes.length,"",Kind.BINARY,false);
            boolean truncated=value.length()>MAX_TEXT_CHARACTERS;
            if (truncated) { int end=MAX_TEXT_CHARACTERS;if (Character.isHighSurrogate(value.charAt(end-1))) end--;value=value.substring(0,end); }
            return new Text(bytes.length,value,Kind.TEXT,truncated);
        } catch (CharacterCodingException invalid) { return new Text(bytes.length,"",Kind.INVALID_UTF8,false); }
    }
    static Comparison compare(long id,String path,byte[] before,byte[] after) { return new Comparison(id,path,text(before),text(after)); }
    public static final class Cache {
        private PackWorkspace workspace;
        private long id=-1;
        private Comparison comparison;
        public void clear() { workspace=null;id=-1;comparison=null; }
        public Comparison inspect(PackWorkspace current,long selected) {
            Objects.requireNonNull(current);
            if (!current.hasHistoryEdit(selected)) { workspace=current;id=-1;comparison=null;return null; }
            if (workspace!=current || id!=selected) { comparison=current.reviewHistoryEdit(selected);workspace=current;id=selected; }
            return comparison;
        }
    }
}
