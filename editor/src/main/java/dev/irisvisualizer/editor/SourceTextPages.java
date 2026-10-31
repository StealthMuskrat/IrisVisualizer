package dev.irisvisualizer.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
    private final String source;private final int start,end;private final List<Boundary> boundaries;private Page cached;
    public static boolean available(JsonStructure.Snapshot snapshot){return snapshot!=null && (snapshot.status()==JsonStructure.Status.READY || snapshot.status()==JsonStructure.Status.LIMITED || snapshot.status()==JsonStructure.Status.UNAVAILABLE);}
    public static SourceTextPages whole(JsonStructure.Snapshot snapshot){require(snapshot);return new SourceTextPages(snapshot.source(),0,snapshot.source().length(),PAGE_CHARACTERS);}
    public static SourceTextPages token(JsonStructure.Snapshot snapshot,int id){require(snapshot);var node=snapshot.node(id);return new SourceTextPages(snapshot.source(),node.start(),node.end(),PAGE_CHARACTERS);}
    private static void require(JsonStructure.Snapshot snapshot){if(!available(snapshot))throw new IllegalArgumentException("Strict JSON source text is unavailable for paging");}
    SourceTextPages(String source,int start,int end,int characters){
        this.source=Objects.requireNonNull(source);if(source.length()>JsonStructure.MAX_BYTES || start<0 || end<start || end>source.length() || characters<2 || characters>PAGE_CHARACTERS || split(source,start) || split(source,end))throw new IllegalArgumentException("Invalid source span or page limit");this.start=start;this.end=end;
        var offsets=new ArrayList<Integer>();offsets.add(start);for(int offset=start;offset<end;){int next=(int)Math.min((long)end,(long)offset+characters);if(split(source,next))next--;offsets.add(next);offset=next;}
        var positions=new ArrayList<Boundary>(offsets.size());int cursor=0,line=1,column=1;
        for(int offset:offsets){while(cursor<offset){char c=source.charAt(cursor++);if(c=='\r'){line++;column=1;}else if(c=='\n'){if(cursor<2 || source.charAt(cursor-2)!='\r')line++;column=1;}else column++;}positions.add(new Boundary(offset,new Position(line,column)));}
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
        int line=1,offset=0;while(line<requested && offset<source.length()){char c=source.charAt(offset++);if(c=='\r'){if(offset<source.length() && source.charAt(offset)=='\n')offset++;line++;}else if(c=='\n')line++;}
        return offsetPage(Math.max(start,offset));
    }
    public int linePage(String input){return linePage(number(input,true));}
    private static int number(String input,boolean positive){
        if(input==null)throw new IllegalArgumentException("Enter a whole source location");String text=input.strip();if(text.isEmpty() || text.length()>10 || !text.chars().allMatch(c->c>='0' && c<='9'))throw new IllegalArgumentException("Use up to ten decimal digits for a source location");long value=Long.parseLong(text);if(value>Integer.MAX_VALUE || positive && value==0)throw new IllegalArgumentException("Source lines start at one; offsets start at zero, within this span");return (int)value;
    }
}
