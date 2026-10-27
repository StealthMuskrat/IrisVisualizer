package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class BiomeObjectPlacementTests {
    private static final String FLOATING="\"floatingChildBiomes\":[{\"floatingObjects\":[{\"chance\":1e-1,\"force\":false}],\"future\":9007199254740993}]";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Biome object placement contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected biome object operation"); }
    private static byte[] document(String placement) { return bytes("{\r\n \"objects\":["+placement+",{\"futureSibling\":1e2}],\r\n "+FLOATING+",\"future\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson placement(byte[] source) { return new LosslessJson(source).objectArray("objects").getFirst(); }
    private static LosslessJson object(byte[] source,String name) { return new LosslessJson(placement(source).token(name).getBytes(StandardCharsets.UTF_8)); }
    static void run(Path temp) throws Exception {
        runOwner(temp,"biomes",FloatingObjectPlacements.BIOME_ROOT);
        runOwner(temp,"regions",FloatingObjectPlacements.REGION_ROOT);
    }
    private static void runOwner(Path temp,String resourceBase,int owner) throws Exception {
        String PATH=resourceBase+"/nested/objects.json";
        String otherPath=(resourceBase.equals("biomes") ? "regions" : "biomes")+"/nested/objects.json";
        byte[] untouchedOther=document("{\"chance\":0.125,\"place\":[\"oak\"],\"futureOther\":9007199254740993}");
        var workspace=PackWorkspace.newPack(Map.of(PATH,document("{}"),otherPath,untouchedOther,"objects/oak.iob",new byte[]{0,127,-1,42},"objects/nested/pine.iob",new byte[]{1},"objects/UPPER.IOB",new byte[]{2}));
        check(FloatingObjectPlacements.objectChoices(workspace).equals(List.of("nested/pine","oak")));
        check(FloatingObjectPlacements.placements(new LosslessJson(bytes("{}")),"objects")==null);
        byte[] empty=FloatingObjectPlacements.enable(PATH,bytes("{}"),owner,"objects");check(new LosslessJson(empty).objectArray("objects").isEmpty());
        check(FloatingObjectPlacements.creationMode("objects").equals("CENTER_HEIGHT") && BiomeObjectPlacements.defaultAnchor().equals("PROFILE_DEFAULT"));
        check(!FloatingObjectPlacements.LIST_FIELDS.contains("objects") && FloatingObjectPlacements.listHelp(owner,"objects").contains("schematics"));
        byte[] added=FloatingObjectPlacements.change(PATH,empty,owner,"objects",RiverProfiles.Action.ADD,-1,"oak","CENTER_HEIGHT",workspace);
        check(placement(added).fields().size()==2 && placement(added).stringArrayPath("place").equals(List.of("oak")));
        byte[] duplicate=FloatingObjectPlacements.change(PATH,added,owner,"objects",RiverProfiles.Action.DUPLICATE,0,null,null,workspace);
        check(new LosslessJson(duplicate).objectArray("objects").size()==2);
        byte[] moved=FloatingObjectPlacements.change(PATH,document("{\"place\":[\"missing/key\"],\"future\":9007199254740993}"),owner,"objects",RiverProfiles.Action.DOWN,0,null,null,workspace);
        check(new LosslessJson(moved).objectArray("objects").getLast().stringArrayPath("place").equals(List.of("missing/key")));
        byte[] removed=FloatingObjectPlacements.change(PATH,added,owner,"objects",RiverProfiles.Action.REMOVE,0,null,null,workspace);check(new LosslessJson(removed).objectArray("objects").isEmpty());
        rejects(() -> FloatingObjectPlacements.change(PATH,empty,owner,"objects",RiverProfiles.Action.ADD,-1,"missing","CENTER_HEIGHT",workspace));
        for (String bad : List.of("null","true","1","{}","\"snippet/objects\"","[null]","[\"snippet/placement\"]","[{},1]")) {
            byte[] source=bytes("{\"objects\":"+bad+"}");
            rejects(() -> FloatingObjectPlacements.placements(new LosslessJson(source),"objects"));
            rejects(() -> FloatingObjectPlacements.enable(PATH,source,owner,"objects"));
            rejects(() -> FloatingObjectPlacements.change(PATH,source,owner,"objects",RiverProfiles.Action.ADD,-1,"oak","CENTER_HEIGHT",workspace));
            rejects(() -> BiomeObjectPlacements.setAnchor(PATH,source,owner,0,"FLOOR"));
        }
        rejects(() -> FloatingObjectPlacements.enable(PATH,empty,owner,"objects"));
        if (owner==FloatingObjectPlacements.REGION_ROOT) {
            rejects(() -> BiomeObjectPlacements.setAnchor(PATH,added,0,"FLOOR"));
            rejects(() -> BiomeObjectPlacements.enableBottom(PATH,added,0));
            rejects(() -> BiomeObjectPlacements.setBottom(PATH,document("{\"bottom\":false}"),0,true));
        }
        for (String path : List.of((resourceBase.equals("biomes") ? "regions" : "biomes")+"/a.json","dimensions/a.json","generators/a.json",resourceBase+"/a.txt","a.json")) {
            rejects(() -> FloatingObjectPlacements.enable(path,bytes("{}"),owner,"objects"));
            rejects(() -> BiomeObjectPlacements.setAnchor(path,added,owner,0,"FLOOR"));
            rejects(() -> BiomeObjectPlacements.enableBottom(path,added,owner,0));
            rejects(() -> NoiseStyleFields.enable(path,added,NoiseStyleFields.Target.placementWarp(owner,"objects",0),"style"));
            rejects(() -> FloatingPlacementSettings.enable(path,added,new FloatingPlacementSettings.Context(owner,"objects",0),"scale"));
            rejects(() -> FloatingObjectRotations.create(path,added,new FloatingObjectRotations.Context(owner,"objects",0)));
            rejects(() -> FloatingPlacementConstraints.setCarving(path,added,new FloatingPlacementConstraints.Context(owner,"objects",0),"CARVING_ONLY"));
            rejects(() -> FloatingPlacementEffects.enableFlag(path,added,new FloatingPlacementEffects.Context(owner,"objects",0),"bore"));
            rejects(() -> FloatingPlacementDeformation.enable(path,added,new FloatingPlacementDeformation.Context(owner,"objects",0),"stiltSettings"));
            rejects(() -> FloatingObjectReplacements.enable(path,added,new FloatingObjectReplacements.Context(owner,"objects",0)));
            rejects(() -> FloatingObjectPalettes.create(path,document("{\"stiltSettings\":{}}"),new FloatingObjectPalettes.Context(owner,"objects",0,-1)));
            rejects(() -> FloatingPlacementOverrides.enableHeightmap(path,added,new FloatingPlacementOverrides.Context(owner,"objects",0)));
            rejects(() -> FloatingPlacementOverrides.enableForce(path,added,new FloatingPlacementOverrides.Context(owner,"objects",0)));
            rejects(() -> NoiseFields.enable(path,document("{\"heightmap\":{}}"),new FloatingPlacementOverrides.Context(owner,"objects",0).noiseTarget(),"seed"));
            rejects(() -> NoiseStyleFields.enable(path,document("{\"heightmap\":{}}"),NoiseStyleFields.Target.noise(new FloatingPlacementOverrides.Context(owner,"objects",0).noiseTarget()),"style"));
            rejects(() -> new FloatingObjectPalettes.Context(owner,"objects",0,-1).target().setBlock(path,document("{\"stiltSettings\":{\"palette\":{\"palette\":[{\"block\":\"stone\"}]}}}"),0,"minecraft:dirt"));
        }
        for (int entry : List.of(-3,-2,-1,0,1)) for (String list : List.of("objects","floatingObjects","extraObjects","unknown")) {
            boolean valid=FloatingObjectPlacements.root(entry) && list.equals("objects") || entry>=0 && FloatingObjectPlacements.LIST_FIELDS.contains(list);
            check(FloatingObjectPlacements.validCollection(entry,list)==valid);
            if (!valid) {
                rejects(() -> new FloatingPlacementSettings.Context(entry,list,0));rejects(() -> new FloatingPlacementConstraints.Context(entry,list,0));
                rejects(() -> new FloatingPlacementEffects.Context(entry,list,0));rejects(() -> new FloatingPlacementDeformation.Context(entry,list,0));
                rejects(() -> new FloatingObjectReplacements.Context(entry,list,0));rejects(() -> new FloatingObjectPalettes.Context(entry,list,0,-1));
                rejects(() -> new FloatingPlacementOverrides.Context(entry,list,0));rejects(() -> new FloatingObjectRotations.Context(entry,list,0));
                rejects(() -> FloatingObjectPlacements.enable(PATH,bytes("{}"),entry,list));
            }
        }
        rejects(() -> new FloatingPlacementOverrides.Context(-1,null,0));rejects(() -> new FloatingPlacementSettings.Context(owner,"objects",-1));
        byte[] original=document("{\"chance\":1e-1,\"density\":2,\"snow\":0,\"force\":false,\"heightmap\":{\"seed\":9007199254740993,\"futureNoise\":true},\"translate\":{\"x\":0},\"densityStyle\":{\"futureRange\":1},\"warp\":{\"futureWarp\":true},\"stiltSettings\":{\"yMax\":0},\"vacuumSettings\":{\"radius\":0},\"futurePlacement\":\"雪\\u0061\",\"loot\":{\"future\":true}}");
        check(text(FloatingObjectPlacements.setScalar(PATH,original,owner,"objects",0,"chance","0.25")).equals(text(original).replaceFirst("\"chance\":1e-1","\"chance\":0.25")));
        check(text(FloatingObjectPlacements.setTranslation(PATH,original,owner,"objects",0,"x","12")).equals(text(original).replace("\"x\":0","\"x\":12")));
        for (String choice : BiomeObjectPlacements.anchors()) check(placement(BiomeObjectPlacements.setAnchor(PATH,original,owner,0,choice)).stringToken("caveAnchorMode").equals(choice));
        check(placement(BiomeObjectPlacements.enableBottom(PATH,original,owner,0)).token("bottom").equals("false"));
        byte[] bottom=BiomeObjectPlacements.enableBottom(PATH,original,owner,0);check(placement(BiomeObjectPlacements.setBottom(PATH,bottom,owner,0,true)).token("bottom").equals("true"));
        rejects(() -> BiomeObjectPlacements.setAnchor(PATH,original,owner,0,"UNKNOWN"));rejects(() -> BiomeObjectPlacements.setBottom(PATH,original,owner,0,true));
        for (String bad : List.of("null","[]","1","true")) rejects(() -> BiomeObjectPlacements.setAnchor(PATH,document("{\"caveAnchorMode\":"+bad+"}"),owner,0,"FLOOR"));
        for (String bad : List.of("null","[]","1","\"true\"")) { byte[] invalid=document("{\"bottom\":"+bad+"}");rejects(() -> BiomeObjectPlacements.setBottom(PATH,invalid,owner,0,true));rejects(() -> BiomeObjectPlacements.enableBottom(PATH,invalid,owner,0)); }
        var settings=new FloatingPlacementSettings.Context(owner,"objects",0);var constraints=new FloatingPlacementConstraints.Context(owner,"objects",0);
        var effects=new FloatingPlacementEffects.Context(owner,"objects",0);var deformation=new FloatingPlacementDeformation.Context(owner,"objects",0);
        var rotation=new FloatingObjectRotations.Context(owner,"objects",0);var replacements=new FloatingObjectReplacements.Context(owner,"objects",0);
        var overrides=new FloatingPlacementOverrides.Context(owner,"objects",0);
        byte[] edited=FloatingPlacementSettings.setDensityBounds(PATH,original,settings,"0","32");
        edited=NoiseStyleFields.enable(PATH,edited,settings.styleTarget(),"style");check(object(edited,"densityStyle").stringPath("style.style").equals("STATIC"));
        edited=FloatingPlacementSettings.enable(PATH,edited,settings,"scale");edited=FloatingPlacementSettings.setScaleBounds(PATH,edited,settings,"0.5","2");
        edited=FloatingObjectRotations.create(PATH,edited,rotation);edited=FloatingObjectRotations.createAxis(PATH,edited,rotation,"yAxis");edited=FloatingObjectRotations.setAngles(PATH,edited,rotation,"yAxis","-90","90");
        edited=FloatingPlacementConstraints.setCarving(PATH,edited,constraints,"ANYWHERE");edited=FloatingPlacementConstraints.enableFlag(PATH,edited,constraints,"requireSurfaceSupport");edited=FloatingPlacementConstraints.setFlag(PATH,edited,constraints,"requireSurfaceSupport",false);
        edited=NoiseStyleFields.enable(PATH,edited,constraints.warpTarget(),"style");edited=FloatingPlacementConstraints.enableMultiplier(PATH,edited,constraints);edited=FloatingPlacementConstraints.setMultiplier(PATH,edited,constraints,"3");
        edited=FloatingPlacementEffects.enableFlag(PATH,edited,effects,"underwater");edited=FloatingPlacementEffects.setFlag(PATH,edited,effects,"underwater",true);
        edited=FloatingPlacementDeformation.setScalar(PATH,edited,deformation,"stiltSettings","yMax","16");edited=FloatingPlacementDeformation.setScalar(PATH,edited,deformation,"vacuumSettings","radius","24");
        var stilt=new FloatingObjectPalettes.Context(owner,"objects",0,-1);edited=FloatingObjectPalettes.create(PATH,edited,stilt);edited=FloatingObjectPalettes.enableEntries(PATH,edited,stilt);edited=NoiseStyleFields.enable(PATH,edited,NoiseStyleFields.Target.objectPalette(stilt),"style");
        edited=FloatingObjectReplacements.enable(PATH,edited,replacements);edited=FloatingObjectReplacements.change(PATH,edited,replacements,RiverProfiles.Action.ADD,-1,"minecraft:stone");
        var replacement=new FloatingObjectPalettes.Context(owner,"objects",0,0);edited=FloatingObjectPalettes.create(PATH,edited,replacement);edited=FloatingObjectPalettes.enableEntries(PATH,edited,replacement);edited=FloatingObjectPalettes.setBlock(PATH,edited,replacement,0,"minecraft:dirt");edited=NoiseStyleFields.enable(PATH,edited,NoiseStyleFields.Target.objectPalette(replacement),"style");
        edited=NoiseFields.editText(PATH,edited,overrides.noiseTarget(),"seed","-9223372036854775808");edited=NoiseStyleFields.enable(PATH,edited,NoiseStyleFields.Target.noise(overrides.noiseTarget()),"style");edited=FloatingPlacementOverrides.setForce(PATH,edited,overrides,true);
        edited=BiomeObjectPlacements.setAnchor(PATH,edited,owner,0,"CEILING");edited=BiomeObjectPlacements.enableBottom(PATH,edited,owner,0);
        check(object(edited,"heightmap").token("seed").equals("-9223372036854775808") && object(edited,"heightmap").stringPath("style.style").equals("IRIS"));
        check(object(edited,"stiltSettings").token("yMax").equals("16") && placement(edited).token("force").equals("true") && placement(edited).token("forcePlace")==null);
        for (String raw : List.of(FLOATING,"\"futureSibling\":1e2","\"future\":9007199254740993","\"escaped\":\"雪\\u0061\"","\"futurePlacement\":\"雪\\u0061\"","\"loot\":{\"future\":true}","\"futureRange\":1","\"futureWarp\":true","\"futureNoise\":true")) check(text(edited).contains(raw));
        check(java.util.Set.copyOf(FloatingObjectPlacements.readOnlyFields(placement(edited),"objects")).equals(java.util.Set.of("futurePlacement","loot")));
        check(FloatingObjectPlacements.readOnlyFields(placement(edited),"floatingObjects").containsAll(List.of("bottom","caveAnchorMode")));
        for (var target : List.of(settings.styleTarget(),constraints.warpTarget(),NoiseStyleFields.Target.objectPalette(stilt),NoiseStyleFields.Target.objectPalette(replacement),NoiseStyleFields.Target.noise(overrides.noiseTarget()))) {
            check(target.resourceBase().equals(resourceBase) && target.draftPrefix().startsWith(FloatingObjectPlacements.listPrefix(owner,"objects")));
            check(!target.draftPrefix().startsWith(FloatingObjectPlacements.listPrefix(0,"floatingObjects")));
        }
        for (String bad : List.of("objects.-1.warp","objects.01.warp","objects.2147483648.warp","floatingChildBiomes.-1.objects.0.warp","floatingChildBiomes.0.objects.0.warp","objects.0.warp.extra")) check(FloatingPlacementConstraints.warpContext(bad)==null);
        check(FloatingObjectPalettes.styleContext("objects.0.edit.2147483648.replace.style")==null);
        check(FloatingObjectPlacements.parsePath(resourceBase,"objects.0.warp").entry()==owner);
        check(FloatingPlacementSettings.styleContext(resourceBase,"objects.0.densityStyle.style").entry()==owner);
        check(FloatingPlacementConstraints.warpContext(resourceBase,"objects.0.warp").entry()==owner);
        check(FloatingObjectPalettes.styleContext(resourceBase,"objects.0.stiltSettings.palette.style").entry()==owner);
        for (String base : List.of("regions","dimensions","generators")) for (String field : List.of("floatingChildBiomes.0.floatingObjects.0.warp","floatingChildBiomes.0.floatingObjects.0.densityStyle.style","floatingChildBiomes.0.floatingObjects.0.stiltSettings.palette.style")) rejects(() -> new NoiseStyleFields.Target(base,field,null));
        for (int bad : List.of(-3,Integer.MIN_VALUE)) { rejects(() -> FloatingObjectPlacements.resourceBase(bad));rejects(() -> BiomeObjectPlacements.setAnchor(PATH,added,bad,0,"FLOOR")); }
        for (String base : List.of("dimensions","generators")) for (String field : List.of("objects.0.warp","objects.0.densityStyle.style","objects.0.stiltSettings.palette.style")) rejects(() -> new NoiseStyleFields.Target(base,field,null));
        String otherRoot=FloatingObjectPlacements.listPrefix(owner==FloatingObjectPlacements.REGION_ROOT ? FloatingObjectPlacements.BIOME_ROOT : FloatingObjectPlacements.REGION_ROOT,"objects");
        check(!FloatingObjectPlacements.listPrefix(owner,"objects").equals(otherRoot));
        var drafts=new InspectorDrafts();String prefix=FloatingObjectPlacements.listPrefix(owner,"objects"),root=settings.styleTarget().draftPrefix()+"zoom",floating=FloatingObjectPlacements.listPrefix(0,"floatingObjects")+"0.chance";
        drafts.update(root,"1","2");drafts.update(floating,"1","2");drafts.update(otherRoot+"0.chance","1","2");
        check(!drafts.transitionUnder(prefix,() -> false,() -> { throw new AssertionError(); }) && drafts.count()==3);
        try { drafts.transitionUnder(prefix,() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==3); }
        check(drafts.transitionUnder(prefix,() -> true,() -> {}) && drafts.count()==2 && drafts.contains(floating) && drafts.contains(otherRoot+"0.chance"));
        workspace.replace(PATH,edited);check(Arrays.equals(workspace.read(otherPath),untouchedOther));workspace.save(temp.resolve(resourceBase+"-objects"));workspace.exportZip(temp.resolve(resourceBase+"-objects.zip"));
        var reopened=PackWorkspace.open(temp.resolve(resourceBase+"-objects"));var imported=PackWorkspace.importZip(temp.resolve(resourceBase+"-objects.zip"));
        for (String path : workspace.paths()) check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
        System.out.println("PASS: "+resourceBase+" root object collection lifecycle, every shared nested context, cave anchors/bottom alignment, owner/type guards, floating/raw preservation, scoped drafts and Save/ZIP contracts");
    }
}
