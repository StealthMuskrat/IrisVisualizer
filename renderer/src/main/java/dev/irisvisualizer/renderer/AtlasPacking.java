package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** First-fit descending-height shelves. No rotations, content rescaling or resolution fallback. */
final class AtlasPacking {
    record Result(List<AtlasLayout.Page> pages,Map<AssetId,AtlasLayout.Sprite> sprites,long bytes,long paddedPixels,long checks) {}
    private static final class Shelf { final int y,height;int used;Shelf(int y,int height) { this.y=y;this.height=height; } }
    private static final class Page { final List<Shelf> shelves=new ArrayList<>();int usedWidth,usedHeight; }
    private static int powerOfTwo(int value) { return value<=1?1:Integer.highestOneBit(value-1)<<1; }
    static Result pack(NavigableMap<AssetId,TextureSprite> sources,AtlasBuilder.Limits limits,BooleanSupplier cancelled) {
        var ordered=new ArrayList<>(sources.values());
        ordered.sort(Comparator.comparingInt(TextureSprite::frameHeight).reversed().thenComparing(Comparator.comparingInt(TextureSprite::frameWidth).reversed()).thenComparing(TextureSprite::id));
        var pages=new ArrayList<Page>();var sprites=new TreeMap<AssetId,AtlasLayout.Sprite>();long checks=0,pixels=0;
        for(var source:ordered) {
            AtlasBuilder.check(cancelled);int width=source.frameWidth()+2*limits.gutter(),height=source.frameHeight()+2*limits.gutter();
            if(width>limits.pageDimension()||height>limits.pageDimension())throw AtlasBuilder.failure(ModelPlan.Code.LIMIT,source.id(),"","Padded sprite exceeds atlas page dimension; resolution was not changed");
            int pageIndex=-1;Shelf selected=null;
            search:for(int p=0;p<pages.size();p++) {
                var page=pages.get(p);
                for(var shelf:page.shelves) {
                    if((checks&255)==0)AtlasBuilder.check(cancelled);if(++checks>limits.packingChecks())throw AtlasBuilder.failure(ModelPlan.Code.LIMIT,source.id(),"","Atlas packing search budget reached");
                    if(height<=shelf.height&&width<=limits.pageDimension()-shelf.used) { pageIndex=p;selected=shelf;break search; }
                }
                if(height<=limits.pageDimension()-page.usedHeight) { pageIndex=p;selected=new Shelf(page.usedHeight,height);page.shelves.add(selected);page.usedHeight+=height;break; }
            }
            if(selected==null) {
                if(pages.size()>=limits.pages())throw AtlasBuilder.failure(ModelPlan.Code.LIMIT,source.id(),"","Atlas page count budget reached; no sprites were omitted");
                pageIndex=pages.size();var page=new Page();selected=new Shelf(0,height);page.shelves.add(selected);page.usedHeight=height;pages.add(page);
            }
            sprites.put(source.id(),new AtlasLayout.Sprite(source,pageIndex,selected.used+limits.gutter(),selected.y+limits.gutter(),limits.gutter()));selected.used+=width;
            pages.get(pageIndex).usedWidth=Math.max(pages.get(pageIndex).usedWidth,selected.used);pixels+=(long)width*height;
        }
        long bytes=0;var immutablePages=new ArrayList<AtlasLayout.Page>();
        for(int i=0;i<pages.size();i++) {
            AtlasBuilder.check(cancelled);var page=pages.get(i);var result=new AtlasLayout.Page(i,powerOfTwo(page.usedWidth),powerOfTwo(page.usedHeight));bytes+=result.bytes();
            if(bytes>limits.atlasBytes())throw AtlasBuilder.failure(ModelPlan.Code.LIMIT,sources.firstKey(),"","Power-of-two atlas page payload budget reached");immutablePages.add(result);
        }
        AtlasBuilder.check(cancelled);return new Result(List.copyOf(immutablePages),sprites,bytes,pixels,checks);
    }
}
