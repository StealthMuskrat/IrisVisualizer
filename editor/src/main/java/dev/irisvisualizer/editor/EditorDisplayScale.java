package dev.irisvisualizer.editor;

import java.util.Objects;

/** UI-session display preferences only. Native style changes are applied between frames. */
final class EditorDisplayScale {
    enum Choice {
        SYSTEM("System display scale",0),P100("100%",1),P125("125%",1.25f),P150("150%",1.5f),P175("175%",1.75f),P200("200%",2);
        final String label;final float factor;
        Choice(String label,float factor){this.label=label;this.factor=factor;}
    }
    private Choice choice=Choice.SYSTEM;
    private float monitor=1,applied=1;
    Choice choice(){return choice;}
    void choose(Choice next){choice=Objects.requireNonNull(next);}
    float factor(){return applied;}
    float monitor(){return monitor;}
    /** GLFW reports a DPI multiplier, not the framebuffer/window ratio. Reject invalid samples. */
    boolean update(float sample){
        if(Float.isFinite(sample) && sample>=.5f && sample<=8)monitor=Math.round(sample*96)/96f;
        float next=choice==Choice.SYSTEM?monitor:choice.factor;
        if(next==applied)return false;applied=next;return true;
    }
    static void validate(float scale){if(!Float.isFinite(scale) || scale<.5f || scale>8)throw new IllegalArgumentException("Invalid UI display scale.");}
    EditorDisplayScale(){}
}
