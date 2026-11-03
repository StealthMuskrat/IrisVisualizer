package dev.irisvisualizer.renderer;

import java.util.*;
import static dev.irisvisualizer.renderer.ModelFields.*;

/** Resolves ancestor geometry with descendant texture bindings; no filesystem lookup or global cache. */
final class ModelResolver {
    static ModelPlan.Model resolve(BlockModels.Work work,AssetId id) {
        work.check();var previous=work.models.get(id);if(previous!=null)return previous;
        var chain=new ArrayList<ModelDefinitions.Raw>();var seen=new LinkedHashSet<AssetId>();AssetId current=id;
        while(current!=null) {
            work.check();if(current.path().startsWith("builtin/"))throw fail(ModelPlan.Code.BUILTIN_MODEL,current,"","This model requires a native special/item renderer");
            if(!seen.add(current))throw fail(ModelPlan.Code.PARENT_CYCLE,current,"/parent","Model parent cycle");
            work.limit(chain.size()<work.limits.parentDepth(),current,"/parent","Model parent depth budget reached");
            var raw=ModelDefinitions.read(work,current);chain.add(raw);current=raw.parent().orElse(null);
        }
        var textures=new LinkedHashMap<String,ModelDefinitions.Texture>();List<ModelDefinitions.Element> geometry=List.of();AssetId geometryOwner=id;boolean ao=true;
        for(int i=chain.size()-1;i>=0;i--) {
            work.check();var raw=chain.get(i);
            for(var entry:raw.textures().entrySet()) {
                work.limit(++work.bindings<=work.limits.textureBindings(),raw.id(),"/textures","Inherited texture-binding budget reached");textures.put(entry.getKey(),entry.getValue());
            }
            if(raw.ambientOcclusion().isPresent())ao=raw.ambientOcclusion().get();
            if(raw.elements().isPresent()) {
                if(raw.elements().get().isEmpty() && raw.parent().isPresent())throw fail(ModelPlan.Code.UNSUPPORTED_INHERITANCE,raw.id(),"/elements","Explicit empty child geometry requires native inheritance verification");
                geometry=raw.elements().get();geometryOwner=raw.id();
            }
        }
        var elements=new ArrayList<ModelPlan.Element>();var materials=new HashMap<String,ModelPlan.Material>();
        for(int i=0;i<geometry.size();i++) {
            work.limit(++work.resolvedElements<=work.limits.elements(),id,"/elements/"+i,"Resolved model-element budget reached");var element=geometry.get(i);var faces=new EnumMap<ModelPlan.Direction,ModelPlan.Face>(ModelPlan.Direction.class);
            for(var entry:element.faces().entrySet()) {
                work.limit(++work.resolvedFaces<=work.limits.faces(),id,"/elements/"+i,"Resolved model-face budget reached");var face=entry.getValue();
                var material=materials.get(face.texture());if(material==null){material=material(work,textures,face.texture(),geometryOwner,"/elements/"+i+"/faces/"+entry.getKey().name().toLowerCase(Locale.ROOT)+"/texture");materials.put(face.texture(),material);}
                faces.put(entry.getKey(),new ModelPlan.Face(face.uv(),material,face.cullface(),face.rotation(),face.tintIndex()));
            }
            elements.add(new ModelPlan.Element(new ModelPlan.Source(geometryOwner,"/elements/"+i),element.from(),element.to(),element.rotation(),element.shadeOverride(),element.lightEmission(),faces));
        }
        Optional<ModelPlan.Material> particle=textures.containsKey("particle")?Optional.of(material(work,textures,"#particle",id,"/textures/particle")):Optional.empty();
        var result=new ModelPlan.Model(id,chain.stream().map(ModelDefinitions.Raw::id).toList(),ao,elements,particle);work.models.put(id,result);return result;
    }
    private static ModelPlan.Material material(BlockModels.Work work,Map<String,ModelDefinitions.Texture> textures,String token,AssetId id,String pointer) {
        var seen=new HashSet<String>();ModelPlan.Material material=null;
        while(token.startsWith("#")) {
            work.check();String name=variable(token.substring(1),id,pointer);
            if(!seen.add(name))throw fail(ModelPlan.Code.TEXTURE_CYCLE,id,pointer,"Texture variable cycle");
            work.limit(seen.size()<=work.limits.parentDepth(),id,pointer,"Texture variable depth budget reached");
            var value=textures.get(name);if(value==null)throw fail(ModelPlan.Code.MISSING_TEXTURE_VARIABLE,id,pointer,"Referenced texture variable is missing");
            id=value.source().asset();pointer=value.source().pointer();
            if(value instanceof ModelDefinitions.Link link)token="#"+link.name();
            else { material=((ModelDefinitions.Sprite)value).material();break; }
        }
        if(material==null)material=new ModelPlan.Material(reference(token,id,pointer),false);
        if(!material.sprite().namespace().equals("minecraft"))throw fail(ModelPlan.Code.EXTERNAL_ASSET,id,pointer,"Only captured vanilla sprites are supported: "+material.sprite());
        if(work.snapshot.find(ClientAssetSnapshot.Kind.TEXTURE,material.sprite()).isEmpty())throw fail(ModelPlan.Code.MISSING_ASSET,id,pointer,"Referenced PNG sprite is missing; generated atlas sprites remain unsupported: "+material.sprite());
        return material;
    }
}
