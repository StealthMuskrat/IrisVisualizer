package dev.irisvisualizer.editor;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Read-only JSON source index, independent of schema authoring and native resource validity. */
public final class JsonStructure {
    public static final int MAX_BYTES=1_000_000,MAX_NODES=4096,MAX_DEPTH=32,MAX_PREVIEW_CHARACTERS=16_384,
            MAX_LABEL_CHARACTERS=256,MAX_POINTER_CHARACTERS=16_384,MAX_QUERY_CHARACTERS=256,PAGE_SIZE=200;
    public enum Kind { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }
    public enum Status { READY, LIMITED, UNAVAILABLE, TOO_LARGE, INVALID_UTF8, CONTROL_TEXT, NOT_JSON }
    public record Node(int id,int parent,String segment,Kind kind,int depth,int start,int end,List<Integer> children,boolean limited) {
        public Node { children=List.copyOf(children); }
    }
    record Index(List<Node> nodes,boolean limited) { Index { nodes=List.copyOf(nodes); } }
    record Limits(int nodes,int depth) { Limits { if (nodes<1 || nodes>MAX_NODES || depth<1 || depth>MAX_DEPTH) throw new IllegalArgumentException("Invalid JSON index limits"); } }
    public record Preview(String text,int totalCharacters,boolean truncated) {}
    public record Pointer(String text,boolean complete) {}
    public record Page(int page,int matches,List<Integer> nodes) {
        public Page { nodes=List.copyOf(nodes); }
        public boolean previous() { return page>0; }
        public boolean next() { return (long)(page+1)*PAGE_SIZE<matches; }
    }
    public record Snapshot(Status status,int bytes,String source,List<Node> nodes,String detail) {
        public Snapshot { nodes=List.copyOf(nodes); }
        public boolean indexed() { return !nodes.isEmpty(); }
        public Node node(int id) { return nodes.get(id); }
        public Preview sourcePreview() { return preview(source,0,source.length()); }
        public Preview token(int id) { var n=node(id);return preview(source,n.start(),n.end()); }
        public String summary(int id) {
            var n=node(id);
            if (n.kind()==Kind.OBJECT || n.kind()==Kind.ARRAY) return n.children().size()+" indexed children"+(n.limited() ? "; more not indexed" : "");
            String token=prefix(source,n.start(),n.end(),MAX_LABEL_CHARACTERS);return InspectorText.label(token,MAX_LABEL_CHARACTERS)+(token.length()<n.end()-n.start() ? "..." : "");
        }
        public Pointer pointer(int id) {
            node(id);
            var path=new ArrayList<Integer>();for (int current=id;current>0;current=node(current).parent()) path.add(current);
            var out=new StringBuilder();
            for (int i=path.size()-1;i>=0;i--) {
                String segment=node(path.get(i)).segment();
                if (out.length()>=MAX_POINTER_CHARACTERS) return new Pointer(out.toString(),false);out.append('/');
                for (int offset=0;offset<segment.length();) {
                    int code=segment.codePointAt(offset);String next=code=='~' ? "~0" : code=='/' ? "~1" : new String(Character.toChars(code));
                    if (out.length()+next.length()>MAX_POINTER_CHARACTERS) return new Pointer(out.toString(),false);
                    out.append(next);offset+=Character.charCount(code);
                }
            }
            return new Pointer(out.toString(),true);
        }
        /** -1 means not indexed, not proof that the field is absent from the full source. */
        public int find(String pointer) {
            if (pointer==null || pointer.length()>MAX_POINTER_CHARACTERS) throw new IllegalArgumentException("Pointer exceeds the 16,384-character limit");
            if (!indexed()) return -1;if (pointer.isEmpty()) return 0;
            if (!pointer.startsWith("/")) throw new IllegalArgumentException("Use a JSON pointer starting with /, or empty for the root");
            String[] segments=pointer.substring(1).split("/",-1);if (segments.length>MAX_DEPTH) return -1;
            int current=0;
            for (String segment : segments) {
                var decoded=new StringBuilder();
                for (int i=0;i<segment.length();i++) {
                    char c=segment.charAt(i);if (c!='~') decoded.append(c);
                    else { if (++i>=segment.length() || segment.charAt(i)!='0' && segment.charAt(i)!='1') throw new IllegalArgumentException("JSON pointer escapes must be ~0 or ~1");decoded.append(segment.charAt(i)=='0' ? '~' : '/'); }
                }
                String key=decoded.toString();int found=-1;
                for (int child : node(current).children()) if (node(child).segment().equals(key)) { found=child;break; }
                if (found<0) return -1;current=found;
            }
            return current;
        }
        /** Empty query lists direct indexed children; nonempty query searches member/index names and scalar prefixes. */
        public Page page(int parent,String query,int requestedPage) {
            node(parent);
            if (query==null || query.length()>MAX_QUERY_CHARACTERS || query.indexOf(0)>=0 || requestedPage<0) throw new IllegalArgumentException("Use up to 256 search characters without NUL and a nonnegative page");
            String text=query.strip().toLowerCase(Locale.ROOT);var matches=new ArrayList<Integer>();
            for (var n : nodes) {
                if (text.isEmpty() ? n.parent()==parent : n.segment().toLowerCase(Locale.ROOT).contains(text) || (n.kind()!=Kind.OBJECT && n.kind()!=Kind.ARRAY && summary(n.id()).toLowerCase(Locale.ROOT).contains(text))) matches.add(n.id());
            }
            int page=Math.min(requestedPage,Math.max(0,(matches.size()-1)/PAGE_SIZE)),start=page*PAGE_SIZE;
            return new Page(page,matches.size(),matches.subList(start,Math.min(matches.size(),start+PAGE_SIZE)));
        }
    }
    private static String prefix(String source,int start,int end,int maximum) {
        int stop=Math.min(end,start+maximum);if (stop>start && stop<end && Character.isHighSurrogate(source.charAt(stop-1)) && Character.isLowSurrogate(source.charAt(stop))) stop--;
        return source.substring(start,stop);
    }
    private static Preview preview(String source,int start,int end) { String value=prefix(source,start,end,MAX_PREVIEW_CHARACTERS);return new Preview(value,end-start,value.length()<end-start); }
    public static boolean supports(String path) { return path!=null && path.toLowerCase(Locale.ROOT).endsWith(".json"); }
    public static Snapshot inspect(byte[] bytes) { return inspect(bytes,new Limits(MAX_NODES,MAX_DEPTH)); }
    static Snapshot inspect(byte[] bytes,Limits limits) {
        Objects.requireNonNull(bytes);Objects.requireNonNull(limits);
        if (bytes.length>MAX_BYTES) return new Snapshot(Status.TOO_LARGE,bytes.length,"",List.of(),"Structure/source display is limited to 1 MB; original bytes remain saveable/exportable.");
        String source;
        try { source=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException invalid) { return new Snapshot(Status.INVALID_UTF8,bytes.length,"",List.of(),"Source is not strict UTF-8; text display is unavailable."); }
        if (source.chars().anyMatch(c -> Character.isISOControl(c) && c!='\r' && c!='\n' && c!='\t')) return new Snapshot(Status.CONTROL_TEXT,bytes.length,"",List.of(),"Source contains control characters unsuitable for text display; original bytes are preserved.");
        try {
            var index=new LosslessJson(bytes).structure(limits.nodes(),limits.depth());
            return new Snapshot(index.limited() ? Status.LIMITED : Status.READY,bytes.length,source,index.nodes(),index.limited() ? "Some children are not indexed because node/depth limits were reached." : "Strict root-object source indexed. This is not schema/native validation.");
        } catch (IllegalArgumentException unavailable) { return new Snapshot(Status.UNAVAILABLE,bytes.length,source,List.of(),"Structure unavailable: "+InspectorText.label(unavailable.getMessage(),MAX_LABEL_CHARACTERS)); }
    }
    public static final class PageCache {
        private Snapshot snapshot;private int parent,requested;private String query;private Page page;
        public void clear() { snapshot=null;query=null;page=null; }
        public Page page(Snapshot current,int currentParent,String text,int number) {
            Objects.requireNonNull(current);
            if (snapshot!=current || parent!=currentParent || requested!=number || !Objects.equals(query,text) || page==null) { page=current.page(currentParent,text,number);snapshot=current;parent=currentParent;query=text;requested=number; }
            return page;
        }
    }
    public static final class Cache {
        private PackWorkspace workspace;private String path;private long revision=-1;private Snapshot snapshot;
        public void clear() { workspace=null;path=null;revision=-1;snapshot=null; }
        public Snapshot inspect(PackWorkspace current,String selected) {
            Objects.requireNonNull(current);
            if (workspace==current && Objects.equals(path,selected) && revision==current.revision() && snapshot!=null) return snapshot;
            boolean present=selected!=null && current.paths().contains(selected);
            snapshot=!present || !supports(selected) ? new Snapshot(Status.NOT_JSON,present ? current.size(selected) : 0,"",List.of(),"Select an existing .json resource to inspect its source.") : current.size(selected)>MAX_BYTES ? new Snapshot(Status.TOO_LARGE,current.size(selected),"",List.of(),"Structure/source display is limited to 1 MB; original bytes remain saveable/exportable.") : JsonStructure.inspect(current.read(selected));
            workspace=current;path=selected;revision=current.revision();return snapshot;
        }
    }
}
