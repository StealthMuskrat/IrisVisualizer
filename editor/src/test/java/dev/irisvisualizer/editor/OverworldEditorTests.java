package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Real pinned-pack acceptance through the editor's storage and lossless editing APIs; no generation. */
public final class OverworldEditorTests {
    private static final String REVISION="2e85a52133ad1206c88cad2f50b6f622bea60cb9";
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static Map<String,String> hashes(PackWorkspace pack)throws Exception{
        var result=new TreeMap<String,String>();for(String path:pack.paths())result.put(path,hash(pack.read(path)));return result;
    }
    private static void verify(Path folder,Map<String,String> expected)throws Exception{
        check(hashes(PackWorkspace.open(folder)).equals(expected),"Exact files/bytes after reopening "+folder);
    }
    private static byte[] enable(String family,String path,byte[] bytes,String field){return switch(family){
        case "dimensions"->DimensionFields.enable(path,bytes,field);case "regions"->RegionFields.enable(path,bytes,field);
        case "biomes"->BiomeFields.enable(path,bytes,field);case "generators"->GeneratorFields.enable(path,bytes,field);
        default->throw new IllegalArgumentException(family);
    };}
    private static byte[] edit(String family,String path,byte[] bytes,String field,String value){return switch(family){
        case "dimensions"->DimensionFields.editText(path,bytes,field,value);case "regions"->RegionFields.editText(path,bytes,field,value);
        case "biomes"->BiomeFields.editText(path,bytes,field,value);case "generators"->GeneratorFields.editText(path,bytes,field,value);
        default->throw new IllegalArgumentException(family);
    };}
    private static void editFamily(PackWorkspace pack,String family,String field,String first,String second)throws Exception{
        for(String path:new TreeSet<>(pack.paths())){
            if(!family.equals(ResourceTypes.base(path)))continue;
            byte[] before=pack.read(path);if(before.length>JsonStructure.MAX_BYTES)continue;
            try {
                var original=new LosslessJson(before);String token=original.token(field);
                byte[] enabled=token==null ? enable(family,path,before,field) : before;
                String value=first.equals(token) ? second : first;
                byte[] after=edit(family,path,enabled,field,value);var changed=new LosslessJson(after);
                check(changed.token(field).equals(value),"Applied exact numeric token: "+path+"/"+field);
                var expectedFields=new HashSet<>(original.fields());expectedFields.add(field);
                check(changed.fields().equals(expectedFields),"Only intended field may be enabled");
                for(String key:original.fields())if(!key.equals(field))check(original.token(key).equals(changed.token(key)),"Unrelated/unknown token preserved: "+path+"/"+key);
                pack.replace(path,after);pack.undo();check(Arrays.equals(pack.read(path),before),"Undo exact original");
                pack.redo();check(Arrays.equals(pack.read(path),after),"Redo exact edit");
                System.out.println("PASS: Overworld edit/Undo/Redo "+path+"/"+field+" "+Objects.toString(token,"<absent>")+" -> "+value);return;
            } catch(IllegalArgumentException protectedShape){System.out.println("PROTECTED: "+path+"/"+field+": "+protectedShape.getMessage());}
        }
        throw new AssertionError("No supported real-pack edit candidate for "+family+"/"+field);
    }
    public static void main(String[] args)throws Exception{
        check(args.length==3,"Usage: OverworldEditorTests <pack folder> <pinned revision> <fresh output folder>");
        check(REVISION.equals(args[1]),"Acceptance fixture must match iris.lock.json pack revision");
        Path source=Path.of(args[0]).toRealPath(),output=Path.of(args[2]).toAbsolutePath().normalize();
        check(!Files.exists(output),"Acceptance output must be fresh");
        check(!output.startsWith(source) && !source.startsWith(output),"Fixture and acceptance output must be separate");
        var original=PackWorkspace.open(source);var pristine=hashes(original);
        check(pristine.containsKey("Schema.json") && pristine.containsKey("dimensions/overworld.json"),"Pinned Overworld root required");
        Files.createDirectories(output);Path working=output.resolve("working");
        original.saveCopy(working);verify(working,pristine);
        check(!original.dirty(),"Pristine Save Copy leaves original clean");
        var pack=PackWorkspace.open(working);
        editFamily(pack,"dimensions","fluidHeight","63","64");
        editFamily(pack,"regions","rarity","2","3");
        editFamily(pack,"biomes","rarity","2","3");
        editFamily(pack,"generators","offsetX","17.25","17.5");
        var expected=hashes(pack);check(!expected.equals(pristine),"Actual edits must change pack bytes");
        int history=pack.undoCount();check(pack.dirty(),"Applied edits are pending before Save");
        Path copy=output.resolve("copy"),as=output.resolve("as"),zip=output.resolve("edited.zip");
        pack.saveCopy(copy);check(pack.dirty(),"Save Copy preserves pending baseline");verify(copy,expected);
        pack.exportZip(zip);check(pack.dirty(),"ZIP export preserves pending baseline");
        check(hashes(PackWorkspace.importZip(zip)).equals(expected),"ZIP import restores exact full pack");
        pack.save(working);check(!pack.dirty(),"Save acknowledges changes");verify(working,expected);
        pack.saveAs(as);check(!pack.dirty(),"Save As adopts destination");verify(as,expected);
        check(pack.undoCount()==history,"Storage preserves applied edit history");
        check(hashes(PackWorkspace.open(source)).equals(pristine),"Pinned fixture remains untouched");
        var changed=new TreeSet<String>();for(String path:pristine.keySet())if(!pristine.get(path).equals(expected.get(path)))changed.add(path);
        check(changed.size()==4,"Exactly the four edited resources change");
        Files.writeString(output.resolve("acceptance.txt"),"Pinned Overworld "+REVISION+"\nFiles: "+pristine.size()+"\nChanged resources: "+changed+"\nPASS: pristine and edited full-pack byte hashes; Save/reopen/Copy/As/ZIP; Undo/Redo; original fixture preserved.\nNo native generation or GUI/GPU claim.\n",StandardCharsets.UTF_8);
        System.out.println("PASS: pinned Overworld "+REVISION+"; "+pristine.size()+" files; exact untouched files and opaque assets across Save/reopen/Copy/As/ZIP; original fixture preserved");
    }
}
