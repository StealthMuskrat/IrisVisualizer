package dev.irisvisualizer.editor;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
public final class WorkspaceTests {
 public static void main(String[] args) throws Exception {
  Path temp=Files.createTempDirectory("iris-editor-test-");
  try {
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
   workspace.replace("biomes/a.json",new byte[]{123});check(workspace.dirty());workspace.save(pack);check(!workspace.dirty());check(Arrays.equals(binary,Files.readAllBytes(pack.resolve("asset.iob"))));
   try(var zip=new ZipOutputStream(Files.newOutputStream(temp.resolve("bad.zip")))) {zip.putNextEntry(new ZipEntry("../escaped"));zip.write(1);zip.closeEntry();}
   boolean rejected=false;try {PackWorkspace.importZip(temp.resolve("bad.zip"));}catch(java.io.IOException expected){rejected=true;}check(rejected);check(!Files.exists(temp.resolve("escaped")));
   System.out.println("PASS: editor workspace round-trip, invalid JSON, binary/empty-directory preservation, defensive bytes, dirty/save and ZIP traversal checks");
  } finally {try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
 }
 private static void check(boolean value){if(!value)throw new AssertionError();}
 private static void rejects(Runnable operation){try{operation.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid edit accepted");}
}
