package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class LinkReviewPaginationTests {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean condition,String reason) { if(!condition)throw new AssertionError("Link review pages: "+reason); }
    private static void rejected(Runnable action) { try { action.run(); }catch(IllegalArgumentException|NullPointerException expected) { return; }throw new AssertionError("Expected invalid link review input"); }
    private static final PackReferenceAudit.Query ALL_LINKS=new PackReferenceAudit.Query("",PackReferenceAudit.View.ALL,null);
    private static final JigsawDependencyReview.Query ALL_EDGES=new JigsawDependencyReview.Query("",JigsawDependencyReview.Scope.ALL,null,false);
    private static AsyncPackReferenceReview.Snapshot ready(AsyncPackReferenceReview worker,PackWorkspace pack)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);AsyncPackReferenceReview.Snapshot state;
        while((state=worker.poll(pack)).phase()==AsyncPackReferenceReview.Phase.SCANNING && System.nanoTime()<until)Thread.sleep(1);
        check(state.phase()==AsyncPackReferenceReview.Phase.READY,"reference scan completes");return state;
    }
    private static AsyncJigsawDependencyReview.Snapshot ready(AsyncJigsawDependencyReview worker,PackWorkspace pack,String root)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);AsyncJigsawDependencyReview.Snapshot state;
        while((state=worker.poll(pack,root)).phase()==AsyncJigsawDependencyReview.Phase.SCANNING && System.nanoTime()<until)Thread.sleep(1);
        check(state.phase()==AsyncJigsawDependencyReview.Phase.READY,"dependency scan completes");return state;
    }
    static void run(Path temp)throws Exception {
        // A real 230-pool ring stays within existing scan budgets, with a route and component beyond one page.
        var files=new LinkedHashMap<String,byte[]>();for(int i=0;i<230;i++)files.put(String.format(Locale.ROOT,"jigsaw-pools/r%03d.json",i),bytes(String.format(Locale.ROOT,"{\"pieces\":[{\"empty\":true}],\"fallback\":\"r%03d\"}",(i+1)%230)));
        files.put("future/unknown.json",bytes("malformed 雪"));files.put("objects/exact.iob",new byte[]{0,-1,5});
        var pack=PackWorkspace.newPack(files);String root="jigsaw-pools/r000.json",last="jigsaw-pools/r229.json";
        var review=JigsawDependencyReview.inspect(pack,root);var audit=PackReferenceAudit.inspect(pack);
        check(!review.incomplete() && review.nodes().size()==230 && review.cycles().size()==1 && review.cycles().getFirst().size()==230 && review.fallbackCycles().getFirst().size()==230,"real long ring remains complete within bounded review scope");
        check(review.edges().size()>200 && audit.findings().size()>200,"fixture spans diagnostic pages");
        var edges=new ArrayList<JigsawDependencyReview.Edge>();for(int page=0;page<ReviewPagination.Window.at(review.edges().size(),0).pages();page++)edges.addAll(JigsawDependencyReview.page(review,ALL_EDGES,page).result().edges());
        var findings=new ArrayList<PackReferenceAudit.Finding>();for(int page=0;page<ReviewPagination.Window.at(audit.findings().size(),0).pages();page++)findings.addAll(PackReferenceAudit.page(audit,ALL_LINKS,page).result().findings());
        check(edges.equals(review.edges()) && findings.equals(audit.findings()),"every recorded diagnostic retains original traversal order and duplicates");
        check(JigsawDependencyReview.filter(review,ALL_EDGES).equals(JigsawDependencyReview.page(review,ALL_EDGES,0).result()) && PackReferenceAudit.filter(audit,ALL_LINKS).equals(PackReferenceAudit.page(audit,ALL_LINKS,0).result()),"legacy filters return the same first bounded page");
        var route0=JigsawDependencyReview.routePage(review,last,0);var route1=JigsawDependencyReview.routePage(review,last,Integer.MAX_VALUE);var route=new ArrayList<>(route0.entries());route.addAll(route1.entries());
        check(route.equals(review.route(last)) && route.size()==230 && route1.entries().size()==30 && route1.entries().getLast().distance()==229,"entire shortest recorded route beyond former 64-row limit");
        var nodes=new ArrayList<>(JigsawDependencyReview.nodes(review,"",0).entries());nodes.addAll(JigsawDependencyReview.nodes(review,"",1).entries());check(nodes.equals(review.nodes()),"every visited node beyond first 200 remains reachable");
        check(JigsawDependencyReview.nodes(review,"R22",Integer.MAX_VALUE).entries().size()==10 && JigsawDependencyReview.nodes(review,"absent",3).entries().isEmpty(),"literal node path filter applies before clamping");
        for(boolean fallback:List.of(false,true)) {
            var component=JigsawDependencyReview.components(review,fallback,"R229",Integer.MAX_VALUE);check(component.window().matches()==1 && component.entries().getFirst().index()==0,"component search finds any member and retains original identity");
            var members=new ArrayList<>(JigsawDependencyReview.members(review,fallback,0,0).entries());members.addAll(JigsawDependencyReview.members(review,fallback,0,1).entries());
            check(members.equals(component.entries().getFirst().members()) && members.size()==230 && members.getFirst().endsWith("r000.json"),"matching component retains every member, including nonmatching paths and last-page paths");
            rejected(()->JigsawDependencyReview.members(review,fallback,-1,0));rejected(()->JigsawDependencyReview.members(review,fallback,1,0));
        }
        check(JigsawDependencyReview.routePage(review,null,5).entries().isEmpty() && JigsawDependencyReview.components(review,false,"missing",1).entries().isEmpty(),"empty routes/components cannot expose stale results");
        try { route1.entries().clear();throw new AssertionError(); }catch(UnsupportedOperationException expected) {}
        try { JigsawDependencyReview.components(review,false,"",0).entries().getFirst().members().clear();throw new AssertionError(); }catch(UnsupportedOperationException expected) {}
        // Real 201 self-components exceed both the former twenty-group display and one new page.
        var many=new LinkedHashMap<String,byte[]>();var connectors=new ArrayList<String>();
        for(int i=0;i<201;i++) { String key=String.format(Locale.ROOT,"g%03d",i);many.put("jigsaw-pools/"+key+".json",bytes("{\"pieces\":[{\"empty\":true}],\"fallback\":\""+key+"\"}"));connectors.add("{\"pool\":\""+key+"\"}"); }
        String piece="jigsaw-pieces/root.json";many.put(piece,bytes("{\"object\":\"model\",\"connectors\":["+String.join(",",connectors)+"]}"));many.put("objects/model.iob",new byte[]{0});
        var manyPack=PackWorkspace.newPack(many);var manyReview=JigsawDependencyReview.inspect(manyPack,piece);
        check(!manyReview.incomplete() && manyReview.cycles().size()==201 && manyReview.fallbackCycles().size()==201,"real bounded scan records all 201 self-components");
        for(boolean fallback:List.of(false,true)) { var later=JigsawDependencyReview.components(manyReview,fallback,"",1);check(later.entries().size()==1 && later.entries().getFirst().index()==200 && later.entries().getFirst().members().getFirst().endsWith("g200.json"),"last component is reachable with stable global index in both independent views"); }
        // Page filters retain existing null-target, exact incoming, state, source and problem semantics.
        var synthetic=new ArrayList<PackReferenceAudit.Finding>();var syntheticEdges=new ArrayList<JigsawDependencyReview.Edge>();
        for(int i=0;i<4096;i++) { var state=i%2==0 ? ReferenceDiagnostics.State.EXACT_FILE : ReferenceDiagnostics.State.NO_EXACT_FILE;String target=i%2==0 ? "target.json" : null;synthetic.add(new PackReferenceAudit.Finding("source.json","field","key",target,state));syntheticEdges.add(new JigsawDependencyReview.Edge("source.json","field","key",target,state)); }
        var partialAudit=new PackReferenceAudit.Audit(512,512,8_000_000,4,synthetic,List.of(new PackReferenceAudit.Notice("source.json","recorded limit")),9,true);
        var partialReview=new JigsawDependencyReview.Review(root,review.nodes(),230,review.bytes(),syntheticEdges,review.cycles(),review.fallbackCycles(),List.of(new JigsawDependencyReview.Notice(root,"recorded limit")),3,true);
        for(var view:PackReferenceAudit.View.values()) {
            var query=new PackReferenceAudit.Query("KEY",view,null);int expected=(int)synthetic.stream().filter(f->view.matches(f.state())).count();var page=PackReferenceAudit.page(partialAudit,query,Integer.MAX_VALUE);
            check(page.result().matches()==expected && page.result().findings().size()<=200 && !page.window().next(),"state/text predicates apply before last-page selection");
        }
        check(PackReferenceAudit.page(partialAudit,new PackReferenceAudit.Query("",PackReferenceAudit.View.ALL,"target.json"),10).result().matches()==2048,"incoming exact target excludes null targets");
        check(JigsawDependencyReview.page(partialReview,new JigsawDependencyReview.Query("",JigsawDependencyReview.Scope.INCOMING,null,false),2).result().matches()==0,"incoming scope without selection stays empty");
        check(JigsawDependencyReview.page(partialReview,new JigsawDependencyReview.Query("",JigsawDependencyReview.Scope.OUTGOING,"source.json",true),10).result().matches()==2048,"outgoing scope and problems filter before paging");
        check(partialAudit.incomplete() && partialAudit.omittedNotices()==9 && partialReview.incomplete() && partialReview.omittedNotices()==3,"paging does not reinterpret partial coverage or omitted findings");
        rejected(()->JigsawDependencyReview.nodes(review,"x".repeat(257),0));rejected(()->JigsawDependencyReview.components(review,false,"a\u0000",0));rejected(()->PackReferenceAudit.page(null,ALL_LINKS,0));
        // Explicit synchronous caches keep one page, retain on Save Copy, invalidate on edits/Undo/owners/root switches.
        var links=new PackReferenceAudit.Cache();links.refresh(pack);var linkPage=links.page(pack,ALL_LINKS,Integer.MAX_VALUE);check(links.page(pack,ALL_LINKS,1)==linkPage,"clamped reference page cache");
        var graph=new JigsawDependencyReview.Cache();graph.refresh(pack,root);var graphPage=graph.page(pack,root,ALL_EDGES,Integer.MAX_VALUE);check(graph.page(pack,root,ALL_EDGES,1)==graphPage,"clamped dependency page cache");
        long revision=pack.revision(),resource=pack.resourceRevision(),saved=pack.saveRevision();var drafts=new InspectorDrafts();drafts.update("late","stored","typing");var nav=new ResourceNavigation();nav.request(pack,last);var request=nav.takeRequest(pack);var selected=new java.util.concurrent.atomic.AtomicReference<>(root);
        check(!drafts.transition(()->false,()->selected.set(request.target())) && selected.get().equals(root) && drafts.any(),"later route/component navigation cannot discard late drafts without confirmation");
        pack.saveCopy(temp.resolve("link-pages-copy"));check(links.page(pack,ALL_LINKS,1)==linkPage && graph.page(pack,root,ALL_EDGES,1)==graphPage,"Save Copy retains captured review pages");
        check(pack.revision()==revision && pack.resourceRevision()==resource && pack.saveRevision()==saved && drafts.any() && Arrays.equals(pack.read("objects/exact.iob"),new byte[]{0,-1,5}) && Arrays.equals(pack.read("future/unknown.json"),files.get("future/unknown.json")),"review navigation/persistence retains drafts, revisions and opaque/unknown bytes");
        var refCalls=new AtomicInteger();var graphCalls=new AtomicInteger();
        try(var worker=new AsyncPackReferenceReview((source,cancelled)->{refCalls.incrementAndGet();return PackReferenceAudit.inspect(source,cancelled);},r->new Thread(r,"paged-reference-test"));var dependency=new AsyncJigsawDependencyReview((source,sourceRoot,cancelled)->{graphCalls.incrementAndGet();return JigsawDependencyReview.inspect(source,sourceRoot,cancelled);},r->new Thread(r,"paged-dependency-test"))) {
            worker.refresh(pack);dependency.refresh(pack,root);var refState=ready(worker,pack);var graphState=ready(dependency,pack,root);
            var refLast=worker.page(pack,ALL_LINKS,1);var graphLast=dependency.page(pack,root,ALL_EDGES,1);
            check(worker.page(pack,ALL_LINKS,Integer.MAX_VALUE)==refLast && dependency.page(pack,root,ALL_EDGES,Integer.MAX_VALUE)==graphLast,"async last-page cache identity");
            for(int i=0;i<4;i++) { worker.page(pack,ALL_LINKS,i%2);dependency.page(pack,root,ALL_EDGES,i%2); }
            check(refCalls.get()==1 && graphCalls.get()==1 && worker.poll(pack).audit()==refState.audit() && dependency.poll(pack,root).review()==graphState.review(),"page access never schedules scans or replaces captured results");
            pack.replace(root,bytes("{}"));check(worker.page(pack,ALL_LINKS,1)==null && dependency.page(pack,root,ALL_EDGES,1)==null && links.page(pack,ALL_LINKS,1)==null && graph.page(pack,root,ALL_EDGES,1)==null,"applied changes invalidate every page");pack.undo();check(worker.page(pack,ALL_LINKS,0)==null && dependency.page(pack,root,ALL_EDGES,0)==null,"Undo cannot resurrect old pages");
            worker.refresh(pack);dependency.refresh(pack,root);ready(worker,pack);ready(dependency,pack,root);var foreign=PackWorkspace.newPack(files);foreign.replace(root,bytes("{}"));foreign.undo();check(foreign.revision()==pack.revision() && worker.page(foreign,ALL_LINKS,1)==null && dependency.page(foreign,root,ALL_EDGES,1)==null,"equal-content/equal-revision owner isolation");
            dependency.refresh(pack,root);ready(dependency,pack,root);check(dependency.page(pack,last,ALL_EDGES,1)==null && graphCalls.get()==3,"root change invalidates without implicit rescan");
        }
        System.out.println("PASS: paged resource-link and jigsaw diagnostic, visited-node, route, component and member reviews with complete ordered recorded coverage and immutable 200-row bounds, real 230-node routes/components and 201 independent components, literal/state/incoming/outgoing/problem filters, stable component identities and full matching membership, unchanged incomplete/notice scopes, compatible first-page APIs and clamped sync/async cache identity, one-scan reuse and edit/Undo/equal-owner/root invalidation, guarded later-page navigation and retained drafts/unknown/opaque bytes; no new scan coverage, file rewrites or native operations");
    }
}
