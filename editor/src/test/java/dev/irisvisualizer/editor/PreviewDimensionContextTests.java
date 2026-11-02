package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Deferred UI-stage metadata/lifecycle checks; no native generation or rendering. */
final class PreviewDimensionContextTests {
    private static final String DIM="dimensions/a.json";
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static DimensionHeights.PreviewRange range(String json){return DimensionHeights.previewRange(new LosslessJson(bytes(json)));}
    private static void rejects(Runnable action){try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected unresolved height metadata");}
    private static void await(CountDownLatch gate)throws Exception{check(gate.await(10,TimeUnit.SECONDS),"preview context worker reached its gate");}
    private static void uninterrupted(CountDownLatch gate)throws Exception{boolean released=false;while(!released)try{released=gate.await(10,TimeUnit.SECONDS);check(released,"worker gate released");}catch(InterruptedException ignored){}}
    private static PreviewDimensionContext.Snapshot terminal(PreviewDimensionContext reader,PackWorkspace pack,String path)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);var state=reader.select(pack,path);
        while(state.phase()==PreviewDimensionContext.Phase.READING && System.nanoTime()<end){Thread.sleep(1);state=reader.select(pack,path);}check(state.phase()!=PreviewDimensionContext.Phase.READING,"preview context reaches a terminal metadata state");return state;
    }
    static void run(Path temp)throws Exception{
        var defaults=range("{}");check(defaults.minimum()==-64 && defaults.maximum()==320 && defaults.span()==384 && defaults.contextDefault() && defaults.minimumDefault() && defaults.maximumDefault() && defaults.problem()==null,"absent dimensionHeight retains the pinned complete contextual range");
        check(defaults.label().contains("through 319") && defaults.provenance().contains("no fields inserted"),"exclusive maximum and absent provenance are explicit");
        var partial=range("{\"dimensionHeight\":{},\"logicalHeight\":0}");check(partial.minimum()==16 && partial.maximum()==32 && !partial.contextDefault() && partial.minimumDefault() && partial.maximumDefault() && partial.problem()==null,"present empty range uses range-class defaults, not contextual defaults");
        var oneBound=range("{\"dimensionHeight\":{\"min\":-64},\"logicalHeight\":0}");check(oneBound.maximum()==32 && !oneBound.minimumDefault() && oneBound.maximumDefault(),"partial range member defaults remain distinct from stored values");
        var explicit=range("{\"dimensionHeight\":{\"min\":-64.00,\"max\":3.2e2,\"future\":9007199254740993}}");check(explicit.minimum()==-64 && explicit.maximum()==320 && !explicit.minimumDefault() && !explicit.maximumDefault() && explicit.problem()==null,"exact equivalent integer spellings and unknown range data stay untouched");
        check(range("{\"dimensionHeight\":{\"min\":-2032,\"max\":2032}}").span()==4064,"full supported configured span is retained without trimming to the default height");
        for(String json:List.of("{\"dimensionHeight\":null}","{\"dimensionHeight\":[]}","{\"dimensionHeight\":\"oops\"}","{\"dimensionHeight\":{\"min\":null}}","{\"dimensionHeight\":{\"max\":320.5}}","{\"dimensionHeight\":{\"max\":2147483648}}","{\"dimensionHeight\":{\"max\":\"320\"}}","{\"dimensionHeight\":{\"min\":-64,\"min\":0}}","{\"dimensionHeight\":{}","{} trailing"))rejects(()->range(json));
        for(String json:List.of("{\"dimensionHeight\":{\"min\":-63,\"max\":321}}","{\"dimensionHeight\":{\"min\":-2048,\"max\":320}}","{\"dimensionHeight\":{\"min\":0,\"max\":8192}}","{\"dimensionHeight\":{\"min\":32,\"max\":16}}","{\"dimensionHeight\":{}}","{\"logicalHeight\":-1}","{\"logicalHeight\":385}","{\"logicalHeight\":\"bad\"}"))check(range(json).problem()!=null,"invalid native bounds/alignment/logical height is visible: "+json);
        check(range("{\"dimensionHeight\":{\"min\":32,\"max\":16}}").label().contains("invalid span"),"reversed bounds never masquerade as a rendered interval");
        var oversize=new PreviewDimensionContext.Source(DIM,0,new byte[PreviewDimensionContext.MAX_BYTES+1]);check(oversize.retainedBytes()==0,"oversize source retains only byte metadata");rejects(()->PreviewDimensionContext.read(oversize,()->false));
        rejects(()->new PreviewDimensionContext.Source("dimensions/nested/a.json",0,bytes("{}")));
        var malformed=new PreviewDimensionContext.Source(DIM,0,new byte[]{'{','"','x','"',':','"',(byte)0xff,'"','}'});rejects(()->PreviewDimensionContext.read(malformed,()->false));
        try{PreviewDimensionContext.read(new PreviewDimensionContext.Source(DIM,0,bytes("{}")),()->true);throw new AssertionError("Cancellation ignored");}catch(CancellationException expected){}
        var files=new LinkedHashMap<String,byte[]>();files.put(DIM,bytes("{ \"dimensionHeight\":{\"min\":-64,\"max\":512}, \"future\":9007199254740993 }"));files.put("biomes/a.json",bytes("{\"name\":\"a\"}"));files.put("images/a.png",new byte[]{0,-1});
        var pack=PackWorkspace.newPack(files);long revision=pack.revision(),saved=pack.saveRevision(),resources=pack.resourceRevision();int history=pack.undoCount();var drafts=new InspectorDrafts();drafts.update("height","512","unfinished");
        try(var reader=new PreviewDimensionContext()){
            var state=terminal(reader,pack,DIM);check(state.phase()==PreviewDimensionContext.Phase.READY && state.report().height().maximum()==512 && state.report().height().span()==576,"selected applied dimension height is read in full");
            check(pack.revision()==revision && pack.resourceRevision()==resources && pack.saveRevision()==saved && pack.undoCount()==history && drafts.any(),"height inspection changes no pack revision, Save state, history or drafts");
            long request=state.request();check(reader.select(pack,DIM).request()==request,"unchanged frames do not repeat reads");
            pack.replace("biomes/a.json",bytes("{\"name\":\"changed\"}"));check(reader.select(pack,DIM).request()==request,"unrelated content edits do not repeat dimension reads");pack.undo();
            pack.replace(DIM,bytes("{\"dimensionHeight\":{\"min\":-64,\"max\":768}}"));check(reader.poll(pack,DIM).report()==null,"applied dimension edits immediately hide stale height metadata");
            check(terminal(reader,pack,DIM).report().height().maximum()==768,"new applied dimension height replaces the previous range");pack.undo();check(terminal(reader,pack,DIM).report().height().maximum()==512,"Undo restores the applied range without taking pending drafts");
            var other=PackWorkspace.newPack(Map.of(DIM,bytes("{}")));check(reader.poll(other,DIM).report()==null && terminal(reader,other,DIM).report().height().maximum()==320,"same path in another workspace cannot reuse height metadata");
            check(reader.select(other,null).phase()==PreviewDimensionContext.Phase.IDLE && reader.select(other,"images/a.png").report()==null,"noncontextual and missing paths release metadata");
            terminal(reader,pack,DIM);reader.clear();check(reader.poll(pack,DIM).report()==null,"clear releases metadata");
            pack.save(temp.resolve("preview-height-save"));pack.saveCopy(temp.resolve("preview-height-copy"));pack.saveAs(temp.resolve("preview-height-as"));pack.exportZip(temp.resolve("preview-height.zip"));
            for(var reopened:List.of(PackWorkspace.open(temp.resolve("preview-height-save")),PackWorkspace.open(temp.resolve("preview-height-copy")),PackWorkspace.open(temp.resolve("preview-height-as")),PackWorkspace.importZip(temp.resolve("preview-height.zip")))){
                check(reopened.paths().equals(pack.paths()),"height metadata creates no pack files");for(String path:files.keySet())check(Arrays.equals(reopened.read(path),files.get(path)),"read-only metadata preserves exact persistence: "+path);
            }
            check(drafts.any(),"metadata and Save modes retain unrelated drafts");
        }
        var bad=PackWorkspace.newPack(Map.of(DIM,bytes("{\"dimensionHeight\":null}")));var reads=new AtomicInteger();
        try(var reader=new PreviewDimensionContext((source,cancelled)->{reads.incrementAndGet();return PreviewDimensionContext.read(source,cancelled);},r->new Thread(r,"height-failure-contract"))){
            var failed=terminal(reader,bad,DIM);check(failed.phase()==PreviewDimensionContext.Phase.FAILED && failed.error().contains("null") && failed.report()==null,"unsupported height has a stable failure, never invented defaults");
            for(int i=0;i<20;i++)check(reader.select(bad,DIM).request()==failed.request(),"failure does not retry itself each frame");check(reads.get()==1,"only one failed read");
            bad.save(temp.resolve("preview-height-invalid-save"));bad.exportZip(temp.resolve("preview-height-invalid.zip"));check(Arrays.equals(PackWorkspace.open(temp.resolve("preview-height-invalid-save")).read(DIM),bytes("{\"dimensionHeight\":null}")) && Arrays.equals(PackWorkspace.importZip(temp.resolve("preview-height-invalid.zip")).read(DIM),bytes("{\"dimensionHeight\":null}")),"unresolved preview height cannot block Save/Export or normalize null");check(reader.select(bad,DIM).request()==failed.request(),"Save alone cannot restart unchanged metadata");
            reader.clear();check(terminal(reader,bad,DIM).request()!=failed.request() && reads.get()==2,"explicit retry is available");bad.replace(DIM,bytes("{}"));check(terminal(reader,bad,DIM).phase()==PreviewDimensionContext.Phase.READY,"correcting the applied source retries metadata");
        }
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();var active=new AtomicInteger();var maximum=new AtomicInteger();
        try(var reader=new PreviewDimensionContext((source,cancelled)->{int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);try{if(calls.incrementAndGet()==1){entered.countDown();uninterrupted(release);}return PreviewDimensionContext.read(source,()->false);}finally{active.decrementAndGet();}},r->new Thread(r,"height-latest-contract"))){
            reader.select(bad,DIM);await(entered);bad.replace(DIM,bytes("{\"dimensionHeight\":{\"min\":-64,\"max\":512}}"));reader.select(bad,DIM);bad.replace(DIM,bytes("{\"dimensionHeight\":{\"min\":-64,\"max\":768}}"));var pending=reader.select(bad,DIM);check(pending.report()==null,"rapid edits hide old output while the latest source waits");release.countDown();
            var latest=terminal(reader,bad,DIM);check(latest.report().height().maximum()==768 && calls.get()==2 && maximum.get()==1,"one active read plus latest pending, no stale publication or overlapping reads");
        }finally{release.countDown();}
        for(int failure=0;failure<3;failure++){final int kind=failure;try(var reader=new PreviewDimensionContext((source,cancelled)->{if(kind==0)throw new OutOfMemoryError();if(kind==1)return null;var valid=PreviewDimensionContext.read(source,cancelled);return new PreviewDimensionContext.Report("dimensions/foreign.json",source.stamp,source.bytes,valid.height());},r->new Thread(r,"height-result-contract"))){check(terminal(reader,bad,DIM).phase()==PreviewDimensionContext.Phase.FAILED,"OOM/null/foreign result cannot publish a range");}}
        var closed=new PreviewDimensionContext();closed.close();check(closed.select(bad,DIM).phase()==PreviewDimensionContext.Phase.CLOSED,"closed worker cannot restart");
        System.out.println("PASS: bounded applied dimension-height metadata, contextual versus member defaults, exact full ranges/provenance and native constraint errors, latest-only background parsing, owner/revision/Undo invalidation, stable failures/retry and byte-exact persistence without native generation");
    }
}
