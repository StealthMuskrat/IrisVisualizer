package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class NoiseStyleSourceTests {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Style source contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected style source operation"); }
    record Case(NoiseStyleFields.Target target,String stylePath) {
        String path() { return target.resourceBase().equals("dimensions") ? "dimensions/source.json" : target.resourceBase()+"/nested/source.json"; }
        byte[] document(String style) { return bytes(wrap(stylePath.split("\\."),0,style)); }
        LosslessJson style(byte[] raw) { return at(new LosslessJson(raw),stylePath.split("\\."),0); }
    }
    private static String wrap(String[] parts,int index,String leaf) {
        if (index==parts.length) return leaf;
        if (parts[index].matches("[0-9]+")) { int slot=Integer.parseInt(parts[index]);return "["+"{},".repeat(slot)+wrap(parts,index+1,leaf)+"]"; }
        return "{\r\n \""+parts[index]+"\":"+wrap(parts,index+1,leaf)+",\"futureSibling\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}";
    }
    private static LosslessJson at(LosslessJson object,String[] parts,int index) {
        String field=parts[index];if (index+1<parts.length && parts[index+1].matches("[0-9]+")) return at(object.objectArray(field).get(Integer.parseInt(parts[index+1])),parts,index+2);
        var child=new LosslessJson(object.token(field).getBytes(StandardCharsets.UTF_8));return index+1==parts.length ? child : at(child,parts,index+1);
    }
    private static void add(List<Case> cases,NoiseStyleFields.Target target) { cases.add(new Case(target,target.objectField())); }
    static List<Case> cases() {
        var result=new ArrayList<Case>();
        for (String field : NoiseStyleFields.DIMENSION_FIELDS) add(result,NoiseStyleFields.Target.dimension(field));
        add(result,DimensionStacks.styleTarget());
        add(result,DimensionCarvings.styleTarget(0));
        add(result,TerrainOverlays.styleTarget(0));
        for (String owner : List.of("stilt","terrain")) add(result,new NativeStructurePalettes.Context(0,owner).styleTarget());
        for (String base : StructurePlacements.CONTEXTS) for (String owner : List.of("stilt","terrain")) add(result,NativeStructurePalettes.Context.placement(base,0,owner).styleTarget());
        for (String base : List.of("biomes","regions")) for (String kind : ProceduralObjects.LIST_FIELDS) { var owner=new ProceduralObjects.Context(base,kind,0);for (String field : ProceduralMaterials.palettes(kind)) add(result,new ProceduralMaterials.Context(owner,field).styleTarget());add(result,new ProceduralMaterials.Context(owner,"stiltSettings.palette").styleTarget()); }
        for (String base : List.of("biomes","regions")) { add(result,new ProceduralAccents.Context(new ProceduralObjects.Context(base,"trees",0),"decorators",0).paletteContext().styleTarget());add(result,new ProceduralAccents.Context(new ProceduralObjects.Context(base,"ruins",0),"accents",0).paletteContext().styleTarget()); }
        for (String list : ExpressionDefinitions.LISTS) add(result,new ExpressionDefinitions.Context(list,0).styleTarget());
        for (String field : NoiseStyleFields.BIOME_FIELDS) add(result,NoiseStyleFields.Target.biome(field));
        for (String field : NoiseStyleFields.TERRAIN_FIELDS) add(result,NoiseStyleFields.Target.terrain(field));
        for (String base : CaveProfileFields.CONTEXTS) {
            for (String field : NoiseStyleFields.CAVE_FIELDS) add(result,NoiseStyleFields.Target.cave(base,field));
            add(result,NoiseStyleFields.Target.threshold(base));result.add(new Case(NoiseStyleFields.Target.module(base,0),"caveProfile.modules.0.style"));
        }
        add(result,NoiseStyleFields.Target.bank());for (String field : RiverChannelRanges.OBJECT_FIELDS) add(result,NoiseStyleFields.Target.channelRange(field));for (String field : RiverChannelRanges.UNDERGROUND_FIELDS) add(result,NoiseStyleFields.Target.undergroundRange(field));
        for (String field : RiverPaletteFields.targets()) add(result,NoiseStyleFields.Target.palette(field));
        add(result,NoiseStyleFields.Target.deepHeight(0));add(result,NoiseStyleFields.Target.deepPalette(0));add(result,NoiseStyleFields.Target.poolPalette(0));add(result,NoiseStyleFields.Target.profilePalette(0));
        for (String field : DimensionPalettes.OBJECT_FIELDS) add(result,NoiseStyleFields.Target.dimensionPalette(field));
        for (String field : BiomeLayers.LIST_FIELDS) add(result,NoiseStyleFields.Target.biomeLayer(field,0));for (String field : BiomeSlabWall.OBJECT_FIELDS) add(result,NoiseStyleFields.Target.slabWall(field));
        for (String field : FloatingNoiseStyles.EDITABLE) add(result,NoiseStyleFields.Target.floating(0,field));add(result,NoiseStyleFields.Target.floatingBottom(0,0));
        for (String field : BiomeDecorators.STYLES) add(result,new BiomeDecorators.Context(0).styleTarget(field));
        for (String base : OreGenerators.CONTEXTS) { add(result,NoiseStyleFields.Target.ore(new OreGenerators.Context(base,0),"chanceStyle"));add(result,NoiseStyleFields.Target.ore(new OreGenerators.Context(base,0),"palette")); }
        var owners=new ArrayList<FloatingObjectReplacements.Context>();owners.add(new FloatingObjectReplacements.Context(FloatingObjectPlacements.BIOME_ROOT,"objects",0));owners.add(new FloatingObjectReplacements.Context(FloatingObjectPlacements.REGION_ROOT,"objects",0));for (String list : FloatingObjectPlacements.LIST_FIELDS) owners.add(new FloatingObjectReplacements.Context(0,list,0));
        for (var owner : owners) {
            add(result,NoiseStyleFields.Target.placementDensity(owner.entry(),owner.list(),owner.placement()));add(result,NoiseStyleFields.Target.placementWarp(owner.entry(),owner.list(),owner.placement()));
            add(result,new FloatingObjectPalettes.Context(owner.entry(),owner.list(),owner.placement(),-1).target().styleTarget());add(result,owner.palette(0).target().styleTarget());
            var noise=new FloatingPlacementOverrides.Context(owner.entry(),owner.list(),owner.placement()).noiseTarget();result.add(new Case(NoiseStyleFields.Target.noise(noise),FloatingObjectPlacements.contextPath(owner.entry(),owner.list(),owner.placement())+".heightmap.style"));
        }
        add(result,new StaticObjects.Context(0).replacements().palette(0).target().styleTarget());
        add(result,FloatingObjectReplacements.Context.structureRoot().palette(0).target().styleTarget());
        result.add(new Case(NoiseStyleFields.Target.noise(NoiseFields.Target.composite(0)),"composite.0.style"));result.add(new Case(NoiseStyleFields.Target.noise(NoiseFields.Target.cliffHeight()),"cliffHeightGenerator.style"));
        return result;
    }
    static void run(Path temp) throws Exception {
        var resources=PackWorkspace.newPack(Map.of("expressions/noise/formula.json",bytes("opaque expression content"),"expressions/second.json",bytes("{}"),"expressions/wrong.JSON",bytes("{}"),"image-maps/map/height.json",bytes("opaque image-map content"),"images/pixels.png",new byte[]{0,127,-1,42},"biomes/keep.json",bytes("{}")));
        check(NoiseStyleSources.choices(resources,"expression").equals(List.of("noise/formula","second")));check(NoiseStyleSources.choices(resources,"imageMap").equals(List.of("map/height")));
        var cache=new NoiseStyleSources.CatalogCache();var keys=cache.choices(resources,"expression");check(keys==cache.choices(resources,"expression"));resources.replace("expressions/second.json",bytes("{\"changed\":true}"));check(keys==cache.choices(resources,"expression"));
        var otherPack=PackWorkspace.newPack(Map.of("expressions/new.json",bytes("{}")));check(cache.choices(otherPack,"expression").equals(List.of("new")));check(cache.choices(resources,"expression").equals(keys));
        for (Case item : cases()) {
            var target=item.target();String path=item.path();byte[] original=item.document("{\"style\":\"UNKNOWN\",\"zoom\":1e0,\"futureStyle\":1e2,\"fracture\":{\"future\":true}}"),copy=original.clone();
            for (String field : NoiseStyleSources.REFERENCES) {
                String key=field.equals("expression") ? "noise/formula" : "map/height";check(Arrays.equals(original,NoiseStyleSources.setReference(path,original,target,field,null,resources)));
                byte[] linked=NoiseStyleSources.setReference(path,original,target,field,key,resources);check(item.style(linked).stringToken(field).equals(key) && !NoiseStyleFields.readOnly(item.style(linked),field));
                byte[] cleared=NoiseStyleSources.setReference(path,linked,target,field,null,resources);check(item.style(cleared).token(field).equals("null"));
                rejects(() -> NoiseStyleSources.setReference(path,original,target,field,"missing",resources));rejects(() -> NoiseStyleSources.setReference(path,original,target,field,key+".json",resources));
                byte[] legacy=item.document("{\""+field+"\":\"legacy\\u0061\",\"futureStyle\":9007199254740993}");check(text(NoiseStyleSources.setReference(path,legacy,target,field,key,resources)).equals(text(legacy).replace("\"legacy\\u0061\"","\""+key+"\"")));
                check(item.style(NoiseStyleSources.setReference(path,item.document("{\""+field+"\":null}"),target,field,key,resources)).stringToken(field).equals(key));
                for (String bad : List.of("[]","{}","1","true")) { byte[] invalid=item.document("{\""+field+"\":"+bad+"}");check(NoiseStyleFields.readOnly(item.style(invalid),field));rejects(() -> NoiseStyleSources.setReference(path,invalid,target,field,key,resources));rejects(() -> NoiseStyleSources.setReference(path,invalid,target,field,null,resources)); }
            }
            byte[] enabled=NoiseStyleSources.enableCache(path,original,target);check(item.style(enabled).token("cacheSize").equals("0") && !NoiseStyleFields.readOnly(item.style(enabled),"cacheSize"));rejects(() -> NoiseStyleSources.enableCache(path,enabled,target));
            for (String value : List.of("0","8192","1e3")) check(item.style(NoiseStyleSources.setCache(path,enabled,target,value)).token("cacheSize").equals(new java.math.BigDecimal(value).toBigIntegerExact().toString()));
            for (String bad : List.of("-1","8193","2147483648","1.000000000000000001","NaN","Infinity")) rejects(() -> NoiseStyleSources.setCache(path,enabled,target,bad));
            rejects(() -> NoiseStyleSources.setCache(path,original,target,"1"));
            for (String bad : List.of("null","[]","{}","true","\"1\"","1.5","9007199254740993")) { byte[] invalid=item.document("{\"cacheSize\":"+bad+"}");check(NoiseStyleFields.readOnly(item.style(invalid),"cacheSize"));rejects(() -> NoiseStyleSources.setCache(path,invalid,target,"1")); }
            check(item.style(NoiseStyleSources.setCache(path,item.document("{\"cacheSize\":-1}"),target,"8192")).token("cacheSize").equals("8192"));
            for (String malformed : List.of("null","[]","true","1","\"snippet/style\"")) { byte[] invalid=item.document(malformed);rejects(() -> NoiseStyleSources.enableCache(path,invalid,target));rejects(() -> NoiseStyleSources.setCache(path,invalid,target,"1"));rejects(() -> NoiseStyleSources.setReference(path,invalid,target,"expression","second",resources)); }
            for (String wrong : List.of("dimensions/wrong.json","biomes/wrong.json","regions/wrong.json","generators/wrong.json")) if (!target.resourceBase().equals(ResourceTypes.base(wrong))) { rejects(() -> NoiseStyleSources.enableCache(wrong,original,target));rejects(() -> NoiseStyleSources.setCache(wrong,enabled,target,"1"));rejects(() -> NoiseStyleSources.setReference(wrong,original,target,"expression","second",resources)); }
            rejects(() -> NoiseStyleSources.enableCache(path,bytes("{}"),target));rejects(() -> NoiseStyleSources.setReference(path,bytes("{}"),target,"expression","second",resources));
            byte[] edited=NoiseStyleSources.setReference(path,enabled,target,"expression","noise/formula",resources);edited=NoiseStyleSources.setReference(path,edited,target,"imageMap","map/height",resources);edited=NoiseStyleSources.setCache(path,edited,target,"8192");
            for (String token : List.of("\"style\":\"UNKNOWN\"","\"zoom\":1e0","\"futureStyle\":1e2","\"fracture\":{\"future\":true}","\"futureSibling\":9007199254740993","\"escaped\":\"雪\\u0061\"")) check(text(edited).contains(token));check(Arrays.equals(original,copy));
            var drafts=new InspectorDrafts();String scalar=target.draftPrefix()+"cacheSize",zoom=target.draftPrefix()+"zoom",other="outside-style#cacheSize";for (String key : List.of(scalar,zoom,other)) drafts.update(key,"1","2");
            check(!drafts.transitionUnder(target.draftPrefix(),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==3);try { drafts.transitionUnder(target.draftPrefix(),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==3); }
            drafts.applied(scalar);check(drafts.count()==2 && drafts.contains(zoom));check(drafts.transitionUnder(target.draftPrefix(),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(other));
        }
        // Explicit style creation remains the existing owner's action; source edits never create a missing leaf.
        var dimension=NoiseStyleFields.Target.dimension("regionStyle");rejects(() -> NoiseStyleSources.enableCache("dimensions/source.json",bytes("{}"),dimension));
        rejects(() -> NoiseStyleSources.setReference("dimensions/source.json",bytes("{}"),dimension,"expression",null,resources));
        byte[] savedSource=bytes("{\"regionStyle\":{\"expression\":\"legacy\\u0061\",\"imageMap\":null,\"cacheSize\":0e0,\"fracture\":{\"future\":1e2}},\"futureRoot\":9007199254740993}");
        var saved=PackWorkspace.newPack(Map.of("dimensions/source.json",savedSource,"expressions/noise/formula.json",resources.read("expressions/noise/formula.json"),"image-maps/map/height.json",resources.read("image-maps/map/height.json"),"objects/opaque.iob",new byte[]{0,127,-1,42}));
        saved.replace("dimensions/source.json",NoiseStyleSources.setReference("dimensions/source.json",saved.read("dimensions/source.json"),dimension,"expression","noise/formula",saved));saved.replace("dimensions/source.json",NoiseStyleSources.setCache("dimensions/source.json",saved.read("dimensions/source.json"),dimension,"8192"));
        saved.save(temp.resolve("style-sources"));saved.exportZip(temp.resolve("style-sources.zip"));var reopened=PackWorkspace.open(temp.resolve("style-sources"));var imported=PackWorkspace.importZip(temp.resolve("style-sources.zip"));for (String path : saved.paths()) check(Arrays.equals(saved.read(path),reopened.read(path)) && Arrays.equals(saved.read(path),imported.read(path)));
        check(FieldCoverage.fields("noiseStyle").stream().filter(FieldCoverage.Field::implemented).count()==10);
        System.out.println("PASS: style references/cache across "+cases().size()+" owner contexts, exact resource catalogs, nullable precedence settings, exact cache bounds/types, strict parent/owner guards, read-only malformed data, independent drafts and lossless Save/ZIP");
    }
}
