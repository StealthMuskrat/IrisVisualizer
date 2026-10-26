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
   InspectorDrafts drafts=new InspectorDrafts();
   drafts.update("fluidHeight","63","64");check(drafts.any() && drafts.count()==1);
   drafts.update("fluidHeight","63","63");check(!drafts.any());
   drafts.update("disabled#1","mod:a","mod:b");drafts.update("disabled#10","mod:a","mod:c");
   drafts.applied("disabled#1");check(!drafts.contains("disabled#1") && drafts.contains("disabled#10"));
   drafts.update("landChance","0.5","invalid");drafts.clearUnder("disabled#");
   check(drafts.count()==1 && drafts.contains("landChance"));drafts.clear();check(!drafts.any());
   var coverage=DimensionFields.readOnlyPaths(new LosslessJson("{\"fluidHeight\":63,\"future\":1,\"importedStructures\":{\"disabled\":[],\"futureNested\":{}}}".getBytes()));
   check(coverage.equals(List.of("future","importedStructures.futureNested")));
   check(DimensionFields.readOnlyPaths(new LosslessJson("{\"importedFeatures\":\"snippet\"}".getBytes())).equals(List.of("importedFeatures")));
   String arrays="{\r\n \"importedStructures\":{\"disabled\":[ \"minecraft:village\",  \"mod:keep\" , \"minecraft:ore\" ],\"unknown\":{\"雪\":true}}\r\n}";
   byte[] arrayEdit=DimensionFields.editArray("dimensions/a.json",arrays.getBytes(java.nio.charset.StandardCharsets.UTF_8),"importedStructures.disabled",1,"mod:new");
   check(new String(arrayEdit,java.nio.charset.StandardCharsets.UTF_8).equals(arrays.replace("mod:keep","mod:new")));
   byte[] arrayRemove=DimensionFields.editArray("dimensions/a.json",arrayEdit,"importedStructures.disabled",1,null);
   check(new LosslessJson(arrayRemove).stringArrayPath("importedStructures.disabled").equals(List.of("minecraft:village","minecraft:ore")));
   byte[] arrayLast=DimensionFields.editArray("dimensions/a.json",arrayRemove,"importedStructures.disabled",1,null);
   byte[] arrayEmpty=DimensionFields.editArray("dimensions/a.json",arrayLast,"importedStructures.disabled",0,null);
   check(new LosslessJson(arrayEmpty).stringArrayPath("importedStructures.disabled").isEmpty());
   byte[] arrayAdd=DimensionFields.editArray("dimensions/a.json",arrayEmpty,"importedStructures.disabled",0,"minecraft:");
   byte[] arrayAppend=DimensionFields.editArray("dimensions/a.json",arrayAdd,"importedStructures.disabled",1,"mod:thing");
   check(new LosslessJson(arrayAppend).stringArrayPath("importedStructures.disabled").equals(List.of("minecraft:","mod:thing")));
   check(new LosslessJson(arrayAppend).tokenPath("importedStructures.unknown").equals("{\"雪\":true}"));
   byte[] arrayFirst=DimensionFields.editArray("dimensions/a.json",arrayAppend,"importedStructures.disabled",0,null);
   check(new LosslessJson(arrayFirst).stringArrayPath("importedStructures.disabled").equals(List.of("mod:thing")));
   byte[] arrayEnabled=DimensionFields.enable("dimensions/a.json","{}".getBytes(),"importedFeatures.disabledExact");
   check(new LosslessJson(arrayEnabled).stringArrayPath("importedFeatures.disabledExact").isEmpty());
   rejects(()->DimensionFields.editArray("dimensions/a.json",arrayEnabled,"importedFeatures.disabledExact",0,"minecraft:"));
   rejects(()->DimensionFields.editArray("dimensions/a.json",arrayEnabled,"importedFeatures.disabledExact",0,"Bad Key"));
   rejects(()->DimensionFields.editArray("dimensions/a.json",arrayEnabled,"importedFeatures.disabledExact",-1,null));
   rejects(()->DimensionFields.editArray("dimensions/a.json",arrayEnabled,"importedFeatures.disabledExact",1,"mod:key"));
   rejects(()->DimensionFields.editArray("biomes/a.json",arrayEnabled,"importedFeatures.disabledExact",0,"mod:key"));
   rejects(()->DimensionFields.editArray("dimensions/a.json","{\"importedFeatures\":{\"disabled\":[1]}}".getBytes(),"importedFeatures.disabled",0,"mod:key"));
   String escaped="{\"importedFeatures\":{\"disabled\":[\"mod:a\\u0062\",\"mod:c\"]}}";
   check(new String(DimensionFields.editArray("dimensions/a.json",escaped.getBytes(),"importedFeatures.disabled",1,"mod:d")).equals(escaped.replace("mod:c","mod:d")));
   String nested="{\r\n  \"importedStructures\": {\"undergroundYShift\": 0, \"unknown\": [\"雪\", {\"x\":1}]}, \"importedStructures.undergroundYShift\": 99\r\n}";
   byte[] nestedEdit=DimensionFields.edit("dimensions/a.json",nested.getBytes(java.nio.charset.StandardCharsets.UTF_8),"importedStructures.undergroundYShift",-64);
   check(new String(nestedEdit,java.nio.charset.StandardCharsets.UTF_8).equals(nested.replace("\"undergroundYShift\": 0","\"undergroundYShift\": -64")));
   var nestedDoc=new LosslessJson(nestedEdit);
   check(nestedDoc.tokenPath("importedStructures.undergroundYShift").equals("-64"));
   check(nestedDoc.token("importedStructures.undergroundYShift").equals("99"));
   byte[] nestedEnable=DimensionFields.enable("dimensions/a.json",nestedEdit,"importedStructures.datapackOverrides");
   check(new LosslessJson(nestedEnable).tokenPath("importedStructures.datapackOverrides").equals("true"));
   check(new LosslessJson(nestedEnable).tokenPath("importedStructures.unknown").equals("[\"雪\", {\"x\":1}]"));
   byte[] newParent=DimensionFields.enable("dimensions/a.json","{}".getBytes(),"importedFeatures.enabled");
   check(new LosslessJson(newParent).tokenPath("importedFeatures.enabled").equals("false"));
   byte[] nativeToggle=DimensionFields.editBoolean("dimensions/a.json",newParent,"importedFeatures.enabled",true);
   check(new LosslessJson(nativeToggle).tokenPath("importedFeatures.enabled").equals("true"));
   check(DimensionFields.description("importedFeatures.enabled").contains("Master switch"));
   rejects(()->DimensionFields.enable("dimensions/a.json","{\"importedFeatures\":\"snippet\"}".getBytes(),"importedFeatures.enabled"));
   rejects(()->DimensionFields.enable("dimensions/a.json","{\"importedFeatures\":null}".getBytes(),"importedFeatures.enabled"));
   rejects(()->DimensionFields.edit("dimensions/a.json",nestedEdit,"importedStructures.undergroundYShift",513));
   rejects(()->DimensionFields.edit("dimensions/a.json",nestedEdit,"importedStructures.undergroundYShift",0.5));
   rejects(()->DimensionFields.enable("dimensions/a.json",nativeToggle,"importedFeatures.enabled"));
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
   Path conflictPack=Files.createDirectory(temp.resolve("conflict-pack"));
   Files.writeString(conflictPack.resolve("a.json"),"original-a");Files.writeString(conflictPack.resolve("b.json"),"original-b");
   var conflict=PackWorkspace.open(conflictPack);conflict.replace("a.json","edited-a".getBytes());conflict.replace("b.json","edited-b".getBytes());
   Files.writeString(conflictPack.resolve("b.json"),"external-b");
   rejectsIo(()->conflict.save(conflictPack));check(conflict.dirty());
   check(Files.readString(conflictPack.resolve("a.json")).equals("original-a"));check(Files.readString(conflictPack.resolve("b.json")).equals("external-b"));
   Files.writeString(conflictPack.resolve("b.json"),"original-b");conflict.save(conflictPack);check(!conflict.dirty());
   conflict.replace("b.json","next-b".getBytes());Files.delete(conflictPack.resolve("b.json"));
   rejectsIo(()->conflict.save(conflictPack));check(conflict.dirty());
   Path occupied=Files.createDirectory(temp.resolve("occupied"));Files.writeString(occupied.resolve("asset.iob"),"keep");
   rejectsIo(()->workspace.save(occupied));check(Files.readString(occupied.resolve("asset.iob")).equals("keep"));check(!Files.exists(occupied.resolve("biomes")));
   Path existingZip=temp.resolve("existing.zip");Files.writeString(existingZip,"keep");
   rejectsIo(()->workspace.exportZip(existingZip));check(Files.readString(existingZip).equals("keep"));workspace.exportZip(existingZip,true);
   check(PackWorkspace.importZip(existingZip).paths().equals(workspace.paths()));
   Path caseZip=temp.resolve("case.zip");
   try(var zip=new ZipOutputStream(Files.newOutputStream(caseZip))) {
    zip.putNextEntry(new ZipEntry("Biomes/a.json"));zip.write(1);zip.closeEntry();
    zip.putNextEntry(new ZipEntry("biomes/b.json"));zip.write(2);zip.closeEntry();
   }
   rejectsIo(()->PackWorkspace.importZip(caseZip));
   Path outside=Files.createDirectory(temp.resolve("outside")), link=temp.resolve("link");
   boolean links=false;
   try {Files.createSymbolicLink(link,outside);links=true;}catch(java.io.IOException | UnsupportedOperationException unavailable) {}
   if(links){rejectsIo(()->workspace.save(link.resolve("nested")));rejectsIo(()->workspace.exportZip(link.resolve("out.zip")));check(!Files.exists(outside.resolve("nested")));Files.delete(link);}
   try(var zip=new ZipOutputStream(Files.newOutputStream(temp.resolve("bad.zip")))) {zip.putNextEntry(new ZipEntry("../escaped"));zip.write(1);zip.closeEntry();}
   boolean rejected=false;try {PackWorkspace.importZip(temp.resolve("bad.zip"));}catch(java.io.IOException expected){rejected=true;}check(rejected);check(!Files.exists(temp.resolve("escaped")));
   System.out.println("PASS: resource hierarchy/filtering, numeric edit preservation, editor workspace round-trip, invalid JSON, binary/empty-directory preservation, defensive bytes, dirty/save and ZIP traversal checks");
  } finally {try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
 }
 @FunctionalInterface private interface IoOperation {void run() throws java.io.IOException;}
 private static void rejectsIo(IoOperation operation) throws java.io.IOException {try{operation.run();}catch(java.io.IOException expected){return;}throw new AssertionError("Unsafe file operation accepted");}
 private static void check(boolean value){if(!value)throw new AssertionError();}
 private static void rejects(Runnable operation){try{operation.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid edit accepted");}
}
