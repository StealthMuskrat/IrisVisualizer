package dev.irisvisualizer.editor;

import java.util.List;

/** Deferred end-of-UI geometry and keyboard-navigation contracts; no native window is launched. */
final class EditorShellLayoutTests {
    private static void check(boolean value,String detail){if(!value)throw new AssertionError(detail);}
    private static void close(float actual,float expected,String detail){check(Math.abs(actual-expected)<.03f,detail+": "+actual+" / "+expected);}
    private static void rejects(Runnable action){try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected invalid layout input");}
    private static void geometry(EditorShell.Layout layout,float width,float height,float header){
        var left=layout.resources();var middle=layout.visualization();var right=layout.inspector();
        close(left.x(),0,"left edge");close(middle.x(),left.width(),"first adjacency");close(right.x(),middle.x()+middle.width(),"second adjacency");close(right.x()+right.width(),width,"right edge");
        for(var pane:List.of(left,middle,right)){
            check(Float.isFinite(pane.width()) && Float.isFinite(pane.height()) && pane.width()>=0 && pane.height()>=0,"finite nonnegative pane");
            close(pane.y(),Math.min(header,height),"shared actual menu offset");close(pane.y()+pane.height(),height,"shared bottom");
        }
        if(width>=800){check(left.width()>=EditorShell.MIN_RESOURCES-.03 && middle.width()>=EditorShell.MIN_VISUALIZATION-.03 && right.width()>=EditorShell.MIN_INSPECTOR-.03,"each normal-size pane retains its usable minimum");}
    }
    static void run(){
        var state=new EditorShell.State();var original=state.layout(1440,900,24);
        state.move(EditorShell.Divider.RESOURCES,80,1440,900,24);var wider=state.layout(1440,900,24);
        close(wider.resources().width(),original.resources().width()+80,"drag first boundary grows resources");close(wider.inspector().width(),original.inspector().width(),"first boundary leaves editor width alone");
        state.move(EditorShell.Divider.INSPECTOR,40,1440,900,24);var shifted=state.layout(1440,900,24);
        close(shifted.inspector().width(),wider.inspector().width()-40,"drag second boundary right shrinks editor");close(shifted.resources().width(),wider.resources().width(),"second boundary leaves resources alone");
        var preference=state.layout(1440,900,24);
        for(float width:new float[]{0,1,320,640,799,800}){
            geometry(state.layout(width,600,24),width,600,24);
            state.move(EditorShell.Divider.RESOURCES,1000,width,600,24);state.move(EditorShell.Divider.INSPECTOR,-1000,width,600,24);
        }
        check(state.layout(1440,900,24).equals(preference),"temporary narrow/minimized layouts do not overwrite width preferences");
        state.move(EditorShell.Divider.RESOURCES,100,1440,24,24);check(state.layout(1440,900,24).equals(preference),"zero body cannot resize panels");
        var doubled=state.layout(2880,900,24);close(doubled.resources().width(),preference.resources().width()*2,"custom resource proportion survives widening");close(doubled.inspector().width(),preference.inspector().width()*2,"custom editor proportion survives widening");
        var constrained=new EditorShell.State();constrained.move(EditorShell.Divider.RESOURCES,500,1920,900,24);constrained.move(EditorShell.Divider.INSPECTOR,-300,1920,900,24);
        var beforeCompression=constrained.layout(1920,900,24);var compressed=constrained.layout(960,900,24);
        constrained.move(EditorShell.Divider.RESOURCES,0,960,900,24);check(constrained.layout(1920,900,24).equals(beforeCompression),"stationary drag frames do not replace preferred proportions");
        constrained.move(EditorShell.Divider.RESOURCES,-12,960,900,24);var edited=constrained.layout(960,900,24);
        close(edited.inspector().width(),compressed.inspector().width(),"drag after a constrained window resize cannot expand the untouched pane");
        constrained.move(EditorShell.Divider.INSPECTOR,12,960,900,24);close(constrained.layout(960,900,24).resources().width(),edited.resources().width(),"the opposite divider also keeps its untouched pane stable after compression");
        for(float width:new float[]{801,960,1280,1920,3840})for(var divider:EditorShell.Divider.values())for(float delta:new float[]{-Float.MAX_VALUE,-200,0,200,Float.MAX_VALUE}){
            state.move(divider,delta,width,720,31);geometry(state.layout(width,720,31),width,720,31);
        }
        state.reset();check(state.layout(1440,900,24).equals(EditorShell.layout(1440,900,24)),"reset restores exact default layout");
        for(float width:new float[]{0,1,800,1440,3840})for(float height:new float[]{0,12,600})for(float header:new float[]{0,24,40})geometry(state.layout(width,height,header),width,height,header);
        check(!state.resizable(800) && state.resizable(801),"resize availability respects the combined minimum");
        rejects(()->state.move(null,1,1440,900,24));rejects(()->state.move(EditorShell.Divider.RESOURCES,Float.NaN,1440,900,24));rejects(()->state.move(EditorShell.Divider.RESOURCES,1,-1,900,24));rejects(()->state.layout(1,2,Float.POSITIVE_INFINITY));
        check(EditorShell.toolbar(359.9f)==EditorShell.Toolbar.STACKED && EditorShell.toolbar(360)==EditorShell.Toolbar.COMPACT && EditorShell.toolbar(679.9f)==EditorShell.Toolbar.COMPACT && EditorShell.toolbar(680)==EditorShell.Toolbar.ROW,"toolbar breakpoints retain all controls as width changes");
        rejects(()->EditorShell.toolbar(-1));rejects(()->EditorShell.toolbar(Float.NaN));
        for(var panel:EditorShell.Panel.values()){
            check(panel.next(false).next(true)==panel && panel.next(true).next(false)==panel,"F6 and Shift+F6 are inverse focus routes");
            check(panel.next(false).next(false).next(false)==panel,"focus wraps through all three panes");
        }
        System.out.println("PASS: resizable contiguous shell geometry, independent constrained dividers, retained proportions across narrowing/minimizing, default reset, actual menu offsets, responsive toolbar breakpoints and reversible three-panel keyboard focus routes");
    }
}
