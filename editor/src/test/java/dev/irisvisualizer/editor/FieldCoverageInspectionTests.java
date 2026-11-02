package dev.irisvisualizer.editor;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Prepared for end-of-UI execution. Model policy metadata must never become an edit capability. */
final class FieldCoverageInspectionTests {
    private static void check(boolean value,String reason){ResourceOperationTests.check(value,reason);}
    static void run(Path temp)throws Exception{
        var total=FieldCoverage.summary();check(total.fields()==1462 && total.models()==150 && total.sources()==240,"one complete pinned model graph backs the report");
        check(total.generation()==1237 && total.implemented()==1237 && total.readOnly()==196 && total.unclassified()==29 && total.missingPolicies()==0,"policy totals distinguish implemented controls, reviewed read-only fields and unresolved recipe scope");
        check(FieldCoverage.fields("treeSettings").size()==2 && FieldCoverage.fields("treeSettings").stream().allMatch(f->f.classification().equals("read-only") && !f.implemented()),"package-private tree-growth settings inventoried without authoring authority");
        check(FieldCoverage.fields("mod").stream().filter(f->f.classification().equals("unclassified")).count()==14,"modification recipe fields are explicitly pending rather than guessed generation/non-generation");
        var biomeGroups=FieldCoverage.groups("biomes/nested/a.json");check(biomeGroups.getFirst().equals("biome") && biomeGroups.containsAll(List.of("effect","commandRegistry","lootReference","tree","treeSize","objectMarker")),"nested model traversal includes read-only child models");
        check(biomeGroups.size()==new java.util.HashSet<>(biomeGroups).size(),"recursive class graph is finite and unique");
        check(FieldCoverage.groups("entities/a.json").containsAll(List.of("entity","attributeModifier","loot","command")),"read-only entity owner has known field coverage");
        check(FieldCoverage.groups("snippet/style/shared.json").equals(List.of("noiseStyle")),"typed snippet owner exposes its model without adding file operations");
        check(FieldCoverage.groups("snippet/unknown/a.json").isEmpty() && FieldCoverage.groups("dimensions/nested/a.json").isEmpty() && FieldCoverage.groups("images/pixels.png").isEmpty(),"unknown kinds, invalid dimension locations and binary assets do not borrow a model");
        var generation=FieldCoverage.inspect("biomes/a.json","/generators/0/generator");check(generation.classification().equals("generation") && generation.group().equals("biomeGeneratorLink") && generation.controlsAvailable(),"typed object-array pointer resolves the declared generator link");
        var growth=FieldCoverage.inspect("dimensions/a.json","/treeSettings/enabled");check(growth.classification().equals("read-only") && growth.readOnlyContext() && !growth.controlsAvailable(),"known nested tree-growth field remains read-only");
        var inherited=FieldCoverage.inspect("dimensions/a.json","/blockDrops/0/blocks/0/block");check(inherited.classification().equals("read-only") && inherited.readOnlyContext() && !inherited.controlsAvailable(),"generation-class block template inherits its gameplay parent restriction");
        var snippet=FieldCoverage.inspect("snippet/style/shared.json","/expression");check(snippet.classification().equals("read-only") && snippet.readOnlyContext() && !snippet.controlsAvailable(),"generation model inside a read-only snippet is not editable from coverage metadata");
        var mod=FieldCoverage.inspect("mods/a.json","/styleReplacers/0/replace/expression");check(mod.classification().equals("unclassified") && mod.readOnlyContext() && !mod.controlsAvailable(),"unclassified recipe ancestor remains visible through a generation-model child");
        var entity=FieldCoverage.inspect("entities/a.json","/passengers/0/helmet/customNbt/a~1b/c~0d");check(entity.classification().equals("read-only") && !entity.controlsAvailable(),"read-only recursive entities and opaque NBT map keys resolve without inventing Iris fields");
        var state=FieldCoverage.inspect("blocks/a.json","/data/axis");check(state.readOnlyContext() && !state.controlsAvailable(),"map members do not acquire independently declared field controls");
        check(FieldCoverage.inspect("Schema.json","/0").classification().equals("read-only"),"ordering metadata has no generation edit authority");
        for(String pointer:List.of("/fields","/palette.implicit.0","/future","/generators/00/generator","/generators/-1/generator","/generators/2147483648/generator","/generators/~2/generator","not/a/pointer","/children/0/future","/name/future","/"+"x".repeat(JsonStructure.MAX_POINTER_CHARACTERS))){
            var unknown=FieldCoverage.inspect("biomes/a.json",pointer);check(unknown.classification().equals("unknown") && unknown.readOnlyContext() && !unknown.controlsAvailable(),"unknown/control-metadata/malformed/noncanonical/out-of-budget pointer remains read-only");
        }
        check(FieldCoverage.fields(null).isEmpty() && FieldCoverage.groups(null).isEmpty() && FieldCoverage.inspect(null,"/x").classification().equals("unknown") && FieldCoverage.inspect("biomes/a.json",null).classification().equals("unknown"),"unavailable source/group/path state stays bounded and read-only");
        byte[] source=ResourceOperationTests.bytes("{\"treeSettings\":{\"enabled\":true},\"future\":9007199254740993}");var pack=PackWorkspace.newPack(Map.of("dimensions/a.json",source));long revision=pack.revision();
        for(int i=0;i<20;i++){FieldCoverage.groups("dimensions/a.json");FieldCoverage.inspect("dimensions/a.json","/treeSettings/enabled");FieldCoverage.inspect("dimensions/a.json","/future");}
        check(pack.revision()==revision && Arrays.equals(source,pack.read("dimensions/a.json")),"metadata inspection neither reads/mutates the workspace nor inserts optional defaults");
        pack.save(temp.resolve("field-policy-inspection"));check(Arrays.equals(source,PackWorkspace.open(temp.resolve("field-policy-inspection")).read("dimensions/a.json")),"unknown/read-only inspected bytes save and reopen unchanged");
        System.out.println("PASS: shared complete field inventory with honest pending scope, all-access and nested/read-only owner coverage, source-pointer array/map/recursive typing, inherited gameplay/recipe/resource restrictions, control-metadata and invalid-pointer rejection, no write authority/default insertion and exact source persistence");
    }
}
