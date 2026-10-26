package dev.irisvisualizer.editor;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
public final class WorkspaceTests {
 public static void main(String[] args) throws Exception {
  Path temp=Files.createTempDirectory("iris-editor-test-");
  try {
   var tree=ResourceTree.build(List.of("biomes/z.json","dimensions/a.json","asset.iob"),List.of("empty"),"");
   check(tree.size()==4 && tree.get(0).folder() && tree.get(3).path().equals("asset.iob"));
   var filtered=ResourceTree.build(List.of("biomes/z.json","dimensions/a.json"),List.of(),"A.JSON");
   check(filtered.size()==1 && filtered.get(0).path().equals("dimensions") && filtered.get(0).children().get(0).path().equals("dimensions/a.json"));
   check(ResourceTree.build(List.of("biomes/z.json"),List.of(),"missing").isEmpty());
   String optional="{\r\n  \"unknown\": {\"nested\":[1,true]}\r\n}";
   byte[] enabled=DimensionFields.enable("dimensions/a.json",optional.getBytes(),"bedrock");
   var enabledDoc=new LosslessJson(enabled);
   check(enabledDoc.token("bedrock").equals("true"));
   check(enabledDoc.token("unknown").equals("{\"nested\":[1,true]}"));
   check(new String(enabled).equals(optional.replace("[1,true]}","[1,true]},\r\n  \"bedrock\": true")));
   byte[] toggled=DimensionFields.editBoolean("dimensions/a.json",enabled,"bedrock",false);
   check(new String(toggled).equals(new String(enabled).replace("\"bedrock\": true","\"bedrock\": false")));
   check(new LosslessJson(DimensionFields.enable("dimensions/a.json","{}".getBytes(),"landChance")).token("landChance").equals("0.625"));
   check(new LosslessJson(DimensionFields.enable("dimensions/a.json","{ \r\n }".getBytes(),"fluidHeight")).token("fluidHeight").equals("63"));
   rejects(()->DimensionFields.enable("dimensions/a.json",enabled,"bedrock"));
   rejects(()->DimensionFields.enable("biomes/a.json",optional.getBytes(),"bedrock"));
   rejects(()->DimensionFields.enable("dimensions/a.json",optional.getBytes(),"unknown"));
   rejects(()->DimensionFields.editBoolean("dimensions/a.json","{\"bedrock\":1}".getBytes(),"bedrock",false));
   rejects(()->DimensionFields.edit("dimensions/a.json",enabled,"bedrock",1));
   rejects(()->DimensionFields.editBoolean("dimensions/a.json",enabled,"fluidHeight",false));
   rejects(()->DimensionFields.edit("dimensions/a.json","{\"landChance\":0.5}".getBytes(),"landChance",1.01));
   String json="{\r\n  \"fluidHeight\" : 50, \"unknown\": {\"fluidHeight\":999}, \"dimensionAngleDeg\": 69, \"text\":\"escaped \\\" quote\"\r\n}";
   byte[] edited=DimensionFields.edit("dimensions/overworld.json",json.getBytes(),"fluidHeight",63);
   check(new String(edited).equals(json.replace(" : 50", " : 63")));
   check(new LosslessJson(edited).token("unknown").equals("{\"fluidHeight\":999}"));
   check(new LosslessJson("{}".getBytes()).token("fluidHeight")==null);
   rejects(()->DimensionFields.edit("dimensions/overworld.json",json.getBytes(),"unknown",3));
   rejects(()->DimensionFields.edit("dimensions/overworld.json",json.getBytes(),"fluidHeight",1.5));
   rejects(()->DimensionFields.edit("dimensions/overworld.json",json.getBytes(),"fluidHeight",2000));
   rejects(()->new LosslessJson("{\"x\":1,\"x\":2}".getBytes()));
   rejects(()->new LosslessJson("{\"x\": [1,]}".getBytes()));
   Path pack=Files.createDirectory(temp.resolve("pack"));Files.createDirectories(pack.resolve("biomes"));Files.createDirectories(pack.resolve("empty"));
   byte[] invalid="{invalid unknown\r\n".getBytes();byte[] binary={0,1,-1,0};
   Files.write(pack.resolve("biomes/a.json"),invalid);Files.write(pack.resolve("asset.iob"),binary);
   var workspace=PackWorkspace.open(pack);workspace.save(pack);
   check(Arrays.equals(invalid,Files.readAllBytes(pack.resolve("biomes/a.json"))));
   workspace.exportZip(temp.resolve("out.zip"));var imported=PackWorkspace.importZip(temp.resolve("out.zip"));
   imported.save(temp.resolve("copy"));check(Arrays.equals(binary,Files.readAllBytes(temp.resolve("copy/asset.iob"))));check(Files.isDirectory(temp.resolve("copy/empty")));
   byte[] exposed=workspace.read("asset.iob");exposed[0]=42;check(workspace.read("asset.iob")[0]==0);
   workspace.replace("biomes/a.json",new byte[]{123});check(workspace.dirty());check(workspace.changed("biomes/a.json"));workspace.save(pack);check(!workspace.dirty());check(!workspace.changed("biomes/a.json"));check(Arrays.equals(binary,Files.readAllBytes(pack.resolve("asset.iob"))));
   try(var zip=new ZipOutputStream(Files.newOutputStream(temp.resolve("bad.zip")))) {zip.putNextEntry(new ZipEntry("../escaped"));zip.write(1);zip.closeEntry();}
   boolean rejected=false;try {PackWorkspace.importZip(temp.resolve("bad.zip"));}catch(java.io.IOException expected){rejected=true;}check(rejected);check(!Files.exists(temp.resolve("escaped")));
   System.out.println("PASS: resource hierarchy/filtering, numeric edit preservation, editor workspace round-trip, invalid JSON, binary/empty-directory preservation, defensive bytes, dirty/save and ZIP traversal checks");
  } finally {try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
 }
 private static void check(boolean value){if(!value)throw new AssertionError();}
 private static void rejects(Runnable operation){try{operation.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid edit accepted");}
}
