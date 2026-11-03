package dev.irisvisualizer.renderer;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Closed texture/animation metadata profile. Ambiguous native layouts remain explicit failures. */
final class TextureMetadata {
    record Parsed(TextureSprite.Settings settings,int width,int height,boolean animated,boolean interpolate,List<TextureSprite.Frame> frames,int nodes) {}
    static ModelFields.Failure failure(ModelPlan.Code code,ClientAssetSnapshot.Kind kind,AssetId id,String pointer,String reason) {
        return new ModelFields.Failure(new ModelPlan.Problem(code,kind,id,pointer,reason));
    }
    private final AssetId id;private final BooleanSupplier cancelled;
    private TextureMetadata(AssetId id,BooleanSupplier cancelled) { this.id=id;this.cancelled=cancelled; }
    private ModelFields.Failure format(String pointer,String reason) { return failure(pointer.startsWith("/animation")?ModelPlan.Code.ANIMATION:ModelPlan.Code.TEXTURE_FORMAT,ClientAssetSnapshot.Kind.TEXTURE_METADATA,id,pointer,reason); }
    private void check() { if(cancelled.getAsBoolean())throw new CancellationException("Texture metadata reading cancelled"); }
    private Map<String,AssetJson.Value> object(AssetJson.Value value,String pointer,Set<String> known) {
        if(!(value instanceof AssetJson.ObjectValue result))throw format(pointer,"Expected a texture metadata object");
        for(String field:result.fields().keySet())if(!known.contains(field))throw failure(ModelPlan.Code.UNSUPPORTED_FIELD,ClientAssetSnapshot.Kind.TEXTURE_METADATA,id,ModelFields.child(pointer,field),"Texture metadata field has no supported semantics");
        return result.fields();
    }
    private int integer(AssetJson.Value value,int fallback,int minimum,int maximum,String pointer) {
        if(value==null)return fallback;
        if(value instanceof AssetJson.NumberToken number)try { int result=new BigDecimal(number.spelling()).intValueExact();if(result>=minimum&&result<=maximum)return result; }catch(ArithmeticException|NumberFormatException invalid) { /* Report typed metadata failure below. */ }
        throw format(pointer,"Expected an exact texture integer within its supported range");
    }
    private boolean flag(AssetJson.Value value,String pointer) { if(value==null)return false;if(value instanceof AssetJson.Flag flag)return flag.value();throw format(pointer,"Expected a texture boolean"); }
    static Parsed read(AssetId id,byte[] bytes,RgbaImage image,int remainingNodes,int remainingFrames,BooleanSupplier cancelled) {
        var parser=new TextureMetadata(id,cancelled);parser.check();int nodes=0;Map<String,AssetJson.Value> root=Map.of();
        if(bytes!=null) {
            if(remainingNodes<1)throw failure(ModelPlan.Code.LIMIT,ClientAssetSnapshot.Kind.TEXTURE_METADATA,id,"","Texture JSON node budget reached");
            try { var document=AssetJson.document(bytes,new AssetJson.Limits(1024*1024,remainingNodes,32,16384),cancelled);nodes=document.nodes();root=parser.object(document.root(),"",Set.of("texture","animation")); }
            catch(AssetJson.LimitFailure invalid) { throw failure(ModelPlan.Code.LIMIT,ClientAssetSnapshot.Kind.TEXTURE_METADATA,id,"",invalid.getMessage()); }
            catch(IOException invalid) { throw failure(ModelPlan.Code.FORMAT,ClientAssetSnapshot.Kind.TEXTURE_METADATA,id,"",invalid.getMessage()); }
        }
        Map<String,AssetJson.Value> texture=root.containsKey("texture")?parser.object(root.get("texture"),"/texture",Set.of("blur","clamp","mipmap_strategy","alpha_cutoff_bias")):Map.of();
        var strategy=TextureSprite.MipmapStrategy.MEAN;
        if(texture.containsKey("mipmap_strategy")) {
            if(!(texture.get("mipmap_strategy") instanceof AssetJson.Text text))throw parser.format("/texture/mipmap_strategy","Expected a mipmap strategy name");
            strategy=switch(text.value()) { case "mean"->TextureSprite.MipmapStrategy.MEAN;case "cutout"->TextureSprite.MipmapStrategy.CUTOUT;case "strict_cutout"->TextureSprite.MipmapStrategy.STRICT_CUTOUT;case "dark_cutout"->TextureSprite.MipmapStrategy.DARK_CUTOUT;default->throw parser.format("/texture/mipmap_strategy","Unsupported mipmap strategy"); };
        }
        float bias=0;if(texture.containsKey("alpha_cutoff_bias")) {
            if(!(texture.get("alpha_cutoff_bias") instanceof AssetJson.NumberToken number))throw parser.format("/texture/alpha_cutoff_bias","Expected a finite alpha cutoff bias");
            try { bias=Float.parseFloat(number.spelling());if(!Float.isFinite(bias))throw new NumberFormatException(); }
            catch(NumberFormatException invalid) { throw parser.format("/texture/alpha_cutoff_bias","Expected a finite alpha cutoff bias"); }
        }
        var settings=new TextureSprite.Settings(parser.flag(texture.get("blur"),"/texture/blur"),parser.flag(texture.get("clamp"),"/texture/clamp"),strategy,bias);
        int width=image.width(),height=image.height();boolean animated=root.containsKey("animation"),interpolate=false;var frames=new ArrayList<TextureSprite.Frame>();
        if(animated) {
            var animation=parser.object(root.get("animation"),"/animation",Set.of("width","height","frametime","frames","interpolate"));
            boolean hasWidth=animation.containsKey("width"),hasHeight=animation.containsKey("height");
            if(hasWidth!=hasHeight)throw parser.format("/animation","Single-axis animation sizes require pinned-client layout verification");
            if(hasWidth) { width=parser.integer(animation.get("width"),0,1,image.width(),"/animation/width");height=parser.integer(animation.get("height"),0,1,image.height(),"/animation/height"); }
            else {
                if(image.width()>image.height())throw parser.format("/animation","Implicit horizontal animation layout requires pinned-client verification");
                width=height=image.width();
            }
            if(image.width()%width!=0||image.height()%height!=0)throw parser.format("/animation","Sprite sheet is not a whole number of animation tiles");
            int tiles=(image.width()/width)*(image.height()/height),time=parser.integer(animation.get("frametime"),1,1,Integer.MAX_VALUE,"/animation/frametime");
            interpolate=parser.flag(animation.get("interpolate"),"/animation/interpolate");
            if(animation.containsKey("frames")) {
                if(!(animation.get("frames") instanceof AssetJson.ArrayValue array))throw parser.format("/animation/frames","Expected an animation frame list");
                if(array.values().isEmpty())throw parser.format("/animation/frames","Explicit empty animation requires pinned-client verification");
                if(array.values().size()>remainingFrames)throw failure(ModelPlan.Code.LIMIT,ClientAssetSnapshot.Kind.TEXTURE_METADATA,id,"/animation/frames","Animation frame budget reached");
                for(int i=0;i<array.values().size();i++) {
                    parser.check();String pointer="/animation/frames/"+i;var value=array.values().get(i);int index,duration=time;
                    if(value instanceof AssetJson.ObjectValue) {
                        var fields=parser.object(value,pointer,Set.of("index","time"));if(!fields.containsKey("index"))throw parser.format(pointer+"/index","Animation frame index is required");
                        index=parser.integer(fields.get("index"),0,0,tiles-1,pointer+"/index");duration=parser.integer(fields.get("time"),time,1,Integer.MAX_VALUE,pointer+"/time");
                    } else index=parser.integer(value,0,0,tiles-1,pointer);
                    frames.add(new TextureSprite.Frame(index,duration));
                }
            } else {
                if(tiles>remainingFrames)throw failure(ModelPlan.Code.LIMIT,ClientAssetSnapshot.Kind.TEXTURE_METADATA,id,"/animation/frames","Implicit animation frame budget reached");
                for(int i=0;i<tiles;i++) { parser.check();frames.add(new TextureSprite.Frame(i,time)); }
            }
        } else {
            if(remainingFrames<1)throw failure(ModelPlan.Code.LIMIT,ClientAssetSnapshot.Kind.TEXTURE,id,"","Static sprite frame budget reached");frames.add(new TextureSprite.Frame(0,1));
        }
        parser.check();return new Parsed(settings,width,height,animated,interpolate,List.copyOf(frames),nodes);
    }
}
