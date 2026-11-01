package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

final class SharedSourceIndexTests {
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError("Shared source index: "+message);}
    private static void rejects(Runnable action){try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected invalid source location/query");}
    private static SourceTextPages.Position position(String text,int offset){int line=1,column=1;for(int i=0;i<offset;i++){char c=text.charAt(i);if(c=='\r'){line++;column=1;}else if(c=='\n'){if(i==0 || text.charAt(i-1)!='\r')line++;column=1;}else column++;}return new SourceTextPages.Position(line,column);}
    static void run(Path temp)throws Exception{
        for(String text:List.of("","a\r\nb\rc\nd\r\n","雪😀\r\n".repeat(9000),"\n".repeat(100000),"{\"long\":\""+"雪😀".repeat(15000)+"\",\"tail\":9007199254740993}")){
            var snapshot=JsonStructure.inspect(bytes(text));var index=SourceTextPages.index(snapshot,()->false);check(index.lines()==position(text,text.length()).line(),"global line count");
            // Exhaustive small input positions include the caret between CR and LF; large inputs sample all boundaries.
            int step=text.length()<100?1:Math.max(1,text.length()/1000);for(int offset=0;offset<=text.length();offset+=step)check(index.position(offset).equals(position(text,offset)),"global UTF-16 position "+offset);check(index.position(text.length()).equals(position(text,text.length())),"end caret");
            var shared=SourceTextPages.whole(snapshot,index);var legacy=SourceTextPages.whole(snapshot);var joined=new StringBuilder();for(int page=0;page<shared.pages();page++){var row=shared.page(page);check(row.equals(legacy.page(page)) && row==shared.page(page),"all exact shared/legacy pages and one-page cache");joined.append(row.text());}check(joined.toString().equals(text),"all original characters reachable");
            for(int line=1;line<=index.lines();line+=Math.max(1,index.lines()/1000)){check(index.position(index.lineStart(line)).line()==line && shared.linePage(line)==legacy.linePage(line),"line starts/jumps reuse immutable global metadata");}
            if(snapshot.indexed())for(var node:snapshot.nodes()){var prepared=SourceTextPages.token(snapshot,node.id(),index);var original=SourceTextPages.token(snapshot,node.id());for(int page=0;page<prepared.pages();page++)check(prepared.page(page).equals(original.page(page)),"token positions and CRLF/surrogate boundaries");}
            rejects(()->index.position(-1));rejects(()->index.position(text.length()+1));rejects(()->index.lineStart(0));rejects(()->index.lineStart(index.lines()+1));
            var equalSource=new JsonStructure.Snapshot(snapshot.status(),snapshot.bytes(),new String(snapshot.source()),snapshot.nodes(),snapshot.detail());rejects(()->SourceTextPages.whole(equalSource,index));
        }
        var maximum=JsonStructure.inspect(bytes("\n".repeat(1_000_000)));var checks=new AtomicInteger();try{SourceTextPages.index(maximum,()->checks.incrementAndGet()>=4);throw new AssertionError();}catch(CancellationException expected){check(checks.get()==4,"line metadata cancellation inside full-source traversal");}var lines=SourceTextPages.index(maximum,()->false);check(lines.lines()==1_000_001 && lines.lineStart(1_000_001)==1_000_000 && SourceTextPages.whole(maximum,lines).linePage(1_000_001)==61,"exact one-million-byte/source-line/end-caret bounds");
        // Complete bounded node paging, including query filtering, clamped last page and stable original IDs.
        String members=String.join(",",java.util.stream.IntStream.range(0,900).mapToObj(i->"\"member_"+i+"\":\"value_"+i+"\"").toList());var snapshot=JsonStructure.inspect(bytes("{"+members+"}"));var cache=new JsonStructure.PageCache();var first=cache.page(snapshot,0,"",0);var last=cache.page(snapshot,0,"",Integer.MAX_VALUE);check(first.nodes().size()==200 && last.nodes().size()==100 && last.page()==4 && last.nodes().getLast()==900,"full 900-member First/Last/jump semantics");var all=new ArrayList<Integer>();for(int page=0;page<=last.page();page++)all.addAll(cache.page(snapshot,0,"",page).nodes());check(all.equals(snapshot.node(0).children()),"original stable child order, complete reachability");
        var accepted=cache.page(snapshot,0,"member_8",0);for(int i=0;i<2;i++){rejects(()->cache.page(snapshot,0,"x".repeat(257),0));rejects(()->cache.page(snapshot,0,"bad\0",0));rejects(()->cache.page(snapshot,0,"",-1));check(cache.page(snapshot,0,"member_8",0)==accepted,"invalid repeated queries never replace accepted owner/query/page cache");}check(cache.page(snapshot,0,".*",0).matches()==0,"literal indexed search");
        System.out.println("PASS: shared worker-prepared source line index with exact CR/LF/CRLF/Unicode UTF-16 positions, all legacy-equivalent whole/token pages and global jumps without full-source rescans, immutable source ownership, one-million-byte/line/end-caret bounds and cooperative indexing cancellation, complete stable 900-node paged selection and invalid-query cache retention; no source edits or previews");
    }
}
