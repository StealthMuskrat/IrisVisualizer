package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class BiomeDerivativeTests {
    private static final String PATH="biomes/nested/derivatives.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Biome derivative contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected derivative operation"); }
    private static byte[] document(String field,String token) { return bytes("{\r\n \""+field+"\":"+token+",\"future\":9007199254740993,\"escaped\":\"雪\\u0061\",\"unrelated\":null\r\n}\r\n"); }
    static void run(Path temp) throws Exception {
        check(BiomeDerivatives.defaultKey().equals("minecraft:the_void"));
        byte[] absent=bytes("{\r\n \"future\":9007199254740993\r\n}\r\n");
        check(Arrays.equals(absent,BiomeDerivatives.inherit(PATH,absent)));
        for (String field : BiomeDerivatives.SCALARS) {
            check(BiomeDerivatives.storedKey(new LosslessJson(absent),field)==null);
            byte[] created=BiomeDerivatives.set(PATH,absent,field,"custom:future/biome");
            check(new LosslessJson(created).stringToken(field).equals("custom:future/biome") && text(created).contains("9007199254740993"));
            byte[] original=document(field,"\"Custom:Stored/Unknown\\u0061\""),copy=original.clone();
            check(text(BiomeDerivatives.set(PATH,original,field,"minecraft:plains")).equals(text(original).replace("\"Custom:Stored/Unknown\\u0061\"","\"minecraft:plains\"")));
            for (String value : List.of("minecraft:plains","plains","custom:future/biome"," Mixed Case ","custom:雪","custom:quote\"slash\\")) check(new LosslessJson(BiomeDerivatives.set(PATH,original,field,value)).stringToken(field).equals(value));
            for (String bad : List.of(""," ","\t","\n")) rejects(() -> BiomeDerivatives.set(PATH,original,field,bad));
            rejects(() -> BiomeDerivatives.set(PATH,original,field,null));
            for (String bad : List.of("true","1","{}","[]")) {
                byte[] invalid=document(field,bad);rejects(() -> BiomeDerivatives.set(PATH,invalid,field,"plains"));rejects(() -> BiomeDerivatives.storedKey(new LosslessJson(invalid),field));
                if (field.equals("vanillaDerivative")) rejects(() -> BiomeDerivatives.inherit(PATH,invalid));
            }
            byte[] nullField=document(field,"null");
            if (field.equals("vanillaDerivative")) {
                check(BiomeDerivatives.storedKey(new LosslessJson(nullField),field)==null);
                check(new LosslessJson(BiomeDerivatives.set(PATH,nullField,field,"plains")).stringToken(field).equals("plains"));
                check(text(BiomeDerivatives.inherit(PATH,original)).equals(text(original).replace("\"Custom:Stored/Unknown\\u0061\"","null")));
                check(Arrays.equals(nullField,BiomeDerivatives.inherit(PATH,nullField)));
            } else { rejects(() -> BiomeDerivatives.set(PATH,nullField,field,"plains"));rejects(() -> BiomeDerivatives.storedKey(new LosslessJson(nullField),field)); }
            check(Arrays.equals(original,copy));
        }
        for (String field : BiomeDerivatives.LISTS) {
            check(BiomeDerivatives.entries(new LosslessJson(absent),field)==null);
            byte[] enabled=BiomeDerivatives.enable(PATH,absent,field);check(BiomeDerivatives.entries(new LosslessJson(enabled),field).isEmpty());
            rejects(() -> BiomeDerivatives.enable(PATH,enabled,field));rejects(() -> BiomeDerivatives.change(PATH,absent,field,RiverProfiles.Action.ADD,-1,"plains"));
            byte[] added=BiomeDerivatives.change(PATH,enabled,field,RiverProfiles.Action.ADD,-1,"minecraft:plains");
            check(BiomeDerivatives.entries(new LosslessJson(added),field).equals(List.of("minecraft:plains")));
            check(BiomeDerivatives.entries(new LosslessJson(BiomeDerivatives.change(PATH,added,field,RiverProfiles.Action.REMOVE,0,null)),field).isEmpty());
            byte[] original=document(field,"[ \"custom:stored\\u0061\",\r\n \"minecraft:plains\", \"minecraft:plains\" ]"),copy=original.clone();
            check(text(BiomeDerivatives.edit(PATH,original,field,0,"ocean")).equals(text(original).replace("\"custom:stored\\u0061\"","\"ocean\"")));
            byte[] duplicate=BiomeDerivatives.change(PATH,original,field,RiverProfiles.Action.DUPLICATE,0,null);
            check(BiomeDerivatives.entries(new LosslessJson(duplicate),field).equals(List.of("custom:storeda","custom:storeda","minecraft:plains","minecraft:plains")));
            check(text(duplicate).indexOf("\"custom:stored\\u0061\"")!=text(duplicate).lastIndexOf("\"custom:stored\\u0061\""));
            byte[] down=BiomeDerivatives.change(PATH,original,field,RiverProfiles.Action.DOWN,0,null);
            check(BiomeDerivatives.entries(new LosslessJson(down),field).equals(List.of("minecraft:plains","custom:storeda","minecraft:plains")) && text(down).contains("\"custom:stored\\u0061\""));
            check(Arrays.equals(original,BiomeDerivatives.change(PATH,down,field,RiverProfiles.Action.UP,1,null)));
            check(BiomeDerivatives.entries(new LosslessJson(BiomeDerivatives.change(PATH,original,field,RiverProfiles.Action.REMOVE,1,null)),field).equals(List.of("custom:storeda","minecraft:plains")));
            rejects(() -> BiomeDerivatives.change(PATH,original,field,RiverProfiles.Action.UP,0,null));rejects(() -> BiomeDerivatives.change(PATH,original,field,RiverProfiles.Action.DOWN,2,null));
            rejects(() -> BiomeDerivatives.change(PATH,original,field,null,0,null));rejects(() -> BiomeDerivatives.edit(PATH,original,field,3,"plains"));
            rejects(() -> BiomeDerivatives.edit(PATH,original,field,-1,"plains"));
            for (String bad : List.of(""," ","\t")) { rejects(() -> BiomeDerivatives.edit(PATH,original,field,0,bad));rejects(() -> BiomeDerivatives.change(PATH,original,field,RiverProfiles.Action.ADD,-1,bad)); }
            for (String bad : List.of("null","{}","true","1","\"plains\"","[null]","[\"plains\",1]")) {
                byte[] invalid=document(field,bad);rejects(() -> BiomeDerivatives.entries(new LosslessJson(invalid),field));rejects(() -> BiomeDerivatives.enable(PATH,invalid,field));
                rejects(() -> BiomeDerivatives.change(PATH,invalid,field,RiverProfiles.Action.ADD,-1,"plains"));rejects(() -> BiomeDerivatives.edit(PATH,invalid,field,0,"plains"));
                check(new LosslessJson(BiomeDerivatives.set(PATH,invalid,"derivative","plains")).token(field).equals(bad));
            }
            var drafts=new InspectorDrafts();String entryKey=BiomeDerivatives.prefix(field)+"0.value",newKey=BiomeDerivatives.prefix(field)+"new",scalarKey=BiomeDerivatives.prefix("derivative")+"value",otherKey=BiomeDerivatives.prefix(field.equals("biomeScatter") ? "biomeSkyScatter" : "biomeScatter")+"0.value",styleKey=NoiseStyleFields.Target.biome("biomeStyle").draftPrefix()+"zoom";
            for (String key : List.of(entryKey,newKey,scalarKey,otherKey,styleKey)) drafts.update(key,"before","after");
            check(!drafts.transitionUnder(BiomeDerivatives.prefix(field),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==5);
            try { drafts.transitionUnder(BiomeDerivatives.prefix(field),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==5); }
            check(drafts.transitionUnder(BiomeDerivatives.prefix(field),() -> true,() -> {}) && drafts.count()==3 && drafts.contains(styleKey) && drafts.contains(scalarKey) && drafts.contains(otherKey));
            check(Arrays.equals(original,copy));
            var workspace=PackWorkspace.newPack(Map.of(PATH,original,"biomes/created.json",added,"biomes/inherit.json",BiomeDerivatives.inherit(PATH,absent)));
            workspace.replace(PATH,BiomeDerivatives.edit(PATH,workspace.read(PATH),field,0,"custom:future/biome"));
            workspace.save(temp.resolve("derivative-"+field));workspace.exportZip(temp.resolve("derivative-"+field+".zip"));
            var reopened=PackWorkspace.open(temp.resolve("derivative-"+field));var imported=PackWorkspace.importZip(temp.resolve("derivative-"+field+".zip"));
            for (String path : workspace.paths()) check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
        }
        var target=NoiseStyleFields.Target.biome("biomeStyle");check(NoiseStyleFields.contextDefault(target).equals("SIMPLEX"));
        byte[] style=NoiseStyleFields.enable(PATH,absent,target,"style");check(new LosslessJson(style).stringPath("biomeStyle.style").equals("SIMPLEX"));
        check(new LosslessJson(NoiseStyleFields.enable(PATH,bytes("{\"biomeStyle\":{}}"),target,"style")).stringPath("biomeStyle.style").equals("FLAT"));
        byte[] original=bytes("{\"biomeStyle\":{\"style\":\"UNKNOWN\",\"zoom\":1e0,\"exponent\":1,\"cellularFrequency\":0,\"cellularZoom\":1,\"expression\":\"kept\",\"cacheSize\":9007199254740993},\"biomeScatter\":[\"custom:unknown\"],\"derivative\":null}");
        for (String choice : NoiseStyleFields.choices()) check(text(NoiseStyleFields.editStyle(PATH,original,target,choice)).equals(text(original).replace("\"UNKNOWN\"","\""+choice+"\"")));
        for (String field : List.of("zoom","exponent","cellularFrequency","cellularZoom")) {
            byte[] enabled=NoiseStyleFields.enable(PATH,absent,target,field);check(new LosslessJson(enabled).stringPath("biomeStyle.style").equals("SIMPLEX"));
            check(new LosslessJson(enabled).tokenPath("biomeStyle."+field).equals(NoiseStyleFields.defaultToken(field)));
            rejects(() -> NoiseStyleFields.editText(PATH,original,target,field,"NaN"));rejects(() -> NoiseStyleFields.editText(PATH,original,target,field,"Infinity"));
        }
        NoiseStyleFields.editText(PATH,original,target,"zoom","0.00001");rejects(() -> NoiseStyleFields.editText(PATH,original,target,"zoom","0.000001"));
        NoiseStyleFields.editText(PATH,original,target,"exponent","64");rejects(() -> NoiseStyleFields.editText(PATH,original,target,"exponent","65"));
        byte[] inactive=NoiseStyleFields.editText(PATH,original,target,"cellularZoom","0");rejects(() -> NoiseStyleFields.editText(PATH,inactive,target,"cellularFrequency","1"));
        byte[] active=NoiseStyleFields.editText(PATH,original,target,"cellularFrequency","1");rejects(() -> NoiseStyleFields.editText(PATH,active,target,"cellularZoom","0"));
        for (String bad : List.of("null","[]","true","\"snippet/style\"")) {
            byte[] invalid=document("biomeStyle",bad);rejects(() -> NoiseStyleFields.enable(PATH,invalid,target,"style"));rejects(() -> NoiseStyleFields.editStyle(PATH,invalid,target,"SIMPLEX"));
            check(new LosslessJson(BiomeDerivatives.set(PATH,invalid,"derivative","plains")).token("biomeStyle").equals(bad));
        }
        for (String field : List.of("zoom","exponent","cellularFrequency","cellularZoom")) {
            byte[] invalid=bytes("{\"biomeStyle\":{\"style\":\"SIMPLEX\",\""+field+"\":\"1\"}}");
            rejects(() -> NoiseStyleFields.editText(PATH,invalid,target,field,"1"));
            check(new LosslessJson(NoiseStyleFields.editStyle(PATH,invalid,target,"FLAT")).tokenPath("biomeStyle."+field).equals("\"1\""));
        }
        var workspace=PackWorkspace.newPack(Map.of(PATH,original,"biomes/defaults.json",style));
        workspace.replace(PATH,NoiseStyleFields.editText(PATH,workspace.read(PATH),target,"zoom","2"));
        workspace.replace(PATH,BiomeDerivatives.set(PATH,workspace.read(PATH),"vanillaDerivative","custom:unknown"));
        workspace.replace(PATH,BiomeDerivatives.inherit(PATH,workspace.read(PATH)));
        workspace.save(temp.resolve("derivative-styles"));workspace.exportZip(temp.resolve("derivative-styles.zip"));
        var reopened=PackWorkspace.open(temp.resolve("derivative-styles"));var imported=PackWorkspace.importZip(temp.resolve("derivative-styles.zip"));
        for (String path : workspace.paths()) check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
        for (String path : List.of("dimensions/a.json","regions/a.json","generators/a.json","a.json","biomes/a.txt")) {
            rejects(() -> BiomeDerivatives.set(path,absent,"derivative","plains"));rejects(() -> BiomeDerivatives.inherit(path,absent));
            rejects(() -> BiomeDerivatives.enable(path,absent,"biomeScatter"));rejects(() -> BiomeDerivatives.change(path,bytes("{\"biomeScatter\":[]}"),"biomeScatter",RiverProfiles.Action.ADD,-1,"plains"));
            rejects(() -> NoiseStyleFields.enable(path,absent,target,"style"));
        }
        rejects(() -> BiomeDerivatives.set(PATH,absent,"carvingBiome","plains"));rejects(() -> BiomeDerivatives.enable(PATH,absent,"derivative"));
        rejects(() -> new NoiseStyleFields.Target("dimensions","biomeStyle",null));rejects(() -> new NoiseStyleFields.Target("biomes","biomeStyle",null,0));
        check(FieldCoverage.fields("biome").stream().filter(f -> BiomeDerivatives.EDITABLE.contains(f.name()) || f.name().equals("biomeStyle")).allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: biome derivative defaults/inheritance and literal keys, lossless ground/sky scatter operations and fallback states, SIMPLEX/FLAT styles and cellular guards, typed owner/draft isolation and Save/ZIP contracts");
    }
}
