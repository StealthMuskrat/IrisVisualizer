package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class InlineBlockDataTests {
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] b) { return new String(b,StandardCharsets.UTF_8); }
    private static void check(boolean ok) { if (!ok) throw new AssertionError("Inline block-data contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected inline block-data operation"); }
    record Case(InlineBlockData.Context context,String jsonPath) {
        String path() { return context.resourceBase().equals("dimensions") ? "dimensions/inline.json" : context.resourceBase()+"/nested/inline.json"; }
        byte[] document(String leaf) { return bytes(wrap(jsonPath.split("\\."),0,leaf)); }
        LosslessJson block(byte[] raw) { return at(new LosslessJson(raw),jsonPath.split("\\."),0); }
    }
    private static String wrap(String[] path,int i,String leaf) { if (i==path.length) return leaf;if (path[i].matches("[0-9]+")) return "["+"{},".repeat(Integer.parseInt(path[i]))+wrap(path,i+1,leaf)+",{\"sibling\":9007199254740993}]";return "{\r\n \""+path[i]+"\":"+wrap(path,i+1,leaf)+",\"opaqueOwner\":9223372036854775807,\"escapedOwner\":\"雪\\u0061\"\r\n}"; }
    private static LosslessJson at(LosslessJson node,String[] path,int i) { if (i==path.length) return node;if (i+1<path.length && path[i+1].matches("[0-9]+")) return at(node.objectArray(path[i]).get(Integer.parseInt(path[i+1])),path,i+2);return at(new LosslessJson(bytes(node.token(path[i]))),path,i+1); }
    private static String node(int d,int max,boolean weighted) { return "{\"block\":\"minecraft:oak_log\",\"weight\":"+(weighted && d==0 ? "2e0" : "9007199254740993")+",\"data\":{\"a\\u0078is\":\"\\u0079\",\"level\":3e0,\"waterlogged\":false,\"opaque\":{\"n\":9223372036854775807},\"entitySpawn\":\"minecraft:pig\"},\"tileData\":{\"keep\":9007199254740993},\"debug\":true,\"stamp\":"+d+(d<max ? ",\"backup\":"+node(d+1,max,false) : "")+"}"; }
    private static void onlyBlock(Case c,byte[] before,byte[] after) { String old=text(c.block(before).sourceBytes()),next=text(c.block(after).sourceBytes()),all=text(before);check(all.indexOf(old)==all.lastIndexOf(old) && text(after).equals(all.replace(old,next))); }
    private static List<Case> cases() {
        var out=new ArrayList<Case>();for (String base : OreGenerators.CONTEXTS) out.add(new Case(InlineBlockData.Context.deposit(new DepositGenerators.Context(base,0),0),"deposits.0.palette.0"));
        var owners=new ArrayList<FloatingObjectReplacements.Context>();owners.add(new FloatingObjectReplacements.Context(FloatingObjectPlacements.BIOME_ROOT,"objects",0));owners.add(new FloatingObjectReplacements.Context(FloatingObjectPlacements.REGION_ROOT,"objects",0));for (String list : FloatingObjectPlacements.LIST_FIELDS) owners.add(new FloatingObjectReplacements.Context(0,list,0));owners.add(FloatingObjectReplacements.Context.fixed(new StaticObjects.Context(0)));
        for (var owner : owners) { String placement=owner.fixed()!=null ? "staticObjects.0" : FloatingObjectPlacements.contextPath(owner.entry(),owner.list(),owner.placement());out.add(new Case(InlineBlockData.Context.find(owner,0,0),placement+".edit.0.find.0")); }
        var decorator=new BiomeDecorators.Context(0);for (String field : BiomeDecorators.BLOCK_LISTS) out.add(new Case(InlineBlockData.Context.decorator(decorator,field,0),"decorators.0."+field+".0"));out.add(new Case(InlineBlockData.Context.forced(decorator),"decorators.0.forceBlock"));return List.copyOf(out);
    }
    static void run(Path temp) throws Exception {
        var cases=cases();check(cases.size()==15 && cases.stream().filter(c -> c.context().weightedRoot()).count()==2);var prefixes=new HashSet<String>();int serial=0;
        for (var c : cases) {
            var ctx=c.context();String path=c.path();var scope=ctx.scope();byte[] raw=c.document(node(0,8,ctx.weightedRoot()));check(prefixes.add(path+"|"+scope.prefix()) && scope.prefix().startsWith(ctx.listPrefix()) && !scope.standalone() && !ctx.fluid());
            var ro=InlineBlockData.readOnlyPaths(c.block(raw),ctx.weightedRoot());check(ro.containsAll(List.of("data.opaque","data.entitySpawn","tileData","debug","stamp","backup.weight")) && ro.contains("weight")!=ctx.weightedRoot() && !ro.contains("data") && !ro.contains("backup"));
            for (int depth=0;depth<=8;depth++) {
                var target=new BlockDefinitions.Scope(ctx,depth);int d=depth;check(target.prefix().equals(scope.prefix()+"backup.".repeat(d)) && target.context().equals(ctx));
                check(Arrays.equals(raw,BlockDefinitions.setState(path,raw,target,"axis","axis",BlockDefinitions.Kind.STRING,"y")) && Arrays.equals(raw,BlockDefinitions.setState(path,raw,target,"level","level",BlockDefinitions.Kind.NUMBER,"3.000")));
                byte[] changed=BlockDefinitions.setState(path,raw,target,"axis","facing",BlockDefinitions.Kind.STRING,"north");onlyBlock(c,raw,changed);var block=BlockDefinitions.owner(c.block(changed),d);check(BlockDefinitions.data(block).token("axis")==null && BlockDefinitions.data(block).stringToken("facing").equals("north") && block.token("weight").equals(BlockDefinitions.owner(c.block(raw),d).token("weight")));
                byte[] typed=BlockDefinitions.setState(path,raw,target,"waterlogged","waterlogged",BlockDefinitions.Kind.NUMBER,"0.5");onlyBlock(c,raw,typed);check(BlockDefinitions.data(BlockDefinitions.owner(c.block(typed),d)).token("waterlogged").equals("0.5"));
                byte[] removed=BlockDefinitions.removeState(path,raw,target,"axis");onlyBlock(c,raw,removed);check(BlockDefinitions.data(BlockDefinitions.owner(c.block(removed),d)).token("axis")==null);
                byte[] noData=BlockDefinitions.resetData(path,raw,target);onlyBlock(c,raw,noData);check(BlockDefinitions.owner(c.block(noData),d).token("data")==null);check(BlockDefinitions.data(BlockDefinitions.owner(c.block(BlockDefinitions.createData(path,noData,target)),d)).fields().isEmpty());
                if (d<8) { byte[] noBackup=BlockDefinitions.resetBackup(path,raw,target);onlyBlock(c,raw,noBackup);check(BlockDefinitions.owner(c.block(noBackup),d).token("backup")==null);byte[] created=BlockDefinitions.createBackup(path,noBackup,target,"minecraft:stone");onlyBlock(c,noBackup,created);check(BlockDefinitions.owner(c.block(created),d+1).fields().equals(Set.of("block"))); }
                else rejects(() -> BlockDefinitions.createBackup(path,raw,target,"minecraft:stone"));
                if (d>0) { byte[] material=BlockDefinitions.setBlock(path,raw,target,"some/alias");onlyBlock(c,raw,material);check(BlockDefinitions.owner(c.block(material),d).stringToken("block").equals("some/alias"));check(BlockDefinitions.owner(c.block(BlockDefinitions.resetBlock(path,material,target)),d).token("block")==null); }
                for (String other : List.of("dimensions/a.json","biomes/a.json","regions/a.json","generators/a.json","blocks/a.json","image-maps/a.json","dimensions/nested/a.json")) if (!ctx.resourceBase().equals(ResourceTypes.base(other))) rejects(() -> BlockDefinitions.setState(other,raw,target,"axis","axis",BlockDefinitions.Kind.STRING,"x"));
            }
            rejects(() -> BlockDefinitions.createBackup(path,raw,scope,"mod:new"));rejects(() -> BlockDefinitions.setState(path,bytes("{}"),scope,"axis","axis",BlockDefinitions.Kind.STRING,"x"));rejects(() -> new BlockDefinitions.Scope(ctx,9));
            for (String bad : List.of("null","[]","true","\"snippet\"")) {
                byte[] data=c.document("{\"block\":\"mod:kept\",\"data\":"+bad+",\"weight\":9007199254740993}");check(InlineBlockData.readOnlyPaths(c.block(data),ctx.weightedRoot()).contains("data"));rejects(() -> BlockDefinitions.resetData(path,data,scope));byte[] changed=BlockDefinitions.createBackup(path,data,scope,"minecraft:stone");onlyBlock(c,data,changed);check(c.block(changed).token("data").equals(bad));
                byte[] backup=c.document("{\"block\":\"mod:kept\",\"data\":{},\"backup\":"+bad+"}");if (!bad.equals("null")) { rejects(() -> BlockDefinitions.resetBackup(path,backup,scope));rejects(() -> BlockDefinitions.createBackup(path,backup,scope,"minecraft:stone")); }byte[] changedState=BlockDefinitions.setState(path,backup,scope,null,"axis",BlockDefinitions.Kind.STRING,"y");onlyBlock(c,backup,changedState);check(c.block(changedState).token("backup").equals(bad));
            }
            byte[] blank=c.document("{\"block\":\"minecraft:stone\"}");rejects(() -> BlockDefinitions.createData(path,blank,scope.child()));byte[] enabled=BlockDefinitions.createData(path,blank,scope);onlyBlock(c,blank,enabled);check(BlockDefinitions.data(c.block(enabled)).fields().isEmpty() && c.block(enabled).token("weight")==null);
            byte[] deeper=c.document(node(0,9,ctx.weightedRoot()));check(InlineBlockData.readOnlyPaths(c.block(deeper),ctx.weightedRoot()).contains("backup.".repeat(9).replaceAll("\\.$","")));byte[] edited=BlockDefinitions.setState(path,deeper,scope,"axis","axis",BlockDefinitions.Kind.STRING,"x");onlyBlock(c,deeper,edited);check(c.block(edited).token("backup").equals(c.block(deeper).token("backup")));
            var drafts=new InspectorDrafts();String inside=scope.prefix()+"data.0",backup=scope.prefix()+"backup.data.0",outside="other.owner.scalar";drafts.update(inside,"a","b");drafts.update(backup,"a","b");drafts.update(outside,"a","b");check(!drafts.transitionUnder(ctx.listPrefix(),() -> false,() -> {}) && drafts.count()==3);try { drafts.transitionUnder(ctx.listPrefix(),() -> true,() -> { throw new IOException("write failed"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==3); }check(drafts.transitionUnder(scope.prefix()+"data.",() -> true,() -> {}) && drafts.contains(backup) && drafts.contains(outside));check(drafts.transitionUnder(ctx.listPrefix(),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(outside));
            if (!ctx.forced()) { String array=array(raw,c);String original=text(raw);check(original.indexOf(array)==original.lastIndexOf(array));for (String bad : List.of("null","{}","\"snippet\"","[null]","[1]")) { byte[] invalid=bytes(original.replace(array,bad));rejects(() -> BlockDefinitions.createData(path,invalid,scope)); }String full="["+String.join(",",java.util.Collections.nCopies(65,"{\"block\":\"stone\",\"data\":{}}"))+"]";byte[] tooMany=bytes(original.replace(array,full));rejects(() -> BlockDefinitions.setState(path,tooMany,scope,null,"axis",BlockDefinitions.Kind.STRING,"x"));var next=withIndex(ctx,1);byte[] changed=BlockDefinitions.createData(path,raw,next.scope());check(Arrays.equals(c.block(raw).sourceBytes(),c.block(changed).sourceBytes()));rejects(() -> BlockDefinitions.createData(path,raw,withIndex(ctx,2).scope())); }
            else for (String bad : List.of("null","[]","\"snippet\"")) { byte[] invalid=c.document(bad);rejects(() -> BlockDefinitions.createData(path,invalid,scope)); }
            var pack=PackWorkspace.newPack(Map.of(path,raw,"objects/opaque.iob",new byte[]{0,1,-1},"effects/opaque.json",bytes("not JSON")));byte[] finalData=BlockDefinitions.setState(path,raw,new BlockDefinitions.Scope(ctx,4),"axis","facing",BlockDefinitions.Kind.STRING,"north");pack.replace(path,finalData);String folder="inline-block-data-"+(serial++);pack.save(temp.resolve(folder));pack.exportZip(temp.resolve(folder+".zip"));var saved=PackWorkspace.open(temp.resolve(folder));var imported=PackWorkspace.importZip(temp.resolve(folder+".zip"));for (String key : pack.paths()) check(Arrays.equals(pack.read(key),saved.read(key)) && Arrays.equals(pack.read(key),imported.read(key)));
        }
        var d=new DepositGenerators.Context("biomes",0);var b=new BiomeDecorators.Context(0);var f=new FloatingObjectReplacements.Context(FloatingObjectPlacements.BIOME_ROOT,"objects",0);
        rejects(() -> new InlineBlockData.Context(null,null,-1,null,null,0));rejects(() -> new InlineBlockData.Context(d,null,-1,b,"palette",0));rejects(() -> InlineBlockData.Context.deposit(d,-1));rejects(() -> InlineBlockData.Context.find(f,-1,0));rejects(() -> InlineBlockData.Context.decorator(b,"unknown",0));rejects(() -> InlineBlockData.Context.decorator(b,"forceBlock",0));rejects(() -> InlineBlockData.Context.decorator(b,"palette",-1));
        System.out.println("PASS: inline state properties/backups across 15 deposit/find/decorator/forced-block contexts and eight backup levels, weighted versus ignored-weight scope, owner/list/index/depth/type/size guards, no implicit parents or activation, exact sibling/opaque/legacy preservation, scoped drafts and lossless Save/ZIP without native matching, selection or generation");
    }
    private static InlineBlockData.Context withIndex(InlineBlockData.Context c,int index) { return new InlineBlockData.Context(c.deposit(),c.replacement(),c.rule(),c.decorator(),c.field(),index); }
    private static String array(byte[] raw,Case c) { String[] path=c.jsonPath().split("\\.");String[] parent=Arrays.copyOf(path,path.length-2);return at(new LosslessJson(raw),parent,0).token(path[path.length-2]); }
}
