package dev.irisvisualizer.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded literal line comparison of one retained edit. No JSON parsing, merge or restore operation. */
public final class AppliedEditDiff {
    public static final int MAX_LINES=512,MAX_CELLS=(MAX_LINES+1)*(MAX_LINES+1),PAGE_SIZE=200;
    public enum Status { READY, UNCHANGED, UNAVAILABLE, LIMITED }
    public enum Kind { ADDED, REMOVED, UNCHANGED }
    public enum Ending {
        LF("\n"), CRLF("\r\n"), CR("\r"), NONE("");
        private final String text;Ending(String text) { this.text=text; }public String text() { return text; }
    }
    public record Row(Kind kind,int beforeLine,int afterLine,String text,Ending ending) {}
    public record Diff(Status status,int beforeLines,int afterLines,int added,int removed,int unchanged,List<Row> rows,String detail) {
        public Diff { rows=List.copyOf(rows); }
        public boolean compared() { return status==Status.READY || status==Status.UNCHANGED; }
    }
    public record Page(int page,int matches,List<Integer> rows) {
        public Page { rows=List.copyOf(rows); }
        public boolean previous() { return page>0; }
        public boolean next() { return (long)(page+1)*PAGE_SIZE<matches; }
    }
    record Limits(int lines,int cells) {
        Limits { if (lines<1 || lines>MAX_LINES || cells<1 || cells>MAX_CELLS) throw new IllegalArgumentException("Invalid applied-edit line comparison limits"); }
    }
    private record Line(String text,Ending ending) {}
    private static boolean complete(AppliedEditReview.Text text) {
        return text!=null && text.kind()==AppliedEditReview.Kind.TEXT && !text.truncated() && text.bytes()>=0 && text.bytes()<=AppliedEditReview.MAX_TEXT_BYTES && text.text()!=null && text.text().length()<=AppliedEditReview.MAX_TEXT_CHARACTERS;
    }
    private static int count(String text) {
        int lines=0,start=0;
        for (int i=0;i<text.length();i++) if (text.charAt(i)=='\r' || text.charAt(i)=='\n') {
            lines++;if (text.charAt(i)=='\r' && i+1<text.length() && text.charAt(i+1)=='\n') i++;start=i+1;
        }
        return lines+(start<text.length() ? 1 : 0);
    }
    private static List<Line> lines(String text) {
        var lines=new ArrayList<Line>();int start=0;
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);if (c!='\r' && c!='\n') continue;
            String content=text.substring(start,i);Ending ending=c=='\n' ? Ending.LF : Ending.CR;
            if (c=='\r' && i+1<text.length() && text.charAt(i+1)=='\n') { ending=Ending.CRLF;i++; }
            lines.add(new Line(content,ending));start=i+1;
        }
        if (start<text.length()) lines.add(new Line(text.substring(start),Ending.NONE));return lines;
    }
    public static Diff compare(AppliedEditReview.Comparison comparison) { return compare(comparison,new Limits(MAX_LINES,MAX_CELLS)); }
    static Diff compare(AppliedEditReview.Comparison comparison,Limits limits) {
        Objects.requireNonNull(comparison);Objects.requireNonNull(limits);
        if (!complete(comparison.before()) || !complete(comparison.after())) return new Diff(Status.UNAVAILABLE,0,0,0,0,0,List.of(),"Line comparison requires complete text on both sides. Clipped, binary/control, invalid UTF-8 or oversized snapshots remain available in Before / after source as text or metadata.");
        String before=comparison.before().text(),after=comparison.after().text();int left=count(before),right=count(after);long cells=(long)(left+1)*(right+1);
        if (left>limits.lines() || right>limits.lines() || cells>limits.cells()) return new Diff(Status.LIMITED,left,right,0,0,0,List.of(),"Line comparison exceeds the "+limits.lines()+"-line per-side or "+limits.cells()+"-cell work budget. No partial change counts are reported; use Before / after source.");
        var a=lines(before);var b=lines(after);int columns=right+1;var lengths=new int[(int)cells];
        for (int i=left-1;i>=0;i--) for (int j=right-1;j>=0;j--) lengths[i*columns+j]=a.get(i).equals(b.get(j)) ? 1+lengths[(i+1)*columns+j+1] : Math.max(lengths[(i+1)*columns+j],lengths[i*columns+j+1]);
        var rows=new ArrayList<Row>();int i=0,j=0,added=0,removed=0,unchanged=0;
        while (i<left || j<right) {
            if (i<left && j<right && a.get(i).equals(b.get(j))) {
                var line=a.get(i);rows.add(new Row(Kind.UNCHANGED,i+1,j+1,line.text(),line.ending()));i++;j++;unchanged++;
            } else if (i<left && (j==right || lengths[(i+1)*columns+j]>=lengths[i*columns+j+1])) {
                // Stable tie break: removal precedes addition when both retain the same number of lines.
                var line=a.get(i);rows.add(new Row(Kind.REMOVED,i+1,0,line.text(),line.ending()));i++;removed++;
            } else {
                var line=b.get(j);rows.add(new Row(Kind.ADDED,0,j+1,line.text(),line.ending()));j++;added++;
            }
        }
        return new Diff(added+removed==0 ? Status.UNCHANGED : Status.READY,left,right,added,removed,unchanged,rows,"Literal lines include their original CR/LF/CRLF or absent final newline. Counts describe this edit only; source spelling and whitespace are significant.");
    }
    public static Page page(Diff diff,boolean showUnchanged,int requested) {
        Objects.requireNonNull(diff);if (requested<0) throw new IllegalArgumentException("Use a nonnegative line page");var matches=new ArrayList<Integer>();
        for (int i=0;i<diff.rows().size();i++) if (showUnchanged || diff.rows().get(i).kind()!=Kind.UNCHANGED) matches.add(i);
        int page=Math.min(requested,Math.max(0,(matches.size()-1)/PAGE_SIZE)),start=page*PAGE_SIZE;
        return new Page(page,matches.size(),matches.subList(start,Math.min(matches.size(),start+PAGE_SIZE)));
    }
    /** The immutable retained comparison supplies identity; Save/Undo do not recompute unchanged review text. */
    public static final class Cache {
        private AppliedEditReview.Comparison comparison;
        private Diff diff;
        public void clear() { comparison=null;diff=null; }
        public Diff inspect(AppliedEditReview.Comparison current) { if (current==null) { clear();return null; }if (comparison!=current) { diff=compare(current);comparison=current; }return diff; }
    }
}
