package dev.irisvisualizer.editor;

import java.util.List;

/** Deferred display preference and geometry contracts. Does not create a window or ImGui context. */
final class EditorDisplayScaleTests {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void close(float actual,float expected,String message){check(Math.abs(actual-expected)<.06f,message+": "+actual+" / "+expected);}
    private static void rejects(Runnable action){try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected invalid display geometry");}
    private static void rect(EditorShell.Rect actual,EditorShell.Rect logical,float scale){close(actual.x(),logical.x()*scale,"scaled X");close(actual.y(),logical.y()*scale,"scaled menu offset");close(actual.width(),logical.width()*scale,"scaled width");close(actual.height(),logical.height()*scale,"scaled height");}
    static void run(){
        var scale=new EditorDisplayScale();check(scale.choice()==EditorDisplayScale.Choice.SYSTEM && scale.factor()==1,"system scale is the session default");
        for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,0,-1,.49f,8.1f})check(!scale.update(invalid) && scale.factor()==1 && scale.monitor()==1,"invalid monitor samples retain the last usable scale");
        check(scale.update(1.5f) && scale.factor()==1.5f,"Windows content scale applies directly without a framebuffer multiplier");check(!scale.update(1.500001f),"sub-DPI monitor jitter cannot continually change fonts/style");
        scale.choose(EditorDisplayScale.Choice.P200);check(scale.factor()==1.5f,"menu choice does not change geometry partway through the current frame");check(scale.update(1.75f) && scale.factor()==2 && scale.monitor()==1.75f,"manual scale applies between frames while monitor metadata remains independent");
        check(!scale.update(2.5f) && scale.factor()==2 && scale.monitor()==2.5f,"monitor changes cannot override an explicit session choice");scale.choose(EditorDisplayScale.Choice.SYSTEM);check(scale.factor()==2 && scale.update(Float.NaN) && scale.factor()==2.5f,"returning to system uses the last valid monitor sample, even if a later sample is invalid");
        check(scale.update(.5f) && scale.factor()==.5f && scale.update(8) && scale.factor()==8,"finite supported monitor boundaries are retained");check(!scale.update(9) && scale.factor()==8,"invalid monitor samples do not clamp or corrupt a valid preference");
        for(var choice:EditorDisplayScale.Choice.values()){
            scale.choose(choice);scale.update(1.25f);close(scale.factor(),choice==EditorDisplayScale.Choice.SYSTEM?1.25f:choice.factor,"source choice controls the applied factor exactly");
        }
        check(new EditorDisplayScale().factor()==1 && new EditorDisplayScale().choice()==EditorDisplayScale.Choice.SYSTEM,"another editor session starts with independent preferences");
        for(float factor:new float[]{.5f,1,1.25f,1.5f,1.75f,2,3,8}){
            for(float width:new float[]{0,1,320,799,800,960,1440,3840})for(float height:new float[]{0,12,720})for(float header:new float[]{0,24,40}){
                var state=new EditorShell.State();var logical=state.layout(width,height,header);var physical=state.layout(width*factor,height*factor,header*factor,factor);
                rect(physical.resources(),logical.resources(),factor);rect(physical.visualization(),logical.visualization(),factor);rect(physical.inspector(),logical.inspector(),factor);
                close(physical.resources().width()+physical.visualization().width()+physical.inspector().width(),width*factor,"scaled shell fills the viewport");
                for(var pane:List.of(physical.resources(),physical.visualization(),physical.inspector()))check(pane.width()>=0 && pane.height()>=0 && Float.isFinite(pane.width()) && Float.isFinite(pane.height()),"scaled minimized/narrow panes remain finite and nonnegative");
                check(state.resizable(width*factor,factor)==state.resizable(width),"divider availability uses the same logical minimum at every scale");
            }
            for(float width:new float[]{0,359,360,679,680,1400})check(EditorShell.toolbar(width*factor,factor)==EditorShell.toolbar(width),"preview breakpoints scale with the fonts and fixed toolbar columns");
            var state=new EditorShell.State();var before=state.layout(1440,900,24);state.move(EditorShell.Divider.RESOURCES,80*factor,1440*factor,900*factor,24*factor,factor);var widened=state.layout(1440*factor,900*factor,24*factor,factor);
            close(widened.resources().width(),(before.resources().width()+80)*factor,"divider mouse delta is converted exactly once");close(widened.inspector().width(),before.inspector().width()*factor,"scaled first divider leaves the inspector alone");
            state.move(EditorShell.Divider.INSPECTOR,32*factor,1440*factor,900*factor,24*factor,factor);var adjusted=state.layout(1440,900,24);close(adjusted.inspector().width(),before.inspector().width()-32,"keyboard width step remains consistent after scaling");
            var retained=state.layout(1440,900,24);state.layout(1440,900,48,2);state.move(EditorShell.Divider.RESOURCES,100,1440,900,48,2);check(state.layout(1440,900,24).equals(retained),"temporary scale compression never overwrites panel preferences");
            state.reset();check(state.layout(1440*factor,900*factor,24*factor,factor).equals(new EditorShell.State().layout(1440*factor,900*factor,24*factor,factor)),"width reset remains independent of display preferences");
        }
        for(float invalid:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY,.49f,8.1f}){
            rejects(()->new EditorShell.State().layout(1440,900,24,invalid));rejects(()->new EditorShell.State().resizable(1440,invalid));rejects(()->new EditorShell.State().move(EditorShell.Divider.RESOURCES,1,1440,900,24,invalid));rejects(()->EditorShell.toolbar(680,invalid));
        }
        System.out.println("PASS: session-only system/manual display scale, invalid-sample and DPI-jitter handling, between-frame choices, DPI-scaled contiguous shell/minima/dividers/toolbars and retained panel proportions across scale compression");
    }
}
