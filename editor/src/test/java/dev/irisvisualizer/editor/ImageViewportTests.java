package dev.irisvisualizer.editor;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;

final class ImageViewportTests {
    private static void check(boolean ok) { if (!ok) throw new AssertionError("Read-only image viewport contract failed"); }
    private static void near(double a,double b) { check(Math.abs(a-b)<1e-8); }
    private static void rejects(Runnable op) { try { op.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected invalid viewport/decoded image input"); }
    private static void outside(Runnable op) { try { op.run(); } catch (IndexOutOfBoundsException expected) { return; }throw new AssertionError("Expected out-of-image pixel rejection"); }
    private static void pixel(ImageViewport view,double x,double y,int expectedX,int expectedY) { check(new ImageViewport.Pixel(expectedX,expectedY).equals(view.pixelAt(x,y))); }
    static void run(Path temp) throws Exception {
        var view=new ImageViewport(2000,1000);view.resize(1000,800);var fit=view.geometry();
        near(fit.scale(),.5);near(fit.left(),0);near(fit.top(),150);near(fit.right(),1000);near(fit.bottom(),650);near(fit.u0(),0);near(fit.v0(),0);near(fit.u1(),1);near(fit.v1(),1);check(view.fitting());
        pixel(view,0,150,0,0);pixel(view,999.999,649.999,1999,999);check(view.pixelAt(1000,500)==null && view.pixelAt(10,650)==null && view.pixelAt(10,149)==null && view.pixelAt(-1,500)==null);
        view.actualSize();var actual=view.geometry();near(actual.scale(),1);near(actual.u0(),.25);near(actual.u1(),.75);near(actual.v0(),.1);near(actual.v1(),.9);near(actual.width(),1000);near(actual.height(),800);pixel(view,0,0,500,100);pixel(view,500,400,1000,500);check(!view.fitting());
        view.pan(100,50);pixel(view,500,400,900,450);near(view.geometry().u0(),.2);near(view.geometry().v0(),.05);
        view.pan(Double.MAX_VALUE,Double.MAX_VALUE);near(view.centerX(),500);near(view.centerY(),400);near(view.geometry().u0(),0);near(view.geometry().v0(),0);
        view.pan(-Double.MAX_VALUE,-Double.MAX_VALUE);near(view.centerX(),1500);near(view.centerY(),600);near(view.geometry().u1(),1);near(view.geometry().v1(),1);
        view.resize(3000,1500);near(view.centerX(),1000);near(view.centerY(),500);near(view.geometry().left(),500);near(view.geometry().top(),250);view.pan(1000,-1000);near(view.centerX(),1000);near(view.centerY(),500);view.fit();near(view.scale(),1);check(view.fitting());
        // Zoom preserves the source point under the anchor where edge clamping is unnecessary.
        var anchored=new ImageViewport(4096,1024);anchored.resize(800,200);anchored.actualSize();pixel(anchored,600,150,2248,562);anchored.zoom(2,600,150);pixel(anchored,600,150,2248,562);near(anchored.centerX(),2148);near(anchored.centerY(),537);anchored.zoom(.5,600,150);near(anchored.centerX(),2048);near(anchored.centerY(),512);near(anchored.scale(),1);
        anchored.zoom(Double.MAX_VALUE);near(anchored.scale(),ImageViewport.MAX_SCALE);anchored.zoom(Double.MIN_VALUE);near(anchored.scale(),ImageViewport.MIN_SCALE);check(Double.isFinite(anchored.centerX()) && Double.isFinite(anchored.centerY()));anchored.fit();near(anchored.scale(),800.0/4096);anchored.resize(1,1);near(anchored.scale(),ImageViewport.MIN_SCALE);
        var portrait=new ImageViewport(512,4096);portrait.resize(200,400);near(portrait.geometry().left(),75);near(portrait.geometry().width(),50);near(portrait.geometry().height(),400);portrait.actualSize();portrait.zoom(.1);portrait.pan(1_000_000,1_000_000);near(portrait.centerX(),256);near(portrait.geometry().v0(),0);check(portrait.geometry().left()>0 && portrait.geometry().height()==400);portrait.fit();portrait.resize(400,200);near(portrait.geometry().width(),25);near(portrait.geometry().left(),187.5);
        var tiny=new ImageViewport(2,1);tiny.resize(100,100);near(tiny.scale(),1);near(tiny.geometry().left(),49);near(tiny.geometry().top(),49.5);pixel(tiny,49,49.5,0,0);pixel(tiny,50.75,50,1,0);check(tiny.pixelAt(48.9,50)==null);tiny.zoom(32);near(tiny.geometry().left(),18);near(tiny.geometry().top(),34);pixel(tiny,20,50,0,0);pixel(tiny,81,50,1,0);tiny.fit();near(tiny.scale(),1);
        // Reject invalid inputs before mutating the view; large finite drags/zoom factors clamp safely.
        var stable=tiny.geometry();rejects(() -> tiny.resize(0,1));rejects(() -> tiny.resize(Double.NaN,1));rejects(() -> tiny.resize(1,Double.POSITIVE_INFINITY));rejects(() -> tiny.resize(ImageViewport.MAX_CANVAS+1,1));rejects(() -> tiny.zoom(0));rejects(() -> tiny.zoom(-1));rejects(() -> tiny.zoom(Double.NaN));rejects(() -> tiny.zoom(Double.POSITIVE_INFINITY));rejects(() -> tiny.zoom(1,-1,0));rejects(() -> tiny.zoom(1,0,101));rejects(() -> tiny.pan(Double.NaN,1));rejects(() -> tiny.pixelAt(0,Double.NaN));check(tiny.geometry().equals(stable));rejects(() -> new ImageViewport(0,1));rejects(() -> new ImageViewport(4097,1));rejects(() -> new ImageViewport(4096,4096));
        // Repeated resizing/zooming/dragging keeps visible rectangles/UVs finite, clipped and source pixels in range.
        var random=new Random(0x494d414745L);var repeated=new ImageViewport(4096,1024);
        for (int i=0;i<1000;i++) {
            double width=1+random.nextInt(1600),height=1+random.nextInt(1000);repeated.resize(width,height);
            if (i%17==0) repeated.fit();else if (i%23==0) repeated.actualSize();else repeated.zoom(Math.pow(1.25,random.nextInt(17)-8),random.nextDouble()*width,random.nextDouble()*height);
            repeated.pan(random.nextDouble()*2000-1000,random.nextDouble()*2000-1000);var g=repeated.geometry();
            check(Double.isFinite(g.left()) && Double.isFinite(g.top()) && Double.isFinite(g.right()) && Double.isFinite(g.bottom()) && Double.isFinite(g.scale()) && g.left()>=0 && g.top()>=0 && g.right()<=width && g.bottom()<=height && g.width()>0 && g.height()>0 && g.u0()>=0 && g.u1()<=1 && g.u1()>g.u0() && g.v0()>=0 && g.v1()<=1 && g.v1()>g.v0());var p=repeated.pixelAt((g.left()+g.right())/2,(g.top()+g.bottom())/2);check(p!=null && p.x()>=0 && p.x()<4096 && p.y()>=0 && p.y()<1024);
        }
        // Decoded channel inspection is immutable, bounded and independent of checkerboard/blending/display transforms.
        byte[] channels={64,32,16,-128,0,-1,0,-1};var decoded=new AssetImage.Decoded(2,1,channels);channels[0]=0;check(decoded.argbAt(0,0)==0x80402010 && decoded.argbAt(1,0)==0xff00ff00);var copy=decoded.rgba();copy[0]=0;check(decoded.argbAt(0,0)==0x80402010);outside(() -> decoded.argbAt(-1,0));outside(() -> decoded.argbAt(2,0));outside(() -> decoded.argbAt(0,1));rejects(() -> new AssetImage.Decoded(0,1,new byte[0]));rejects(() -> new AssetImage.Decoded(2,1,new byte[7]));rejects(() -> new AssetImage.Decoded(2,1,null));
        var raster=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_ARGB);raster.setRGB(0,0,0x80402010);raster.setRGB(1,0,0xff00ff00);raster.setRGB(0,1,0);raster.setRGB(1,1,0xff0000ff);var output=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(raster,"png",output);byte[] encoded=output.toByteArray();var png=AssetImage.decode(encoded);var pointer=new ImageViewport(2,2);pointer.resize(8,8);pointer.zoom(4);var top=pointer.pixelAt(2,2);var bottom=pointer.pixelAt(6,6);check(png.argbAt(top.x(),top.y())==0x80402010 && png.argbAt(bottom.x(),bottom.y())==0xff0000ff && png.argbAt(0,1)==0);
        var pack=PackWorkspace.newPack(Map.of("images/source.png",encoded,"objects/opaque.iob",new byte[]{0,-1,3},"future/unknown.json",new byte[]{'{'}));Path root=temp.resolve("image-viewport-source");pack.save(root);long revision=pack.revision(),resources=pack.resourceRevision();var drafts=new InspectorDrafts();drafts.update("late","stored","draft");pointer.pan(2,-2);pointer.zoom(2,4,4);pointer.fit();check(pack.revision()==revision && pack.resourceRevision()==resources && !pack.dirty() && pack.undoCount()==0 && drafts.any() && Arrays.equals(pack.read("images/source.png"),encoded));pack.exportZip(temp.resolve("image-viewport.zip"));var imported=PackWorkspace.importZip(temp.resolve("image-viewport.zip"));for (String path : pack.paths()) check(Arrays.equals(pack.read(path),imported.read(path)));check(AssetImage.decode(imported.read("images/source.png")).argbAt(1,1)==0xff0000ff);
        System.out.println("PASS: bounded read-only image fit/1:1/anchor zoom/pan, clipped UV geometry, pixel/letterbox/edge mapping, resize and extreme-input guards, repeated transform invariants, immutable decoded RGBA inspection and exact image/unknown/opaque Save/ZIP without native preview generation");
    }
}
