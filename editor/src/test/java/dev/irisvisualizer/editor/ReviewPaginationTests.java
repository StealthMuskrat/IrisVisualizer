package dev.irisvisualizer.editor;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class ReviewPaginationTests {
    private static final WorkspaceChanges.Query ALL=new WorkspaceChanges.Query("",WorkspaceChanges.Filter.ALL);
    private static void check(boolean value,String reason) { if(!value)throw new AssertionError("Review paging: "+reason); }
    private static void rejected(Runnable action) { try { action.run(); }catch(IllegalArgumentException|NullPointerException expected) { return; }throw new AssertionError("Expected invalid paging input"); }
    private static AsyncPendingDiskReview.Snapshot ready(AsyncPendingDiskReview review,PackWorkspace pack)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);AsyncPendingDiskReview.Snapshot state;
        while((state=review.poll(pack)).phase()==AsyncPendingDiskReview.Phase.CHECKING && System.nanoTime()<end)Thread.sleep(1);
        check(state.phase()==AsyncPendingDiskReview.Phase.READY,"review completes");return state;
    }
    static void run(Path temp)throws Exception {
        for(int count:new int[]{0,1,199,200,201,399,400,401,100_000,Integer.MAX_VALUE}) {
            var first=ReviewPagination.Window.at(count,Integer.MIN_VALUE);var last=ReviewPagination.Window.at(count,Integer.MAX_VALUE);
            check(first.page()==0 && first.matches()==count && !first.previous(),"negative page requests clamp to first");
            check(!last.next() && last.end()==count && last.end()-last.start()<=200 && last.firstRow()==(count==0 ? 0 : last.start()+1),"last-page boundaries and overflow-safe arithmetic");
            check(first.pages()==Math.max(1,(count==0 ? 0 : (count-1)/200+1)),"exact multiples and empty page count");
        }
        for(String input:List.of("0","-1","+1","1.5","1e2","1_000","","\u0661","1\n2","12345678901"))rejected(()->ReviewPagination.requestedPage(input));
        rejected(()->ReviewPagination.requestedPage(null));rejected(()->ReviewPagination.Window.at(-1,0));rejected(()->new ReviewPagination.Window(-1,1));rejected(()->new ReviewPagination.Window(1,200));
        check(ReviewPagination.requestedPage(" 1 ")==0 && ReviewPagination.requestedPage("0000000201")==200 && ReviewPagination.requestedPage("9999999999")==Integer.MAX_VALUE,"one-based whole-page input and bounded overflow clamping");
        var source=new ArrayList<Integer>();for(int i=0;i<1001;i++)source.add(i);
        var combined=new ArrayList<Integer>();for(int page=0;page<3;page++) { var result=ReviewPagination.select(source,n->n%2==0,page);check(result.entries().size()<=200,"bounded retained page");combined.addAll(result.entries()); }
        check(combined.equals(source.stream().filter(n->n%2==0).toList()),"ordered filtered pages contain every match exactly once");
        var detached=ReviewPagination.select(source,n->true,2);source.clear();check(detached.entries().getFirst()==400 && detached.entries().getLast()==599,"page does not retain mutable source slices");
        try { detached.entries().clear();throw new AssertionError(); }catch(UnsupportedOperationException expected) {}
        check(ReviewPagination.select(List.of(1,2),n->false,Integer.MAX_VALUE).window().firstRow()==0,"empty filter returns empty first page");
        rejected(()->ReviewPagination.select(null,n->true,0));rejected(()->ReviewPagination.select(List.of(1),null,0));rejected(()->new ReviewPagination.Page<>(ReviewPagination.Window.at(1,0),List.of()));
        var files=new LinkedHashMap<String,byte[]>();for(int i=0;i<625;i++)files.put(String.format(Locale.ROOT,"future/item%04d.bin",i),new byte[]{(byte)i,0,-1});
        files.put("blocks/base.json",new byte[]{1});
        var pack=PackWorkspace.newPack(files);pack.createSubfolder("blocks","empty");var cache=new WorkspaceChanges.Cache();var first=cache.page(pack,ALL,0);var last=cache.page(pack,ALL,Integer.MAX_VALUE);
        check(first.result().newFiles()==626 && first.result().newFolders()==1 && first.result().fileBytes()==1876 && last.result().matches()==627 && last.result().entries().size()==27,"global totals remain independent of selected page");
        check(cache.page(pack,ALL,Integer.MAX_VALUE)==last && cache.page(pack,ALL,3)==last,"clamped page cache identity");
        var paths=new ArrayList<String>();for(int i=0;i<4;i++)paths.addAll(cache.page(pack,ALL,i).result().entries().stream().map(WorkspaceChanges.Entry::path).toList());
        check(paths.equals(pack.pendingChanges().stream().map(WorkspaceChanges.Entry::path).toList()),"all pending file/folder metadata is reachable in path order");
        var filtered=cache.page(pack,new WorkspaceChanges.Query("ITEM06",WorkspaceChanges.Filter.FILES),Integer.MAX_VALUE);
        check(filtered.window().page()==0 && filtered.result().matches()==25 && filtered.result().total()==627,"filters clamp pages but retain whole-inventory totals");
        check(cache.page(pack,new WorkspaceChanges.Query("absent",WorkspaceChanges.Filter.ALL),3).result().entries().isEmpty(),"no stale entries after empty query");
        check(cache.query(pack,ALL).entries().equals(first.result().entries()),"existing query API remains the first bounded page");
        var drafts=new InspectorDrafts();drafts.update("late","stored","typing");long revision=pack.revision(),saved=pack.saveRevision(),resource=pack.resourceRevision();int undo=pack.undoCount();
        var captured=pack.capturePendingDiskReview(null);var report=PendingDiskReview.inspect(captured,()->false,(n,b)->{});var diskPaths=new ArrayList<String>();
        for(int i=0;i<4;i++)diskPaths.addAll(PendingDiskReview.filter(report,ALL,false,i).checks().stream().map(c->c.entry().path()).toList());
        check(diskPaths.equals(paths) && report.count(WorkspaceChanges.Status.NO_ACTIVE_FOLDER)==627 && report.bytesRead()==0,"every captured disk receipt is reachable without payload reads");
        check(PendingDiskReview.filter(report,new WorkspaceChanges.Query("ITEM06",WorkspaceChanges.Filter.NEW_FILES),true,Integer.MAX_VALUE).checks().size()==25,"path/kind/attention filtering before page boundaries");
        try { PendingDiskReview.filter(report,ALL,false,3).checks().clear();throw new AssertionError(); }catch(UnsupportedOperationException expected) {}
        var beyondFirst=PendingDiskReview.filter(report,ALL,false,3).checks().getLast();var navigation=new ResourceNavigation();navigation.request(pack,beyondFirst.entry().path());var request=navigation.takeRequest(pack);var selected=new java.util.concurrent.atomic.AtomicReference<>("blocks/base.json");
        check(!drafts.transition(()->false,()->selected.set(request.target())) && selected.get().equals("blocks/base.json") && drafts.any(),"opening a later disk-result page retains late drafts when cancelled");
        // Mixed receipt statuses keep full-report counts and filter attention before paging.
        var checks=new ArrayList<WorkspaceChanges.Check>();var counts=new EnumMap<WorkspaceChanges.Status,Integer>(WorkspaceChanges.Status.class);
        for(int i=0;i<801;i++) { var status=i%2==0 ? WorkspaceChanges.Status.CONFLICT : WorkspaceChanges.Status.BASELINE_MATCH;checks.add(new WorkspaceChanges.Check(new WorkspaceChanges.Entry("file"+i,WorkspaceChanges.Kind.MODIFIED_FILE,1),null,status,1,"captured"));counts.merge(status,1,Integer::sum); }
        var mixed=new PendingDiskReview.Report(null,checks,801,counts);var attention=PendingDiskReview.filter(mixed,ALL,true,2);
        check(attention.matches()==401 && attention.checks().size()==1 && attention.checks().getFirst().entry().path().equals("file800") && mixed.count(WorkspaceChanges.Status.BASELINE_MATCH)==400,"last attention-only match beyond 200 remains reachable");
        var scans=new AtomicInteger();try(var review=new AsyncPendingDiskReview((input,cancelled,progress)->{scans.incrementAndGet();return PendingDiskReview.inspect(input,cancelled,progress);},r->new Thread(r,"paged-review-test"))) {
            review.start(pack,null);var state=ready(review,pack);var lastDisk=review.query(pack,ALL,false,Integer.MAX_VALUE);
            check(review.query(pack,ALL,false,3)==lastDisk && review.poll(pack).report()==state.report() && scans.get()==1,"paging caches one page and retains original receipt without rescan");
            for(int i=0;i<4;i++)review.query(pack,ALL,i%2==0,i);check(scans.get()==1 && review.query(pack,ALL,false).window().page()==0,"filter/page access never schedules work");
            pack.replace("future/item0000.bin",new byte[]{4});check(review.query(pack,ALL,false,3)==null && scans.get()==1,"late applied edit invalidates every page without automatic rescan");pack.undo();check(review.query(pack,ALL,false,0)==null,"Undo does not resurrect old receipts");
            review.start(pack,null);ready(review,pack);
            var foreign=PackWorkspace.newPack(files);foreign.createSubfolder("blocks","empty");foreign.replace("future/item0000.bin",new byte[]{4});foreign.undo();
            check(foreign.revision()==pack.revision() && scans.get()==2 && review.query(foreign,ALL,false,3)==null,"equal-content/equal-revision foreign owner cannot reuse old pages");
        }
        check(pack.revision()>revision && pack.saveRevision()==saved && pack.resourceRevision()==resource && pack.undoCount()==undo && drafts.any() && Arrays.equals(pack.read("future/item0624.bin"),files.get("future/item0624.bin")),"paging leaves drafts, save baselines, structural identity and opaque bytes intact");
        // Saved-baseline and partial-save changes refresh inventory pages even without an applied revision change.
        var small=PackWorkspace.newPack(Map.of("blocks/base.json",new byte[]{1}));Path root=temp.resolve("paged-review-save");small.save(root);small.duplicate("blocks/base.json","a");small.duplicate("blocks/base.json","z");
        var before=cache.page(small,ALL,0);revision=small.revision();saved=small.saveRevision();
        try { small.save(root,(base,path,data,baseline)->{if(path.endsWith("/z.json"))throw new java.io.IOException("later file failure");return FileSaves.write(base,path,data,baseline);});throw new AssertionError(); }catch(java.io.IOException expected) {}
        var after=cache.page(small,ALL,3);check(small.revision()==revision && small.saveRevision()>saved && after!=before && after.result().newFiles()==1 && after.result().modifiedFiles()==1,"partial Save invalidates paged metadata using save revision");
        small.saveCopy(temp.resolve("paged-review-copy"));check(cache.page(small,ALL,3)==after,"Save Copy retains current inventory page and baseline identity");
        small.save(root);check(cache.page(small,ALL,Integer.MAX_VALUE).window().matches()==0,"successful Save clamps empty inventory to page zero");cache.clear();check(cache.page(PackWorkspace.empty(),ALL,4).result().total()==0,"clear removes old page and totals");
        System.out.println("PASS: complete pending-change and captured disk-result paging with bounded immutable 200-row pages, ordered exact coverage and global counts, empty/exact/overflow/one-based jump boundaries, literal path/kind/attention filtering before paging, clamped cache identity and first-page API compatibility, single-scan receipt reuse, edit/Undo/equal-owner/Save/partial-save invalidation, retained drafts/history/opaque bytes and no disk rescan, writes or native operations while paging");
    }
}
