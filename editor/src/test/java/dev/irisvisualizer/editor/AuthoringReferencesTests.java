package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

final class AuthoringReferencesTests {
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable action){try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected typed reference rejection");}
    static void run(Path temp) throws Exception {
        var files=new LinkedHashMap<String,byte[]>();var keys=new ArrayList<String>();
        for(int i=0;i<430;i++)keys.add(String.format(Locale.ROOT,"pick_%04d",i));
        for(String family:List.of("biomes","regions","generators","dimensions","structures","expressions","image-maps"))for(String key:keys)files.put(family+"/"+key+".json",bytes("invalid target JSON"));
        for(String key:keys){files.put("objects/"+key+".iob",new byte[]{0,-1,7});files.put("images/"+key+".png",new byte[]{-1,0,3});}
        for(String family:List.of("biomes","regions")){files.put(family+"/dotted.name.json",bytes("not parsed"));files.put(family+"/nested/雪#key.json",bytes("not parsed"));files.put(family+"/upper.JSON",bytes("{}"));}
        files.put("dimensions/nested/ignored.json",bytes("{}"));files.put("dimensions/none.json",bytes("{}"));files.put("images/upper.PNG",new byte[]{1});files.put("images/photo.jpg",new byte[]{1});files.put("objects/upper.IOB",new byte[]{1});
        String dimension="dimensions/host.json",biome="biomes/host.json",region="regions/host.json",map="image-maps/host.json";
        byte[] raw=bytes("{\r\n \"future\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}");
        files.put(dimension,raw);files.put(region,bytes("{\"landBiomes\":[\"missing\"],\"future\":9007199254740993}"));
        files.put(biome,bytes("{\"generators\":[{\"generator\":\"missing\"}],\"children\":[\"missing\"],\"floatingChildBiomes\":[{\"biome\":\"missing\",\"extraObjects\":[{\"place\":[\"missing\"]}]}],\"future\":9007199254740993}"));
        files.put(map,bytes("{\"source\":\"missing\",\"future\":9007199254740993}"));
        var pack=PackWorkspace.newPack(files);var cache=new AuthoringReferences.Cache();var retained=new EnumMap<AuthoringReferences.Kind,ReferenceChoices.Catalog>(AuthoringReferences.Kind.class);
        var drafts=new InspectorDrafts();drafts.update("generator#0.min","0","typing");long revision=pack.revision(),resources=pack.resourceRevision();
        for(var kind:AuthoringReferences.Kind.values()){
            var catalog=cache.catalog(pack,kind);retained.put(kind,catalog);var view=new ReferenceChoices.View();var all=new ArrayList<String>();
            for(int page=0;page<3;page++){var result=view.page(catalog,"pick_",page);check(result.entries().size()<=200,"bounded authoring picker page");all.addAll(result.entries());}
            var expected=kind==AuthoringReferences.Kind.DIMENSION_PATHS ? keys.stream().map(key->"dimensions/"+key+".json").toList() : keys;
            check(all.equals(expected),"all late-page policy choices reachable: "+kind);
            check(catalog==cache.catalog(pack,kind),"one metadata catalog per owner/revision/policy");
        }
        check(pack.revision()==revision && pack.resourceRevision()==resources && pack.undoCount()==0 && drafts.any(),"browsing is not an edit or draft transition");
        for(String path:files.keySet())check(Arrays.equals(pack.read(path),files.get(path)),"no target decoding or payload mutation");
        check(retained.get(AuthoringReferences.Kind.BIOMES).keys().contains("dotted.name") && retained.get(AuthoringReferences.Kind.BIOMES).keys().contains("nested/雪#key"),"ordinary exact Unicode/dotted file keys retained");
        check(!retained.get(AuthoringReferences.Kind.SUBTERRAIN_BIOMES).keys().contains("dotted.name") && !retained.get(AuthoringReferences.Kind.SUBTERRAIN_REGIONS).keys().contains("nested/雪#key"),"native subterrain syntax remains a separate policy");
        check(!retained.get(AuthoringReferences.Kind.DIMENSIONS).keys().contains("nested/ignored") && retained.get(AuthoringReferences.Kind.DIMENSIONS).keys().contains("none"),"top-level dimension and reserved upper sentinel rules remain separate");
        check(retained.get(AuthoringReferences.Kind.PNG_SOURCES).keys().size()==430 && retained.get(AuthoringReferences.Kind.OBJECTS).keys().size()==430,"lowercase PNG/iob extension policies");
        var left=new ReferenceChoices.View();var right=new ReferenceChoices.View();var last=left.page(retained.get(AuthoringReferences.Kind.BIOMES),"pick_",2);right.page(retained.get(AuthoringReferences.Kind.BIOMES),"pick_00",0);
        check(left.page(retained.get(AuthoringReferences.Kind.BIOMES),"PICK_",2)==last,"same-family pickers keep independent filters/cursors");
        String late=last.entries().getLast();check(late.equals("pick_0429"),"exact late key");
        pack.replace(biome,BiomeGeneratorLinks.assign(biome,pack.read(biome),0,pack,late));pack.replace(biome,BiomeChildren.edit(biome,pack.read(biome),0,late,pack));
        pack.replace(region,RegionFields.editArray(region,pack.read(region),"landBiomes",0,late,pack));
        pack.replace(dimension,DimensionFields.editArray(dimension,bytes("{\"regions\":[\"missing\"],\"hydrology\":{},\"future\":9007199254740993}"),"regions",0,late,pack));
        pack.replace(dimension,StructurePlacements.change(dimension,pack.read(dimension),RiverProfiles.Action.ADD,-1,"late","IRIS",late,pack));
        pack.replace(dimension,StructurePlacements.changeReference(dimension,pack.read(dimension),0,"caveBiomes",RiverProfiles.Action.ADD,-1,late,pack));
        pack.replace(dimension,SubterrainFeatures.change(dimension,pack.read(dimension),RiverProfiles.Action.ADD,-1,"late",late,pack));
        pack.replace(dimension,SubterrainFeatures.changeRegion(dimension,pack.read(dimension),0,RiverProfiles.Action.ADD,-1,late,pack));
        pack.replace(biome,FloatingBiomes.setBiome(biome,pack.read(biome),0,late,pack));
        pack.replace(biome,FloatingObjectPlacements.setReference(biome,pack.read(biome),0,"extraObjects",0,"place",0,late,pack));
        pack.replace(map,ImageMapDefinitions.setSource(map,pack.read(map),late,pack));
        pack.replace(dimension,SurfacePools.enable(dimension,pack.read(dimension)));
        pack.replace(dimension,SurfacePools.change(dimension,pack.read(dimension),RiverProfiles.Action.ADD,-1,"late"));
        pack.replace(dimension,SurfacePools.setBiome(dimension,pack.read(dimension),0,late,pack));
        pack.replace(dimension,DimensionStacks.setUpper(dimension,pack.read(dimension),late,pack));
        check(new LosslessJson(pack.read(dimension)).stringToken("upperDimension").equals(late),"upper dimension uses late page exact key");
        pack.replace(dimension,DimensionStacks.setUpper(dimension,pack.read(dimension),null,pack));
        pack.replace(dimension,DimensionStacks.create(dimension,pack.read(dimension),late,pack));
        check(DimensionStacks.layers(DimensionStacks.object(new LosslessJson(pack.read(dimension)))).equals(List.of(late,"host")),"dimension stack staging authority remains typed whole-order validation");
        check(new LosslessJson(pack.read(biome)).objectArray("generators").getFirst().stringToken("generator").equals(late) && new LosslessJson(pack.read(region)).stringArrayPath("landBiomes").equals(List.of(late)),"generator and biome membership assignments");
        check(StructurePlacements.strings(StructurePlacements.entries(new LosslessJson(pack.read(dimension))).getFirst(),"structures").equals(List.of(late)),"exact late structure source");
        check(SubterrainFeatures.regions(SubterrainFeatures.entries(new LosslessJson(pack.read(dimension))).getFirst()).equals(List.of(late)),"exact late native region restriction");
        check(FloatingObjectPlacements.pool(FloatingObjectPlacements.placements(FloatingBiomes.entries(new LosslessJson(pack.read(biome))).getFirst(),"extraObjects").getFirst(),"place").equals(List.of(late)),"late opaque object pool assignment");
        check(new LosslessJson(pack.read(map)).stringToken("source").equals(late),"late opaque PNG source assignment without decoding");
        rejects(()->SubterrainFeatures.setBiome(dimension,pack.read(dimension),0,"dotted.name",pack));rejects(()->DimensionStacks.setUpper(dimension,raw,"none",pack));rejects(()->DimensionStacks.create(dimension,raw,"host",pack));
        rejects(()->BiomeGeneratorLinks.assign(dimension,raw,0,pack,late));rejects(()->ImageMapDefinitions.setSource(map,pack.read(map),"upper",pack));
        pack.replace(biome,FloatingBiomes.setBiome(biome,pack.read(biome),0,"",pack));pack.replace(dimension,SurfacePools.setBiome(dimension,pack.read(dimension),0,"",pack));pack.replace(dimension,SubterrainFeatures.setBiome(dimension,pack.read(dimension),0,null,pack));
        check(FloatingBiomes.entries(new LosslessJson(pack.read(biome))).getFirst().stringToken("biome").isEmpty() && SubterrainFeatures.text(SubterrainFeatures.entries(new LosslessJson(pack.read(dimension))).getFirst(),"biome").isEmpty(),"empty selections require explicit typed operations");
        for(String path:List.of(dimension,biome,region,map))check(new LosslessJson(pack.read(path)).token("future").equals("9007199254740993"),"raw long/unknown data preserved by late assignments");
        for(var kind:AuthoringReferences.Kind.values())check(cache.catalog(pack,kind)==retained.get(kind),"byte edits preserve path-only catalog identity");
        for(String path:files.keySet())if(!List.of(dimension,biome,region,map).contains(path))check(Arrays.equals(pack.read(path),files.get(path)),"unrelated opaque/invalid targets preserved");
        check(drafts.any(),"filtering and reference assignments leave unrelated numeric drafts intact");
        pack.duplicate("objects/"+late+".iob","new_object");check(cache.catalog(pack,AuthoringReferences.Kind.OBJECTS)!=retained.get(AuthoringReferences.Kind.OBJECTS) && cache.catalog(pack,AuthoringReferences.Kind.OBJECTS).keys().contains("new_object"),"structural addition invalidates shared metadata");
        var beforeClear=cache.catalog(pack,AuthoringReferences.Kind.BIOMES);cache.clear();check(cache.catalog(pack,AuthoringReferences.Kind.BIOMES)!=beforeClear,"Clear releases metadata");
        var small=PackWorkspace.newPack(Map.of("biomes/only.json",bytes("invalid")));var smallCatalog=cache.catalog(small,AuthoringReferences.Kind.BIOMES);small.save(temp.resolve("authoring-references-small"));check(cache.catalog(small,AuthoringReferences.Kind.BIOMES)==smallCatalog,"Save retains path-only catalog identity");
        small.replace("biomes/only.json",bytes("changed invalid"));small.undo();check(cache.catalog(small,AuthoringReferences.Kind.BIOMES)==smallCatalog,"byte edit and Undo retain path-only metadata");
        var other=PackWorkspace.newPack(Map.of("biomes/other.json",bytes("{}")));check(cache.catalog(other,AuthoringReferences.Kind.BIOMES).keys().equals(List.of("other")),"equal-revision owner isolation");
        System.out.println("PASS: bounded authoring reference pages across all twelve path-only catalog policies, complete late-page biome/generator/dimension/structure/subterrain/object/PNG choices with independent views, unchanged exact/native syntax and reserved dimension guards, explicit typed late-page assignments and empty/self semantics, raw long/unknown/opaque bytes and drafts retained, revision/owner/Clear/Save/Undo cache isolation; no target decoding or native previews");
    }
}
