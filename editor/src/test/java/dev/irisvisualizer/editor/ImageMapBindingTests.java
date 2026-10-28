package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

final class ImageMapBindingTests {
    private static final String PATH="dimensions/maps.json";
    private static final ImageMapBindings.Context ROOT=ImageMapBindings.Context.binding(0),MASK=ROOT.mask(0);
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static byte[] document(String list) { return bytes("{\"imageMaps\":"+list+",\"futureRoot\":9007199254740993}"); }
    private static byte[] single(String entry) { return document("["+entry+"]"); }
    private static LosslessJson entry(byte[] raw,int index) { return ImageMapBindings.entries(new LosslessJson(raw)).get(index); }
    private static LosslessJson mask(byte[] raw,int binding,int index) { return ImageMapBindings.masks(entry(raw,binding)).get(index); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Image-map binding contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected image-map binding operation"); }
    private static byte[] add(byte[] raw,String key,String role,PackWorkspace pack) { return ImageMapBindings.change(PATH,raw,RiverProfiles.Action.ADD,-1,key,"nested/map",role,pack); }
    static void run(Path temp) throws Exception {
        var pack=PackWorkspace.newPack(Map.of(PATH,bytes("{}"),"image-maps/nested/map.json",bytes("opaque malformed map, never decoded"),"image-maps/other.json",bytes("{}"),"image-maps/wrong.JSON",bytes("{}"),"images/opaque.png",new byte[]{1,2,3},"objects/opaque.iob",new byte[]{4,5}));
        check(ImageMapBindings.applications().equals(List.of("TERRAIN_HEIGHT","BIOME","REGION","SURFACE_BLOCK","MASK","CUSTOM")) && ImageMapBindings.operations().equals(List.of("MULTIPLY","MINIMUM","MAXIMUM","ADD","SUBTRACT")));
        check(ImageMapBindings.choices(pack).equals(List.of("nested/map","other")) && ImageMapBindings.defaultToken(ROOT,"application").equals("CUSTOM") && ImageMapBindings.defaultToken(MASK,"operation").equals("MULTIPLY") && ImageMapBindings.defaultToken(MASK,"threshold").equals("0") && ImageMapBindings.defaultToken(MASK,"inverted").equals("false"));
        byte[] absent=bytes("{\r\n \"future\":\"雪\\u0061\",\"large\":9223372036854775807\r\n}\r\n"),created=add(absent,"height","TERRAIN_HEIGHT",pack);
        check(entry(created,0).fields().equals(Set.of("key","map","application")) && entry(created,0).stringToken("key").equals("height"));check(Arrays.equals(absent,ImageMapBindings.change(PATH,created,RiverProfiles.Action.REMOVE,0,null,null,null,pack)));
        for (String role : ImageMapBindings.applications()) {
            byte[] one=add(absent,"one",role,pack);
            if (List.of("MASK","CUSTOM").contains(role)) check(ImageMapBindings.entries(new LosslessJson(add(one,"two",role,pack))).size()==2);
            else { rejects(() -> add(one,"two",role,pack));rejects(() -> ImageMapBindings.change(PATH,one,RiverProfiles.Action.DUPLICATE,0,"two",null,role,pack)); }
            check(entry(ImageMapBindings.change(PATH,one,RiverProfiles.Action.DUPLICATE,0,"copy",null,"CUSTOM",pack),1).stringToken("application").equals("CUSTOM"));
        }
        for (String blank : List.of(""," ","\t\n")) { rejects(() -> add(absent,blank,"CUSTOM",pack));rejects(() -> ImageMapBindings.setKey(PATH,created,0,blank)); }
        for (String key : List.of("height"," height ")) rejects(() -> add(created,key,"CUSTOM",pack));
        check(entry(add(created,"HEIGHT","CUSTOM",pack),1).stringToken("key").equals("HEIGHT"));
        for (String map : List.of("missing","nested/map.json","NESTED/map","wrong","alias:map","../map")) rejects(() -> ImageMapBindings.setMap(PATH,created,ROOT,map,pack));
        rejects(() -> add(absent,"one","UNKNOWN",pack));rejects(() -> add(absent,"one",null,pack));rejects(() -> ImageMapBindings.setChoice(PATH,created,ROOT,"application",null));
        for (String invalid : List.of("null","true","1","{}","\"snippet\"","[null]","[{},1]")) {
            byte[] wrong=document(invalid);rejects(() -> add(wrong,"one","CUSTOM",pack));rejects(() -> ImageMapBindings.setKey(PATH,wrong,0,"one"));rejects(() -> ImageMapBindings.change(PATH,wrong,RiverProfiles.Action.REMOVE,0,null,null,null,pack));
        }
        for (String field : List.of("key","map","application")) for (String invalid : List.of("null","true","1","[]","{}")) {
            byte[] wrong=single("{\""+field+"\":"+invalid+"}");
            if (field.equals("key")) rejects(() -> ImageMapBindings.setKey(PATH,wrong,0,"valid"));else if (field.equals("map")) rejects(() -> ImageMapBindings.setMap(PATH,wrong,ROOT,"other",pack));else rejects(() -> ImageMapBindings.setChoice(PATH,wrong,ROOT,"application","CUSTOM"));
        }
        String raw="{\r\n \"imageMaps\":[{\"key\":\"custo\\u006d\",\"map\":\"nested/map\",\"application\":\"CUSTOM\",\"masks\":[{\"map\":\" we\\u0074 \",\"operation\":\"MULTIPLY\",\"inverted\":false,\"threshold\":0e0,\"falloff\":0,\"futureMask\":\"雪\\u0061\"}],\"futureBinding\":9223372036854775807},{\"key\":\" wet \",\"map\":\"other\",\"application\":\"MASK\"},{\"key\":\"dry\",\"map\":\"other\",\"application\":\"MASK\"}],\"futureRoot\":9007199254740993\r\n}\r\n";
        byte[] source=bytes(raw),copy=source.clone();check(ImageMapBindings.maskKeys(new LosslessJson(source)).equals(List.of("dry","wet")) && ImageMapBindings.findings(new LosslessJson(source)).isEmpty());
        check(Arrays.equals(source,ImageMapBindings.setKey(PATH,source,0,"custom")));check(text(ImageMapBindings.setKey(PATH,source,0,"renamed")).equals(raw.replace("\"key\":\"custo\\u006d\"","\"key\":\"renamed\"")));
        check(text(ImageMapBindings.setMap(PATH,source,ROOT,"other",pack)).equals(raw.replace("\"map\":\"nested/map\"","\"map\":\"other\"")));
        check(text(ImageMapBindings.setMap(PATH,source,MASK,"dry",pack)).equals(raw.replace("\"map\":\" we\\u0074 \"","\"map\":\"dry\"")));
        rejects(() -> ImageMapBindings.setMap(PATH,source,MASK,"nested/map",pack));rejects(() -> ImageMapBindings.setMap(PATH,source,MASK,"custom",pack));rejects(() -> ImageMapBindings.setChoice(PATH,source,ROOT,"application","MASK"));
        byte[] duplicated=ImageMapBindings.change(PATH,source,RiverProfiles.Action.DUPLICATE,0,"copy",null,"BIOME",pack);check(entry(duplicated,1).token("masks").equals(entry(source,0).token("masks")) && entry(duplicated,1).token("futureBinding").equals(entry(source,0).token("futureBinding")) && entry(duplicated,1).stringToken("application").equals("BIOME"));
        byte[] moved=ImageMapBindings.change(PATH,duplicated,RiverProfiles.Action.UP,1,null,null,null,pack);check(entry(moved,0).stringToken("key").equals("copy"));check(entry(ImageMapBindings.change(PATH,moved,RiverProfiles.Action.DOWN,0,null,null,null,pack),1).stringToken("key").equals("copy"));
        byte[] blankRoles=single("{}");check(ImageMapBindings.choice(entry(blankRoles,0),ROOT,"application").equals("CUSTOM"));ImageMapBindings.setKey(PATH,blankRoles,0,"fixed");ImageMapBindings.setMap(PATH,blankRoles,ROOT,"other",pack);
        for (String role : ImageMapBindings.applications()) ImageMapBindings.setChoice(PATH,blankRoles,ROOT,"application",role);
        byte[] unknown=single("{\"application\":\"UNKNOWN\"}");ImageMapBindings.setChoice(PATH,unknown,ROOT,"application","CUSTOM");
        byte[] owner=add(add(add(absent,"custom","CUSTOM",pack),"wet","MASK",pack),"dry","MASK",pack);
        byte[] layered=ImageMapBindings.changeMask(PATH,owner,0,RiverProfiles.Action.ADD,-1,"wet");check(mask(layered,0,0).fields().equals(Set.of("map")) && ImageMapBindings.choice(mask(layered,0,0),MASK,"operation").equals("MULTIPLY"));
        byte[] extra=ImageMapBindings.changeMask(PATH,layered,0,RiverProfiles.Action.ADD,-1,"dry");check(ImageMapBindings.masks(entry(extra,0)).size()==2);
        byte[] reordered=ImageMapBindings.changeMask(PATH,extra,0,RiverProfiles.Action.UP,1,null);check(mask(reordered,0,0).stringToken("map").equals("dry"));reordered=ImageMapBindings.changeMask(PATH,reordered,0,RiverProfiles.Action.DOWN,0,null);check(mask(reordered,0,1).stringToken("map").equals("dry"));
        byte[] duplicateLayer=ImageMapBindings.changeMask(PATH,layered,0,RiverProfiles.Action.DUPLICATE,0,null);check(Arrays.equals(mask(duplicateLayer,0,0).sourceBytes(),mask(duplicateLayer,0,1).sourceBytes()));check(Arrays.equals(owner,ImageMapBindings.changeMask(PATH,layered,0,RiverProfiles.Action.REMOVE,0,null)));
        rejects(() -> ImageMapBindings.changeMask(PATH,owner,1,RiverProfiles.Action.ADD,-1,"dry"));rejects(() -> ImageMapBindings.changeMask(PATH,owner,0,RiverProfiles.Action.ADD,-1,"missing"));
        for (String op : ImageMapBindings.operations()) check(mask(ImageMapBindings.setChoice(PATH,layered,MASK,"operation",op),0,0).stringToken("operation").equals(op));
        check(text(ImageMapBindings.setInverted(PATH,source,MASK,true)).equals(raw.replace("\"inverted\":false","\"inverted\":true")));check(mask(ImageMapBindings.setInverted(PATH,layered,MASK,true),0,0).token("inverted").equals("true"));
        for (String field : List.of("threshold","falloff")) {
            byte[] enabled=ImageMapBindings.enableScalar(PATH,layered,MASK,field);check(mask(enabled,0,0).token(field).equals("0"));rejects(() -> ImageMapBindings.enableScalar(PATH,enabled,MASK,field));rejects(() -> ImageMapBindings.setScalar(PATH,layered,MASK,field,"0.5"));
            for (String value : List.of("0","0.5","1","5e-1")) ImageMapBindings.setScalar(PATH,enabled,MASK,field,value);
            for (String invalid : List.of("-0.1","1.1","NaN","Infinity","1e999","1e9999999999","")) rejects(() -> ImageMapBindings.setScalar(PATH,enabled,MASK,field,invalid));
            byte[] outOfRange=document("[{\"masks\":[{\""+field+"\":2}]}]");ImageMapBindings.setScalar(PATH,outOfRange,MASK,field,"1");
        }
        for (String field : ImageMapBindings.MASK_FIELDS) for (String invalid : List.of("null","true","1","[]","{}")) {
            if (field.equals("inverted") && invalid.equals("true") || List.of("threshold","falloff").contains(field) && invalid.equals("1")) continue;
            byte[] wrong=document("[{\"masks\":[{\""+field+"\":"+invalid+"}]},{\"key\":\"wet\",\"application\":\"MASK\"}]");
            if (field.equals("map")) rejects(() -> ImageMapBindings.setMap(PATH,wrong,MASK,"wet",pack));
            else if (field.equals("operation")) rejects(() -> ImageMapBindings.setChoice(PATH,wrong,MASK,field,"ADD"));
            else if (field.equals("inverted")) rejects(() -> ImageMapBindings.setInverted(PATH,wrong,MASK,false));
            else rejects(() -> ImageMapBindings.setScalar(PATH,wrong,MASK,field,"0.5"));
        }
        for (String masks : List.of("null","[]")) { byte[] node=single("{\"key\":\"a\",\"masks\":"+masks+"}");ImageMapBindings.setChoice(PATH,node,ROOT,"application","MASK"); }
        byte[] nullLayers=ImageMapBindings.changeMask(PATH,document("[{\"key\":\"c\",\"masks\":null},{\"key\":\"wet\",\"application\":\"MASK\"}]"),0,RiverProfiles.Action.ADD,-1,"wet");check(mask(nullLayers,0,0).stringToken("map").equals("wet"));
        for (String invalid : List.of("true","1","{}","\"snippet\"","[null]","[{},1]")) {
            byte[] wrong=document("[{\"masks\":"+invalid+"},{\"key\":\"wet\",\"application\":\"MASK\"}]");rejects(() -> ImageMapBindings.changeMask(PATH,wrong,0,RiverProfiles.Action.ADD,-1,"wet"));rejects(() -> ImageMapBindings.setInverted(PATH,wrong,MASK,true));
        }
        byte[] duplicateKeys=document("[{\"key\":\"wet\",\"application\":\"MASK\"},{\"key\":\" wet \",\"application\":\"CUSTOM\"},{\"key\":\"WET\",\"application\":\"MASK\"}]");check(ImageMapBindings.maskKeys(new LosslessJson(duplicateKeys)).equals(List.of("WET")));check(ImageMapBindings.findings(new LosslessJson(duplicateKeys)).stream().anyMatch(f -> f.contains("duplicate trimmed")));
        byte[] dangling=ImageMapBindings.setKey(PATH,source,1,"renamed-mask");check(ImageMapBindings.findings(new LosslessJson(dangling)).stream().anyMatch(f -> f.contains("no unambiguous")));
        byte[] conflicting=document("[{\"key\":\"a\",\"application\":\"REGION\"},{\"key\":\"b\",\"application\":\"REGION\"},{\"key\":\"c\",\"application\":\"MASK\",\"masks\":[{\"map\":\"a\"}]}]");check(ImageMapBindings.findings(new LosslessJson(conflicting)).stream().anyMatch(f -> f.contains("singleton")) && ImageMapBindings.findings(new LosslessJson(conflicting)).stream().anyMatch(f -> f.contains("additional masks")));
        byte[] repaired=ImageMapBindings.changeMask(PATH,conflicting,2,RiverProfiles.Action.REMOVE,0,null);check(entry(repaired,2).token("masks")==null);
        String maskList="["+String.join(",",Collections.nCopies(65,"{\"map\":\"wet\"}"))+"]";byte[] oversizedMasks=document("[{\"key\":\"a\",\"masks\":"+maskList+"},{\"key\":\"wet\",\"application\":\"MASK\"}]");rejects(() -> ImageMapBindings.setInverted(PATH,oversizedMasks,MASK,true));rejects(() -> ImageMapBindings.setScalar(PATH,oversizedMasks,MASK,"threshold","0.5"));rejects(() -> ImageMapBindings.changeMask(PATH,oversizedMasks,0,RiverProfiles.Action.REMOVE,0,null));
        byte[] oversized=document("["+String.join(",",Collections.nCopies(65,"{\"key\":\"a\",\"masks\":[{}]}"))+"]");rejects(() -> ImageMapBindings.setKey(PATH,oversized,0,"fixed"));rejects(() -> ImageMapBindings.setChoice(PATH,oversized,ROOT,"application","CUSTOM"));rejects(() -> ImageMapBindings.setInverted(PATH,oversized,MASK,true));rejects(() -> ImageMapBindings.change(PATH,oversized,RiverProfiles.Action.REMOVE,0,null,null,null,pack));
        for (String path : List.of("biomes/x.json","regions/x.json","generators/x.json","dimensions/nested/x.json","x.json")) { rejects(() -> ImageMapBindings.setKey(path,created,0,"fixed"));rejects(() -> ImageMapBindings.setInverted(path,source,MASK,true));rejects(() -> ImageMapBindings.change(path,created,RiverProfiles.Action.REMOVE,0,null,null,null,pack)); }
        pack.replace(PATH,source);long revision=pack.revision();var links=ReferenceDiagnostics.inspect(pack,PATH);check(links.stream().filter(l -> l.field().startsWith("imageMaps[")).count()==3 && links.stream().anyMatch(l -> l.field().equals("imageMaps[0].map") && l.target().equals("image-maps/nested/map.json") && l.state()==ReferenceDiagnostics.State.EXACT_FILE));check(pack.revision()==revision);
        pack.replace(PATH,single("{}"));check(ReferenceDiagnostics.inspect(pack,PATH).stream().anyMatch(l -> l.field().equals("imageMaps[0].map") && l.state()==ReferenceDiagnostics.State.ABSENT_REQUIRED));
        pack.replace(PATH,single("{\"map\":\"alias:map\"}"));check(ReferenceDiagnostics.inspect(pack,PATH).stream().anyMatch(l -> l.field().equals("imageMaps[0].map") && l.state()==ReferenceDiagnostics.State.INVALID_KEY));
        pack.replace(PATH,single("{\"map\":1}"));check(ReferenceDiagnostics.inspect(pack,PATH).stream().anyMatch(l -> l.field().equals("imageMaps[0].map") && l.state()==ReferenceDiagnostics.State.UNSUPPORTED));
        var cache=new NoiseStyleSources.CatalogCache();var catalog=cache.choices(pack,"imageMap");pack.replace("image-maps/other.json",bytes("changed content"));check(catalog==cache.choices(pack,"imageMap"));pack.duplicate(PATH,"copied");check(catalog!=cache.choices(pack,"imageMap") && catalog.equals(cache.choices(pack,"imageMap")));var otherPack=PackWorkspace.newPack(Map.of("image-maps/new.json",bytes("{}")));check(cache.choices(otherPack,"imageMap").equals(List.of("new")));
        var drafts=new InspectorDrafts();String roleDraft=ROOT.prefix()+"key",maskDraft=MASK.prefix()+"threshold",outside="outside#zoom";for (String key : List.of(roleDraft,maskDraft,outside)) drafts.update(key,"1","2");check(!drafts.transitionUnder(ImageMapBindings.prefix(),() -> false,() -> {}) && drafts.count()==3);
        try { drafts.transitionUnder(ImageMapBindings.prefix(),() -> true,() -> { throw new IOException("write failed"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==3); }
        check(drafts.transitionUnder(ImageMapBindings.masksPrefix(0),() -> true,() -> {}) && drafts.count()==2 && drafts.contains(roleDraft));check(drafts.transitionUnder(ImageMapBindings.prefix(),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(outside));
        check(Arrays.equals(source,copy));pack.replace(PATH,source);pack.save(temp.resolve("image-map-bindings"));pack.exportZip(temp.resolve("image-map-bindings.zip"));var reopened=PackWorkspace.open(temp.resolve("image-map-bindings"));var imported=PackWorkspace.importZip(temp.resolve("image-map-bindings.zip"));for (String path : pack.paths()) check(Arrays.equals(pack.read(path),reopened.read(path)) && Arrays.equals(pack.read(path),imported.read(path)));
        check(FieldCoverage.fields("imageMapBinding").size()==4 && FieldCoverage.fields("imageMapMask").size()==5 && FieldCoverage.fields("imageMapBinding").stream().allMatch(FieldCoverage.Field::implemented) && FieldCoverage.fields("imageMapMask").stream().allMatch(FieldCoverage.Field::implemented) && FieldCoverage.groups(PATH).containsAll(List.of("imageMapBinding","imageMapMask")));
        System.out.println("PASS: exact image-map binding catalogs, all roles/operations/defaults, trimmed case-sensitive keys/singletons, ordered named MASK layers, native numeric/type/list/owner/size guards, raw structural edits, bounded diagnostics, scoped drafts and lossless Save/ZIP");
    }
}
