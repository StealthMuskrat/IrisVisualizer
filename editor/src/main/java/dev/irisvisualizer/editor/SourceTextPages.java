package dev.irisvisualizer.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;

/** Bounded pages of existing strict decoded source. Only page text is sliced; the whole/token range stays shared. */
public final class SourceTextPages {
    public static final int PAGE_CHARACTERS=JsonStructure.MAX_PREVIEW_CHARACTERS;
    public record Position(int line,int column) {}
    private record Boundary(int offset,Position position) {}
    public record Page(int page,int pages,int start,int end,int spanStart,int spanEnd,Position from,Position to,String text) {
        public Page {Objects.requireNonNull(from);Objects.requireNonNull(to);Objects.requireNonNull(text);if(page<0 || pages<1 || page>=pages || spanStart<0 || start<spanStart || end<start || end>spanEnd || text.length()!=end-start || text.length()>PAGE_CHARACTERS)throw new IllegalArgumentException("Invalid source text page");}
        public boolean previous(){return page>0;}
        public boolean next(){return page+1<pages;}
        public int totalCharacters(){return spanEnd-spanStart;}
    }
    /** Shared immutable source-global line metadata, prepared once on the source worker. */
    public static final class Index {
        private final String source;private final int[] starts;
        private Index(String source,BooleanSupplier cancelled){
            this.source=Objects.requireNonNull(source);if(source.length()>JsonStructure.MAX_BYTES)throw new IllegalArgumentException("Source index exceeds 1 MB");
            int count=1;for(int i=0;i<source.length();i++){if((i&4095)==0)check(cancelled);char c=source.charAt(i);if(c=='\r'){if(i+1<source.length() && source.charAt(i+1)=='\n')i++;count++;}else if(c=='\n')count++;}
            starts=new int[count];int line=1;for(int i=0;i<source.length();i++){if((i&4095)==0)check(cancelled);char c=source.charAt(i);if(c=='\r'){if(i+1<source.length() && source.charAt(i+1)=='\n')i++;starts[line++]=i+1;}else if(c=='\n')starts[line++]=i+1;}check(cancelled);
        }
        private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancellationException("Source line indexing cancelled");}
        public int lines(){return starts.length;}
        public Position position(int offset){
            if(offset<0 || offset>source.length())throw new IllegalArgumentException("Source offset out of range");
            if(offset>0 && offset<source.length() && source.charAt(offset-1)=='\r' && source.charAt(offset)=='\n')offset++;
            int found=Arrays.binarySearch(starts,offset);int line=found>=0 ? found : -found-2;return new Position(line+1,offset-starts[line]+1);
        }
        public int lineStart(int line){if(line<1 || line>starts.length)throw new IllegalArgumentException("Source line out of range");return starts[line-1];}
    }
    public static Index index(JsonStructure.Snapshot snapshot,BooleanSupplier cancelled){require(snapshot);return new Index(snapshot.source(),Objects.requireNonNull(cancelled));}
    private final String source;private final int start,end;private final List<Boundary> boundaries;private final Index index;private Page cached;
    public static boolean available(JsonStructure.Snapshot snapshot){return snapshot!=null && (snapshot.status()==JsonStructure.Status.READY || snapshot.status()==JsonStructure.Status.LIMITED || snapshot.status()==JsonStructure.Status.UNAVAILABLE);}
    public static SourceTextPages whole(JsonStructure.Snapshot snapshot){require(snapshot);return new SourceTextPages(snapshot.source(),0,snapshot.source().length(),PAGE_CHARACTERS);}
    public static SourceTextPages token(JsonStructure.Snapshot snapshot,int id){require(snapshot);var node=snapshot.node(id);return new SourceTextPages(snapshot.source(),node.start(),node.end(),PAGE_CHARACTERS);}
    public static SourceTextPages whole(JsonStructure.Snapshot snapshot,Index index){require(snapshot);return new SourceTextPages(snapshot.source(),0,snapshot.source().length(),PAGE_CHARACTERS,index);}
    public static SourceTextPages token(JsonStructure.Snapshot snapshot,int id,Index index){require(snapshot);var node=snapshot.node(id);return new SourceTextPages(snapshot.source(),node.start(),node.end(),PAGE_CHARACTERS,index);}
    private static void require(JsonStructure.Snapshot snapshot){if(!available(snapshot))throw new IllegalArgumentException("Strict JSON source text is unavailable for paging");}
    SourceTextPages(String source,int start,int end,int characters){
        this(source,start,end,characters,new Index(source,()->false));
    }
    private SourceTextPages(String source,int start,int end,int characters,Index index){
        this.index=Objects.requireNonNull(index);if(index.source!=source)throw new IllegalArgumentException("Line index belongs to another source");
        this.source=Objects.requireNonNull(source);if(source.length()>JsonStructure.MAX_BYTES || start<0 || end<start || end>source.length() || characters<2 || characters>PAGE_CHARACTERS || split(source,start) || split(source,end))throw new IllegalArgumentException("Invalid source span or page limit");this.start=start;this.end=end;
        var offsets=new ArrayList<Integer>();offsets.add(start);for(int offset=start;offset<end;){int next=(int)Math.min((long)end,(long)offset+characters);if(split(source,next))next--;offsets.add(next);offset=next;}
        var positions=new ArrayList<Boundary>(offsets.size());for(int offset:offsets)positions.add(new Boundary(offset,index.position(offset)));
        boundaries=List.copyOf(positions);
    }
    private static boolean split(String source,int offset){return offset>0 && offset<source.length() && (Character.isHighSurrogate(source.charAt(offset-1)) && Character.isLowSurrogate(source.charAt(offset)) || source.charAt(offset-1)=='\r' && source.charAt(offset)=='\n');}
    public int pages(){return Math.max(1,boundaries.size()-1);}
    public Page page(int requested){int index=Math.clamp(requested,0,pages()-1);if(cached!=null && cached.page()==index)return cached;var a=boundaries.get(index);var b=boundaries.get(Math.min(index+1,boundaries.size()-1));return cached=new Page(index,pages(),a.offset(),b.offset(),start,end,a.position(),b.position(),source.substring(a.offset(),b.offset()));}
    /** Source-global zero-based UTF-16 offset; the span's end caret maps to its final page. */
    public int offsetPage(int offset){
        if(offset<start || offset>end)throw new IllegalArgumentException("Use a source UTF-16 offset from "+start+" through "+end);
        if(offset==end)return pages()-1;int low=0,high=boundaries.size()-1;while(low+1<high){int mid=(low+high)>>>1;if(boundaries.get(mid).offset()<=offset)low=mid;else high=mid;}return low;
    }
    public int offsetPage(String input){return offsetPage(number(input,false));}
    /** One-based source-global line; a token's first partial line maps to the token's own start. */
    public int linePage(int requested){
        int first=boundaries.getFirst().position().line(),last=boundaries.getLast().position().line();if(requested<first || requested>last)throw new IllegalArgumentException("Use a source line from "+first+" through "+last);
        int offset=index.lineStart(requested);
        return offsetPage(Math.max(start,offset));
    }
    public int linePage(String input){return linePage(number(input,true));}
    private static int number(String input,boolean positive){
        if(input==null)throw new IllegalArgumentException("Enter a whole source location");String text=input.strip();if(text.isEmpty() || text.length()>10 || !text.chars().allMatch(c->c>='0' && c<='9'))throw new IllegalArgumentException("Use up to ten decimal digits for a source location");long value=Long.parseLong(text);if(value>Integer.MAX_VALUE || positive && value==0)throw new IllegalArgumentException("Source lines start at one; offsets start at zero, within this span");return (int)value;
    }
}
