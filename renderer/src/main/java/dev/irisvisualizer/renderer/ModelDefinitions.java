package dev.irisvisualizer.renderer;

import java.util.*;
import static dev.irisvisualizer.renderer.ModelFields.*;

/** Parsed 26.3 geometry/material declarations. UV defaults and geometric transformations remain unbaked. */
final class ModelDefinitions {
    sealed interface Texture permits Link,Sprite { ModelPlan.Source source(); }
    record Link(String name,ModelPlan.Source source) implements Texture {}
    record Sprite(ModelPlan.Material material,ModelPlan.Source source) implements Texture {}
    record Face(Optional<ModelPlan.Uv> uv,String texture,Optional<ModelPlan.Direction> cullface,int rotation,int tintIndex) {}
    record Element(ModelPlan.Vec3 from,ModelPlan.Vec3 to,Optional<ModelPlan.Rotation> rotation,
                   Optional<ModelPlan.Direction> shadeOverride,int lightEmission,Map<ModelPlan.Direction,Face> faces) {}
    record Raw(AssetId id,Optional<AssetId> parent,Optional<Boolean> ambientOcclusion,Map<String,Texture> textures,Optional<List<Element>> elements) {}
    private static final Set<String> ROOT=Set.of("parent","ambientocclusion","textures","elements","display","gui_light");
    static Raw read(BlockModels.Work work,AssetId id) {
        var prior=work.rawModels.get(id);if(prior!=null)return prior;
        var root=work.document(ClientAssetSnapshot.Kind.MODEL,id);known(root,ROOT,id,"");
        Optional<AssetId> parent=root.containsKey("parent")?Optional.of(reference(text(root.get("parent"),id,"/parent"),id,"/parent")):Optional.empty();
        Optional<Boolean> ao=root.containsKey("ambientocclusion")?Optional.of(flag(root.get("ambientocclusion"),true,id,"/ambientocclusion")):Optional.empty();
        if(root.containsKey("display"))object(root.get("display"),id,"/display");
        if(root.containsKey("gui_light")) { String light=text(root.get("gui_light"),id,"/gui_light");if(!Set.of("front","side").contains(light))throw format(id,"/gui_light","Unsupported GUI light mode"); }
        var textures=new LinkedHashMap<String,Texture>();
        if(root.containsKey("textures"))for(var entry:object(root.get("textures"),id,"/textures").entrySet()) {
            work.limit(++work.bindings<=work.limits.textureBindings(),id,"/textures","Texture declaration budget reached");
            String pointer=child("/textures",entry.getKey()),name=variable(entry.getKey(),id,pointer);var value=entry.getValue();Texture texture;
            if(value instanceof AssetJson.Text string) {
                String target=string.value();var source=new ModelPlan.Source(id,pointer);texture=target.startsWith("#")?new Link(variable(target.substring(1),id,pointer),source):new Sprite(new ModelPlan.Material(reference(target,id,pointer),false),source);
            } else {
                var material=object(value,id,pointer);known(material,Set.of("sprite","force_translucent"),id,pointer);
                texture=new Sprite(new ModelPlan.Material(reference(text(material.get("sprite"),id,pointer+"/sprite"),id,pointer+"/sprite"),flag(material.get("force_translucent"),false,id,pointer+"/force_translucent")),new ModelPlan.Source(id,pointer));
            }
            textures.put(name,texture);
        }
        Optional<List<Element>> elements=Optional.empty();
        if(root.containsKey("elements")) {
            var inputs=array(root.get("elements"),id,"/elements");var result=new ArrayList<Element>();
            for(int i=0;i<inputs.size();i++){work.limit(++work.elements<=work.limits.elements(),id,"/elements","Model element declaration budget reached");result.add(element(work,inputs.get(i),id,"/elements/"+i));}
            elements=Optional.of(List.copyOf(result));
        }
        var result=new Raw(id,parent,ao,Collections.unmodifiableMap(textures),elements);work.rawModels.put(id,result);return result;
    }
    private static Element element(BlockModels.Work work,AssetJson.Value value,AssetId id,String pointer) {
        var fields=object(value,id,pointer);known(fields,Set.of("from","to","rotation","shade_direction_override","light_emission","faces"),id,pointer);
        var from=vector(fields.get("from"),id,pointer+"/from",true);var to=vector(fields.get("to"),id,pointer+"/to",true);
        Optional<ModelPlan.Rotation> rotation=fields.containsKey("rotation")?Optional.of(rotation(fields.get("rotation"),id,pointer+"/rotation")):Optional.empty();
        Optional<ModelPlan.Direction> shade=fields.containsKey("shade_direction_override")?Optional.of(direction(text(fields.get("shade_direction_override"),id,pointer+"/shade_direction_override"),id,pointer+"/shade_direction_override")):Optional.empty();
        int light=integer(fields.get("light_emission"),0,0,15,id,pointer+"/light_emission");
        var faces=new EnumMap<ModelPlan.Direction,Face>(ModelPlan.Direction.class);
        var declarations=object(fields.get("faces"),id,pointer+"/faces");
        for(var entry:declarations.entrySet()) {
            String here=child(pointer+"/faces",entry.getKey());var side=direction(entry.getKey(),id,here);
            work.limit(++work.faces<=work.limits.faces(),id,here,"Model face declaration budget reached");var face=object(entry.getValue(),id,here);
            known(face,Set.of("uv","texture","cullface","rotation","tintindex"),id,here);
            Optional<ModelPlan.Uv> uv=Optional.empty();
            if(face.containsKey("uv")) {
                var tuple=array(face.get("uv"),id,here+"/uv");if(tuple.size()!=4)throw format(id,here+"/uv","Expected four face UV coordinates");
                uv=Optional.of(new ModelPlan.Uv(number(tuple.get(0),id,here+"/uv/0"),number(tuple.get(1),id,here+"/uv/1"),number(tuple.get(2),id,here+"/uv/2"),number(tuple.get(3),id,here+"/uv/3")));
            }
            int spin=integer(face.get("rotation"),0,0,270,id,here+"/rotation");if(!ModelPlan.quarter(spin))throw format(id,here+"/rotation","Face UV rotation must be a quarter turn");
            Optional<ModelPlan.Direction> cull=face.containsKey("cullface")?Optional.of(direction(text(face.get("cullface"),id,here+"/cullface"),id,here+"/cullface")):Optional.empty();
            String texture=text(face.get("texture"),id,here+"/texture");if(texture.startsWith("#"))variable(texture.substring(1),id,here+"/texture");else reference(texture,id,here+"/texture");
            faces.put(side,new Face(uv,texture,cull,spin,integer(face.get("tintindex"),-1,-1,Integer.MAX_VALUE,id,here+"/tintindex")));
        }
        return new Element(from,to,rotation,shade,light,ModelPlan.directions(faces));
    }
    private static ModelPlan.Rotation rotation(AssetJson.Value value,AssetId id,String pointer) {
        var fields=object(value,id,pointer);known(fields,Set.of("origin","axis","angle","x","y","z","rescale"),id,pointer);
        var origin=vector(fields.get("origin"),id,pointer+"/origin",false);boolean rescale=flag(fields.get("rescale"),false,id,pointer+"/rescale");
        float x=0,y=0,z=0;
        if(fields.containsKey("axis") || fields.containsKey("angle")) {
            String axis=text(fields.get("axis"),id,pointer+"/axis");float angle=number(fields.get("angle"),id,pointer+"/angle");
            switch(axis){case "x"->x=angle;case "y"->y=angle;case "z"->z=angle;default->throw format(id,pointer+"/axis","Unsupported element rotation axis");}
        } else { x=number(fields.get("x"),0,id,pointer+"/x");y=number(fields.get("y"),0,id,pointer+"/y");z=number(fields.get("z"),0,id,pointer+"/z"); }
        return new ModelPlan.Rotation(origin,x,y,z,rescale);
    }
}
