package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deferred UI-stage contracts: request metadata only, with no native generation or image resampling. */
final class PreviewFramingTests {
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean value,String detail){if(!value)throw new AssertionError(detail);}
    private static void rejects(Runnable action){try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected invalid preview framing input");}
    static void run(Path temp)throws Exception{
        check(PreviewFraming.MAP_SAMPLES==512 && PreviewFraming.DEFAULT.width()==128 && PreviewFraming.DEFAULT.depth()==128 && PreviewFraming.DEFAULT.blocksPerSample()==1,"original preview defaults are retained");
        for(String scale:new String[]{null,"","0","-1","NaN","Infinity","0x1p0","1e309","1e-99999","1.2.3","9".repeat(129)})rejects(()->PreviewFraming.DEFAULT.mapScale(scale));
        var fractional=PreviewFraming.DEFAULT.mapScale(" 2.5 ");check(fractional.mapCoordinate(-5,2)==0,"world-space sample spacing supports fractional coordinates and negative origins");
        rejects(()->fractional.mapCoordinate(0,-1));rejects(()->fractional.mapCoordinate(0,512));
        check(PreviewFraming.DEFAULT.mapProblem(Integer.MAX_VALUE-511,0)==null && PreviewFraming.DEFAULT.mapProblem(Integer.MAX_VALUE-510,0)!=null,"map endpoint boundary is checked without omitting samples");
        check(PreviewFraming.DEFAULT.terrainProblem(Integer.MAX_VALUE-127,0)==null && PreviewFraming.DEFAULT.terrainProblem(Integer.MAX_VALUE-126,0)!=null,"terrain endpoint boundary uses the entire horizontal extent");
        check(PreviewFraming.DEFAULT.mapProblem(Integer.MIN_VALUE,Integer.MIN_VALUE)==null,"negative signed world origins are retained");
        for(String area:new String[]{null,"","0","-1","1.5","1e2","2147483648","9".repeat(32)})rejects(()->PreviewFraming.DEFAULT.terrainArea("64",area));
        var files=new LinkedHashMap<String,byte[]>();files.put("dimensions/one.json",bytes("{\"name\":\"One\"}"));files.put("dimensions/two.json",bytes("{}"));files.put("biomes/a.json",bytes("{\"name\":\"a\"}"));files.put("generators/nested/noise.json",bytes("{}"));files.put("structures/assembly.json",bytes("{}"));files.put("jigsaw-pieces/nested/door.json",bytes("{}"));files.put("images/ref.png",new byte[]{0,-1});files.put("images/ref.jpg",new byte[]{1,-1});files.put("objects/shape.iob",new byte[]{2,-1});files.put("blocks/opaque.json",bytes("{\"block\":\"minecraft:stone\",\"data\":{\"large\":9007199254740993}}"));
        var pack=PackWorkspace.newPack(files);long revision=pack.revision(),saved=pack.saveRevision();int history=pack.undoCount();var drafts=new InspectorDrafts();drafts.update("name","a","typing");var session=new PreviewSession();
        var unresolved=session.select(pack,"biomes/a.json");check(unresolved.dimension()==null,"multiple dimensions require an explicit context");rejects(()->session.mapScale("2"));
        session.dimension("dimensions/one.json");session.configure(Long.toString(Long.MAX_VALUE),"-100","-200");var scaled=session.mapScale("2");
        check(scaled.framing().blocksPerSample()==2 && scaled.coordinates().seed()==Long.MAX_VALUE,"framing edits preserve the exact signed64 seed");
        session.mode(PreviewSession.Mode.THREE_D);var area=session.terrainArea("64","96");check(area.framing().width()==64 && area.framing().depth()==96 && area.framing().blocksPerSample()==2,"terrain and map settings are independent");
        rejects(()->session.mapScale("3"));var applied=area.framing();rejects(()->session.terrainArea("256","bad"));check(session.select(pack,"biomes/a.json").framing().equals(applied),"invalid paired area input cannot partially apply");
        session.mode(PreviewSession.Mode.TWO_D);session.mapScale("4");session.dimension("dimensions/two.json");check(session.select(pack,"biomes/a.json").framing().equals(PreviewFraming.DEFAULT),"new dimension context receives independent defaults");
        session.mapScale("3");session.mode(PreviewSession.Mode.THREE_D);session.terrainArea("256","192");session.dimension("dimensions/one.json");
        var returned=session.select(pack,"biomes/a.json");check(returned.framing().width()==64 && returned.framing().blocksPerSample()==4 && returned.coordinates().seed()==Long.MAX_VALUE,"returning context retains coordinates and both framing modes");
        session.resetFraming();check(session.select(pack,"biomes/a.json").framing().blocksPerSample()==4,"terrain reset does not change map scale");
        session.mode(PreviewSession.Mode.TWO_D);session.resetFraming();check(session.select(pack,"biomes/a.json").framing().equals(PreviewFraming.DEFAULT),"map reset restores its default independently");
        session.configure("5",Integer.toString(Integer.MAX_VALUE),"0");var huge=session.mapScale("1e308");
        check(huge.framing().blocksPerSample()==1e308 && huge.coordinates().x()==Integer.MAX_VALUE && huge.framingProblem()!=null && huge.unavailable().contains("not omitted or clamped"),"oversized requests report the geometry limit without changing settings");
        session.mode(PreviewSession.Mode.THREE_D);session.terrainArea("32","48");session.configure("5","0","0");check(session.select(pack,"biomes/a.json").framingProblem()==null,"invalid map geometry does not invalidate an independent terrain footprint");
        session.mode(PreviewSession.Mode.TWO_D);check(session.select(pack,"biomes/a.json").framingProblem()!=null,"the map problem remains until the user explicitly corrects scale");session.mapScale("0.25");
        var expected=session.select(pack,"biomes/a.json");check(expected.framingProblem()==null,"corrected scale retains the complete 512-sample grid");
        var generator=session.select(pack,"generators/nested/noise.json");check(generator.identity().resourceType().equals("generators") && generator.identity().resourceKey().equals("nested/noise"),"preview identity uses the exact base family and complete resource key");
        var piece=session.select(pack,"jigsaw-pieces/nested/door.json");check(piece.identity().resourceType().equals("jigsaw-pieces") && piece.identity().resourceKey().equals("nested/door") && !piece.framingAvailable(),"grouped Structure UI labels do not erase the actual resource type or invent terrain controls");rejects(()->session.terrainArea("64","64"));
        var image=session.select(pack,"images/ref.png");var jpeg=session.select(pack,"images/ref.jpg");check(image.identity().resourceKey().equals(jpeg.identity().resourceKey()) && !image.identity().sourcePath().equals(jpeg.identity().sourcePath()) && !jpeg.framingAvailable(),"original-image paths distinguish equal keys with different extensions and keep source dimensions");rejects(()->session.mapScale("4"));
        check(!session.select(pack,"objects/shape.iob").framingAvailable(),"individual objects do not receive terrain footprint controls");
        check(session.select(pack,"blocks/opaque.json").identity()==null && !session.select(pack,"images/missing.png").framingAvailable(),"unsupported or missing resources cannot borrow stale preview metadata");
        check(pack.revision()==revision && pack.saveRevision()==saved && pack.undoCount()==history && drafts.any(),"UI framing never changes pack revisions, Save/history or resource drafts");for(String path:files.keySet())check(Arrays.equals(pack.read(path),files.get(path)),"unchanged source bytes: "+path);
        session.select(pack,"biomes/a.json");var beforeRename=session.select(pack,"biomes/a.json");
        String renamed=ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,"dimensions/one.json","renamed");session.resourceApplied(pack,"dimensions/one.json",renamed);
        var afterRename=session.select(pack,"biomes/a.json");check(afterRename.dimension().equals(renamed) && afterRename.framing().equals(beforeRename.framing()) && afterRename.coordinates().equals(beforeRename.coordinates()),"successful dimension renames transfer context settings without editing pack overrides");
        session.resourceApplied(pack,renamed,"dimensions/missing.json");check(session.select(pack,"biomes/a.json").framing().equals(afterRename.framing()),"unapplied path changes cannot discard preview state");
        pack.replace("biomes/a.json",bytes("{\"name\":\"changed\"}"));pack.undo();check(session.select(pack,"biomes/a.json").framing().equals(afterRename.framing()),"ordinary edits/Undo do not reset preview framing");
        pack.save(temp.resolve("preview-framing-save"));pack.saveCopy(temp.resolve("preview-framing-copy"));pack.saveAs(temp.resolve("preview-framing-as"));pack.exportZip(temp.resolve("preview-framing.zip"));
        for(var reopened:List.of(PackWorkspace.open(temp.resolve("preview-framing-save")),PackWorkspace.open(temp.resolve("preview-framing-copy")),PackWorkspace.open(temp.resolve("preview-framing-as")),PackWorkspace.importZip(temp.resolve("preview-framing.zip")))){
            check(reopened.paths().equals(pack.paths()),"framing creates no pack files");for(String path:pack.paths())check(Arrays.equals(pack.read(path),reopened.read(path)),"framing cannot enter saved/exported JSON: "+path);
        }
        check(session.select(pack,"biomes/a.json").framing().equals(afterRename.framing()) && drafts.any(),"Save modes retain UI framing and unrelated resource drafts");
        var replacement=PackWorkspace.newPack(Map.of("dimensions/renamed.json",bytes("{}"),"biomes/a.json",bytes("{}")));var fresh=session.select(replacement,"biomes/a.json");check(fresh.framing().equals(PreviewFraming.DEFAULT) && fresh.coordinates().seed()==0,"another workspace with identical paths cannot inherit framing");
        session.resourceApplied(pack,renamed,null);check(session.select(replacement,"biomes/a.json").dimension().equals("dimensions/renamed.json"),"foreign operation receipts cannot clear another workspace's context");
        session.mapScale("8");session.clear();check(session.select(replacement,"biomes/a.json").framing().equals(PreviewFraming.DEFAULT),"explicit clear releases framing settings");
        ResourceOperationTests.apply(replacement,ResourceOperation.Mode.DELETE,"dimensions/renamed.json",null);session.resourceApplied(replacement,"dimensions/renamed.json",null);
        var removed=session.select(replacement,"biomes/a.json");check(removed.dimension()==null && removed.framing().equals(PreviewFraming.DEFAULT),"deleting the context releases its framing instead of retaining a missing dimension");rejects(()->session.mapScale("2"));
        System.out.println("PASS: UI-only 512-sample map scale and 128-block terrain defaults, exact resource identities, atomic per-mode/per-dimension settings, coordinate overflow without clamping, applicability and owner isolation, dimension rename retention and exact Save/Copy/As/ZIP without preview overrides");
    }
}
