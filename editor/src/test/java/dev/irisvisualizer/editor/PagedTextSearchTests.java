package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class PagedTextSearchTests {
    private static final String A="future/a.json",B="future/b.JSON";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static WorkspaceTextSearch.Query query(String text) { return new WorkspaceTextSearch.Query(text,ResourceFinder.Family.ALL,false); }
    private static void check(boolean value,String reason) { if(!value)throw new AssertionError("Paged text search: "+reason); }
    private static void rejected(Runnable action) { try { action.run(); }catch(IllegalArgumentException|IllegalStateException|NullPointerException expected) { return; }throw new AssertionError("Expected rejected search input"); }
    private static Map<String,byte[]> fixture() { return Map.of(A,bytes("😀\r\n"+"Needle x\r\n".repeat(401)),B,bytes("\r"+"雪 Needle\n".repeat(200)),"future/bad.json",new byte[]{(byte)0xc3,0x28},"future/control.json",bytes("Needle\u0000"),"future/large.json",new byte[WorkspaceTextSearch.MAX_FILE_BYTES+1],"objects/exact.iob",new byte[]{0,-1,5}); }
    private static AsyncWorkspaceTextSearch.Snapshot ready(AsyncWorkspaceTextSearch search)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);AsyncWorkspaceTextSearch.Snapshot state;
        while((state=search.poll()).phase()==AsyncWorkspaceTextSearch.Phase.SEARCHING && System.nanoTime()<end)Thread.sleep(1);
        check(state.phase()==AsyncWorkspaceTextSearch.Phase.READY,"background page completes: "+state.phase()+" / "+state.error());return state;
    }
    private static void await(CountDownLatch latch)throws Exception { check(latch.await(10,TimeUnit.SECONDS),"test gate reached"); }
    private static void ignoreInterrupts(CountDownLatch latch) { boolean done=false;while(!done)try { done=latch.await(10,TimeUnit.SECONDS);if(!done)throw new AssertionError("Test gate timed out"); }catch(InterruptedException ignored) {} }
    private static final class Gate {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        volatile java.util.function.BooleanSupplier cancelled;
    }
    static void run(Path temp)throws Exception {
        var files=fixture();var pack=PackWorkspace.newPack(files);var q=query("Needle");var hits=new ArrayList<WorkspaceTextSearch.Hit>();
        for(int page=0;page<4;page++) {
            var found=WorkspaceTextSearch.searchPage(pack,q,pack::changed,page,()->false);
            check(found.window().page()==page && found.window().matches()==601 && found.result().hits().size()==(page==3 ? 1 : 200),"complete ordered bounded page");
            check(found.result().tooLarge()==1 && found.result().invalidText()==2 && found.result().scannedFiles()==2 && found.result().incomplete(),"skip/coverage counts independent of page");hits.addAll(found.result().hits());
        }
        check(hits.size()==601 && hits.stream().map(h->h.path()+":"+h.offset()).distinct().count()==601,"all counted matches reachable exactly once");
        check(new WorkspaceTextSearch.Cache().query(pack,q).hits().equals(hits.subList(0,200)),"legacy first-page locations unchanged");
        for(var hit:hits) { var view=SourceMatch.inspect(pack,SourceMatch.request(pack,q,hit));check(view.matched().equals("Needle"),"every later location retains exact UTF-16 line/column and source literal"); }
        var last=hits.getLast();check(last.path().equals(B) && last.line()==201 && last.column()==3,"CR/LF/CRLF, Unicode and cross-file page location");
        check(WorkspaceTextSearch.searchPage(pack,q,pack::changed,Integer.MIN_VALUE,()->false).result().hits().equals(hits.subList(0,200)) && WorkspaceTextSearch.searchPage(pack,q,pack::changed,Integer.MAX_VALUE,()->false).result().hits().equals(List.of(last)),"extreme page requests clamp without overflow");
        check(WorkspaceTextSearch.searchPage(pack,query("absent"),pack::changed,Integer.MAX_VALUE,()->false).window().firstRow()==0,"empty searches clamp safely");
        try { WorkspaceTextSearch.searchPage(pack,q,pack::changed,3,()->true);throw new AssertionError(); }catch(CancellationException expected) {}
        try { WorkspaceTextSearch.searchPage(pack,q,pack::changed,1,()->false).result().hits().clear();throw new AssertionError(); }catch(UnsupportedOperationException expected) {}
        // Dense matches retain excerpts only for the requested page; no per-match index is allocated.
        var dense=PackWorkspace.newPack(Map.of("dense.json",bytes("x".repeat(WorkspaceTextSearch.MAX_FILE_BYTES))));var densePage=WorkspaceTextSearch.searchPage(dense,query("x"),dense::changed,Integer.MAX_VALUE,()->false);
        check(densePage.result().matches()==WorkspaceTextSearch.MAX_FILE_BYTES && densePage.result().hits().size()<=200 && densePage.result().hits().getLast().offset()==WorkspaceTextSearch.MAX_FILE_BYTES-1 && densePage.result().hits().getLast().column()==WorkspaceTextSearch.MAX_FILE_BYTES,"one-MiB dense source stays bounded while exposing last match");
        var reads=new AtomicInteger();PackResourceView counted=new PackResourceView() { public Set<String> paths(){return dense.paths();}public int size(String path){return dense.size(path);}public byte[] read(String path){reads.incrementAndGet();return dense.read(path);} };
        try { WorkspaceTextSearch.searchPage(counted,query("x"),path->true,Integer.MAX_VALUE,()->reads.get()>=2);throw new AssertionError(); }catch(CancellationException expected) { check(reads.get()==2,"clamp replay remains cancellable"); }
        // Page 1 active, page 2 pending, page 3 latest: only 0,1,3 execute using the same immutable capture.
        var gate=new Gate();var calls=Collections.synchronizedList(new ArrayList<Integer>());var sources=Collections.synchronizedList(new ArrayList<PackResourceView>());var active=new AtomicInteger();var maximum=new AtomicInteger();Thread caller=Thread.currentThread();
        try(var search=new AsyncWorkspaceTextSearch((source,selected,page,cancelled)->{
            check(Thread.currentThread()!=caller && !(source instanceof PackWorkspace),"page preparation is off-UI on a private source");calls.add(page);sources.add(source);maximum.accumulateAndGet(active.incrementAndGet(),Math::max);
            try { if(page==1) { gate.cancelled=cancelled;gate.entered.countDown();ignoreInterrupts(gate.release); }return WorkspaceTextSearch.searchPage(source,selected,path->true,page,()->false); }finally {active.decrementAndGet();}
        },r->new Thread(r,"paged-text-latest-test"))) {
            search.select(pack,q);var first=ready(search);var loading=search.page(pack,q,1);await(gate.entered);check(loading.result()==null && loading.window().page()==1,"old hits hidden during page preparation");
            search.page(pack,q,2);var latest=search.page(pack,q,3);check(search.page(pack,q,Integer.MAX_VALUE)==latest && latest.request()>loading.request() && gate.cancelled.getAsBoolean(),"latest clamped page replaces pending request and cancels active page");
            var drafts=new InspectorDrafts();drafts.update("late","stored","typing");long revision=pack.revision(),saved=pack.saveRevision();pack.saveCopy(temp.resolve("paged-text-copy"));check(search.select(pack,q)==latest && drafts.any(),"Save Copy and drafts retain the in-flight page/capture");
            gate.release.countDown();var finished=ready(search);check(finished.window().page()==3 && finished.result().hits().equals(List.of(last)) && calls.equals(List.of(0,1,3)) && maximum.get()==1,"one active worker/latest pending with stale page rejection");
            check(sources.stream().allMatch(source->source==sources.getFirst()) && search.select(pack,q)==finished && pack.revision()==revision && pack.saveRevision()==saved,"paging reuses the identical private capture and does not recapture live workspace");
            var nav=new ResourceNavigation();nav.request(pack,SourceMatch.request(pack,q,finished.result().hits().getFirst()));var request=nav.takeRequest(pack);var selected=new AtomicReference<>(A);
            check(!drafts.transition(()->false,()->selected.set(request.target())) && selected.get().equals(A) && drafts.any(),"last-page source navigation retains late drafts on cancellation");
            check(Arrays.equals(Files.readAllBytes(temp.resolve("paged-text-copy").resolve(A)),files.get(A)) && Arrays.equals(pack.read("objects/exact.iob"),new byte[]{0,-1,5}),"exact captured unknown/opaque persistence");
        }finally {gate.release.countDown();}
        // Noncooperative pages cannot publish after edit, equal-revision owner/query switch, clear or close.
        for(int mode=0;mode<5;mode++) {
            var current=PackWorkspace.newPack(files);var blocked=new Gate();var count=new AtomicInteger();int choice=mode;
            var search=new AsyncWorkspaceTextSearch((source,selected,page,cancelled)->{count.incrementAndGet();if(page==1){blocked.cancelled=cancelled;blocked.entered.countDown();ignoreInterrupts(blocked.release);}return WorkspaceTextSearch.searchPage(source,selected,path->true,page,()->false);},r->new Thread(r,"paged-text-invalidation-test"));
            try {
                search.select(current,q);ready(search);var old=search.page(current,q,1);await(blocked.entered);
                if(choice==0) { current.replace(A,bytes("replacement Needle"));check(search.poll(current).phase()==AsyncWorkspaceTextSearch.Phase.IDLE && count.get()==2,"frame-end poll discards hidden stale capture without scheduling a new search");search.select(current,q); }
                else if(choice==1) { var foreign=PackWorkspace.newPack(files);check(foreign.revision()==current.revision(),"foreign owner shares revision");search.select(foreign,q); }
                else if(choice==2)search.select(current,query("Needle x"));
                else if(choice==3)search.clear();
                else {long start=System.nanoTime();search.close();check(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1),"closing never joins blocked page");}
                check(blocked.cancelled.getAsBoolean(),"invalidation cancels active page");blocked.release.countDown();
                if(choice<3){var state=ready(search);check(state.request()>old.request() && state.window().page()==0 && (choice!=0 || state.result().matches()==201) && (choice!=2 || state.result().matches()==401),"replacement starts a fresh first page, never old page completion");}
                else {Thread.sleep(10);check(search.poll().phase()==(choice==3 ? AsyncWorkspaceTextSearch.Phase.IDLE : AsyncWorkspaceTextSearch.Phase.CLOSED) && search.poll().result()==null,"cleared/closed page cannot republish");}
            }finally {blocked.release.countDown();search.close();}
        }
        // Failed pages stay stable; only explicit Search/retry captures again and restarts at the first page.
        var attempts=new AtomicInteger();var failOnce=new AtomicBoolean(true);try(var search=new AsyncWorkspaceTextSearch((source,selected,page,cancelled)->{attempts.incrementAndGet();if(page==1 && failOnce.getAndSet(false))throw new IllegalArgumentException("page failure\n"+"x".repeat(1000));return WorkspaceTextSearch.searchPage(source,selected,path->true,page,cancelled);},r->new Thread(r,"paged-text-retry-test"))) {
            search.select(pack,q);ready(search);search.page(pack,q,1);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(search.poll().phase()==AsyncWorkspaceTextSearch.Phase.SEARCHING && System.nanoTime()<until)Thread.sleep(1);var failed=search.poll();
            check(failed.phase()==AsyncWorkspaceTextSearch.Phase.FAILED && failed.error().length()<=515 && !failed.error().contains("\n") && search.select(pack,q)==failed && search.page(pack,q,0)==failed && attempts.get()==2,"stable bounded page error without implicit retry");
            search.retry(pack,q);check(ready(search).window().page()==0,"explicit retry resets first page");search.page(pack,q,1);check(ready(search).window().page()==1 && attempts.get()==4,"failed page can be prepared after explicit retry");
        }
        // Changed-only Save and partial-save revision changes reset a paged result even with unchanged applied revision.
        var partial=PackWorkspace.newPack(Map.of("a.json",bytes("Needle".repeat(401)),"z.json",bytes("Needle".repeat(200))));Path root=temp.resolve("paged-text-baseline");partial.save(root);partial.replace("a.json",bytes("Needle".repeat(402)));partial.replace("z.json",bytes("Needle".repeat(201)));var changed=new WorkspaceTextSearch.Query("Needle",ResourceFinder.Family.ALL,true);
        try(var search=new AsyncWorkspaceTextSearch()) {
            search.select(partial,changed);ready(search);search.page(partial,changed,3);var old=ready(search);long revision=partial.revision(),saved=partial.saveRevision();
            try{partial.save(root,(base,path,data,baseline)->{if(path.equals("z.json"))throw new java.io.IOException("later failure");return FileSaves.write(base,path,data,baseline);});throw new AssertionError();}catch(java.io.IOException expected){}
            search.select(partial,changed);var refreshed=ready(search);check(partial.revision()==revision && partial.saveRevision()>saved && refreshed.request()>old.request() && refreshed.window().page()==0,"partial Save baseline resets retained capture/page");
            partial.save(root);search.select(partial,changed);check(ready(search).result().eligibleFiles()==0 && search.poll().window().page()==0,"successful Save resets changed-only empty result");
        }
        rejected(()->new WorkspaceTextSearch.Page(ReviewPagination.Window.at(0,0),new WorkspaceTextSearch.Result(0,0,0,1,0,0,0,0,List.of())));
        System.out.println("PASS: background JSON text-result paging with every bounded-search match reachable and only 200 excerpts retained, dense one-MiB last-match and clamped replay cancellation, exact Unicode/CR-LF/source locations across file/page boundaries, unchanged skip/coverage/first-page contracts, identical private capture reuse without live recapture, one-active/latest-pending and stale noncooperative page rejection, edit/query/equal-owner/clear/close/Save/partial-save invalidation, stable errors/explicit retry, guarded later-page source navigation and exact unknown/opaque preservation; no file writes or native operations while paging");
    }
}
