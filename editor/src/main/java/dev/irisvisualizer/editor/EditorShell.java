package dev.irisvisualizer.editor;

import java.util.List;

/** Main desktop shell contract from SPECIFICATION sections 7 and 9; no backend or pack mutation. */
final class EditorShell {
    static final List<String> FILE_ACTIONS=List.of("New","Open","Save","Import","Export");
    static final float MIN_RESOURCES=220,MIN_VISUALIZATION=280,MIN_INSPECTOR=300;
    enum Panel {
        RESOURCES("Resources"),VISUALIZATION("Visualization"),INSPECTOR("Resource inspector");
        final String window;
        Panel(String window){this.window=window;}
        Panel next(boolean reverse){return values()[(ordinal()+(reverse?2:1))%3];}
    }
    enum Divider {RESOURCES,INSPECTOR}
    enum Toolbar {ROW,COMPACT,STACKED}
    record Rect(float x,float y,float width,float height){}
    record Layout(Rect resources,Rect visualization,Rect inspector){}
    /** Preferences live for this editor session only. Resizing never captures workspace data. */
    static final class State {
        private float resourceFraction=Float.NaN,inspectorFraction=Float.NaN;
        Layout layout(float width,float height,float menuHeight){
            validate(width,height,menuHeight);
            float left=Float.isNaN(resourceFraction)?Math.min(320,width*.24f):width*resourceFraction;
            float right=Float.isNaN(inspectorFraction)?Math.min(480,width*.32f):width*inspectorFraction;
            float scale=Math.min(1,width/(MIN_RESOURCES+MIN_VISUALIZATION+MIN_INSPECTOR));
            float minLeft=MIN_RESOURCES*scale,minMiddle=MIN_VISUALIZATION*scale,minRight=MIN_INSPECTOR*scale;
            left=Math.max(minLeft,left);right=Math.max(minRight,right);
            float excess=left+right+minMiddle-width;
            if(excess>0){
                float spareLeft=left-minLeft,spareRight=right-minRight,total=spareLeft+spareRight;
                if(total>0){float remove=Math.min(excess,total);left-=remove*(spareLeft/total);right-=remove*(spareRight/total);}
            }
            left=Math.max(0,Math.min(left,width));right=Math.max(0,Math.min(right,width-left));
            float middle=Math.max(0,width-left-right),top=Math.min(menuHeight,height),body=height-top;
            return new Layout(new Rect(0,top,left,body),new Rect(left,top,middle,body),new Rect(left+middle,top,right,body));
        }
        boolean resizable(float width){return Float.isFinite(width) && width>MIN_RESOURCES+MIN_VISUALIZATION+MIN_INSPECTOR;}
        void move(Divider divider,float delta,float width,float height,float menuHeight){
            if(divider==null || !Float.isFinite(delta))throw new IllegalArgumentException("Invalid panel resize");
            var current=layout(width,height,menuHeight);
            if(!resizable(width) || current.resources().height()==0 || delta==0)return;
            if(divider==Divider.RESOURCES){
                float next=Math.max(MIN_RESOURCES,Math.min(width-current.inspector().width()-MIN_VISUALIZATION,current.resources().width()+delta));
                if(next!=current.resources().width()){resourceFraction=next/width;inspectorFraction=current.inspector().width()/width;}
            }else{
                float next=Math.max(MIN_INSPECTOR,Math.min(width-current.resources().width()-MIN_VISUALIZATION,current.inspector().width()-delta));
                if(next!=current.inspector().width()){inspectorFraction=next/width;resourceFraction=current.resources().width()/width;}
            }
        }
        void reset(){resourceFraction=inspectorFraction=Float.NaN;}
    }
    static Layout layout(float width,float height,float menuHeight){
        return new State().layout(width,height,menuHeight);
    }
    static Toolbar toolbar(float width){
        if(!Float.isFinite(width) || width<0)throw new IllegalArgumentException("Invalid preview toolbar width");
        return width>=680?Toolbar.ROW:width>=360?Toolbar.COMPACT:Toolbar.STACKED;
    }
    private static void validate(float width,float height,float menuHeight){
        if(!Float.isFinite(width) || !Float.isFinite(height) || !Float.isFinite(menuHeight) || width<0 || height<0 || menuHeight<0)throw new IllegalArgumentException("Invalid desktop dimensions");
    }
    private EditorShell(){}
}
