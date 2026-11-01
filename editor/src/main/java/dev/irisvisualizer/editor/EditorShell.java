package dev.irisvisualizer.editor;

import java.util.List;

/** Main desktop shell contract from SPECIFICATION sections 7 and 9; no backend or pack mutation. */
final class EditorShell {
    static final List<String> FILE_ACTIONS=List.of("New","Open","Save","Import","Export");
    record Rect(float x,float y,float width,float height){}
    record Layout(Rect resources,Rect visualization,Rect inspector){}
    static Layout layout(float width,float height,float menuHeight){
        if(!Float.isFinite(width) || !Float.isFinite(height) || !Float.isFinite(menuHeight) || width<0 || height<0 || menuHeight<0)throw new IllegalArgumentException("Invalid desktop dimensions");
        float top=Math.min(menuHeight,height),body=height-top;
        float left=Math.min(320,width*.24f),right=Math.min(480,width*.32f),middle=width-left-right;
        return new Layout(new Rect(0,top,left,body),new Rect(left,top,middle,body),new Rect(left+middle,top,right,body));
    }
    private EditorShell(){}
}
