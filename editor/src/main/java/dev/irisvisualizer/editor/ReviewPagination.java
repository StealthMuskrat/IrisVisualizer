package dev.irisvisualizer.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Read-only paging of recorded metadata. Retains one bounded page, never a full filtered copy. */
public final class ReviewPagination {
    public static final int PAGE_SIZE=200;
    public record Window(int page,int matches) {
        public Window { if(matches<0 || page<0 || page>Math.max(0,(matches-1)/PAGE_SIZE))throw new IllegalArgumentException("Invalid review page"); }
        public static Window at(int matches,int requested) {
            if(matches<0)throw new IllegalArgumentException("Invalid match count");
            return new Window(Math.clamp(requested,0,Math.max(0,(matches-1)/PAGE_SIZE)),matches);
        }
        public int pages() { return Math.max(1,(matches-1)/PAGE_SIZE+1); }
        public int start() { return page*PAGE_SIZE; }
        public int end() { return (int)Math.min((long)matches,(long)start()+PAGE_SIZE); }
        public int firstRow() { return matches==0 ? 0 : start()+1; }
        public boolean previous() { return page>0; }
        public boolean next() { return page+1<pages(); }
    }
    /** Positive one-based UI input; out-of-range values remain clamped by Window.at. */
    public static int requestedPage(String input) {
        Objects.requireNonNull(input);String text=input.strip();
        if(text.isEmpty() || text.length()>10 || !text.chars().allMatch(c->c>='0' && c<='9'))throw new IllegalArgumentException("Use a positive whole page number (up to ten digits)");
        long value=Long.parseLong(text);if(value==0)throw new IllegalArgumentException("Page numbers start at one");
        return (int)Math.min(Integer.MAX_VALUE,value-1);
    }
    public record Page<T>(Window window,List<T> entries) {
        public Page { Objects.requireNonNull(window);entries=List.copyOf(entries);if(entries.size()!=window.end()-window.start())throw new IllegalArgumentException("Incomplete review page"); }
    }
    public static <T> Page<T> select(List<T> values,Predicate<T> predicate,int requested) {
        Objects.requireNonNull(values);Objects.requireNonNull(predicate);
        int matches=0;for(var value:values)if(predicate.test(value))matches++;
        var window=Window.at(matches,requested);var shown=new ArrayList<T>(window.end()-window.start());
        if(matches>0) { int index=0;for(var value:values) { if(!predicate.test(value))continue;if(index>=window.start())shown.add(value);if(++index>=window.end())break; } }
        return new Page<>(window,shown);
    }
    private ReviewPagination() {}
}
