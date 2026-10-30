package dev.irisvisualizer.editor;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Literal, case-sensitive search of applied JSON source bytes. No JSON or native evaluation. */
public final class WorkspaceTextSearch {
    public static final int MAX_FILE_BYTES=1_048_576, MAX_TOTAL_BYTES=16_777_216,
            MAX_FILES=4096, MAX_RESULTS=200, MAX_QUERY_CHARACTERS=256, MAX_EXCERPT_CHARACTERS=160;
    public record Query(String text, ResourceFinder.Family family, boolean changedOnly) {
        public Query {
            if (text==null || text.isEmpty() || text.length()>MAX_QUERY_CHARACTERS
                    || text.chars().anyMatch(Character::isISOControl) || family==null)
                throw new IllegalArgumentException("Enter 1-256 literal characters without control characters");
        }
    }
    /** Line and column are one-based; column and offset count UTF-16 code units in decoded source. */
    public record Hit(String path, int line, int column, int offset, String excerpt, boolean clippedBefore, boolean clippedAfter) {}
    public record Result(int eligibleFiles, int scannedFiles, long scannedBytes, int matches,
                         int tooLarge, int invalidText, int byteBudgetSkipped, int fileBudgetSkipped, List<Hit> hits) {
        public Result { hits=List.copyOf(hits); }
        public boolean incomplete() { return tooLarge+invalidText+byteBudgetSkipped+fileBudgetSkipped>0; }
        public boolean truncated() { return matches>hits.size(); }
    }
    record Limits(int fileBytes, int totalBytes, int files, int results) {
        Limits {
            if (fileBytes<1 || fileBytes>MAX_FILE_BYTES || totalBytes<1 || totalBytes>MAX_TOTAL_BYTES
                    || files<1 || files>MAX_FILES || results<1 || results>MAX_RESULTS)
                throw new IllegalArgumentException("Invalid text-search limits");
        }
    }
    static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static Hit hit(String path, String text, int offset, int line, int lineStart) {
        int start=Math.max(lineStart,offset-40);
        if (start>0 && Character.isLowSurrogate(text.charAt(start)) && Character.isHighSurrogate(text.charAt(start-1))) start++;
        int end=Math.min(text.length(),start+MAX_EXCERPT_CHARACTERS);
        for (int i=start;i<end;i++) if (text.charAt(i)=='\r' || text.charAt(i)=='\n') { end=i;break; }
        if (end>start && end<text.length() && Character.isHighSurrogate(text.charAt(end-1)) && Character.isLowSurrogate(text.charAt(end))) end--;
        boolean after=end<text.length() && text.charAt(end)!='\r' && text.charAt(end)!='\n';
        return new Hit(path,line,offset-lineStart+1,offset,text.substring(start,end),start>lineStart,after);
    }
    static boolean eligible(String path,Query query,java.util.function.Predicate<String> changed) {
        return path.toLowerCase(Locale.ROOT).endsWith(".json") && query.family().matches(path) && (!query.changedOnly() || changed.test(path));
    }
    static Result search(PackResourceView current,Query query,java.util.function.Predicate<String> changed,java.util.function.BooleanSupplier cancelled) {
        return search(current,query,changed,new Limits(MAX_FILE_BYTES,MAX_TOTAL_BYTES,MAX_FILES,MAX_RESULTS),cancelled);
    }
    static Result search(PackResourceView current,Query query,java.util.function.Predicate<String> changed,Limits limits,java.util.function.BooleanSupplier cancelled) {
        Objects.requireNonNull(current);Objects.requireNonNull(query);Objects.requireNonNull(changed);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);
        int eligible=0,considered=0,scanned=0,matches=0,large=0,invalid=0,byteSkipped=0,fileSkipped=0;
        long bytes=0;var hits=new ArrayList<Hit>();
        for (String path : current.paths()) {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            if (!eligible(path,query,changed)) continue;
            eligible++;
            if (considered++>=limits.files()) { fileSkipped++;continue; }
            int size=current.size(path);
            if (size>limits.fileBytes()) { large++;continue; }
            if (size>limits.totalBytes()-bytes) { byteSkipped++;continue; }
            bytes+=size;String text;
            try { text=decode(current.read(path)); }
            catch (CharacterCodingException failure) { invalid++;continue; }
            if (text.chars().anyMatch(c -> Character.isISOControl(c) && c!='\r' && c!='\n' && c!='\t')) { invalid++;continue; }
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            scanned++;int cursor=0,line=1,lineStart=0;
            for (int offset=text.indexOf(query.text());offset>=0;offset=text.indexOf(query.text(),offset+query.text().length())) {
                if ((matches & 1023)==0 && cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                matches++;
                if (hits.size()>=limits.results()) continue;
                while (cursor<offset) {
                    char c=text.charAt(cursor++);
                    if (c=='\r') { if (cursor<offset && text.charAt(cursor)=='\n') cursor++;line++;lineStart=cursor; }
                    else if (c=='\n') { line++;lineStart=cursor; }
                }
                hits.add(hit(path,text,offset,line,lineStart));
            }
        }
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        return new Result(eligible,scanned,bytes,matches,large,invalid,byteSkipped,fileSkipped,hits);
    }
    public static final class Cache {
        private final Limits limits;
        private PackWorkspace workspace;
        private long revision=-1,saveRevision=-1;
        private Query previous;
        private Result result;
        public Cache() { this(new Limits(MAX_FILE_BYTES,MAX_TOTAL_BYTES,MAX_FILES,MAX_RESULTS)); }
        Cache(Limits limits) { this.limits=Objects.requireNonNull(limits); }
        public void clear() { workspace=null;revision=-1;saveRevision=-1;previous=null;result=null; }
        public Result query(PackWorkspace current, Query query) {
            Objects.requireNonNull(current);Objects.requireNonNull(query);
            if (workspace==current && revision==current.revision() && query.equals(previous)
                    && (!query.changedOnly() || saveRevision==current.saveRevision())) return result;
            result=search(current,query,current::changed,limits,() -> false);
            workspace=current;revision=current.revision();saveRevision=current.saveRevision();previous=query;return result;
        }
    }
}
