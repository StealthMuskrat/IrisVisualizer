package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.WorldSnapshot;
import dev.irisvisualizer.renderer.WorldDrawOrder;
import dev.irisvisualizer.renderer.WorldRay;
import java.util.Objects;

/** Camera coordinates are relative to an exact world anchor, never absolute world floats. */
public record GlCamera(WorldSnapshot.Position anchor,double eyeX,double eyeY,double eyeZ,double targetX,double targetY,double targetZ,double fieldOfView,double near,double far) {
    public GlCamera {
        Objects.requireNonNull(anchor);double[] values={eyeX,eyeY,eyeZ,targetX,targetY,targetZ,fieldOfView,near,far};for(double value:values)if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite camera parameter");
        if(fieldOfView<10||fieldOfView>150||near<0.001||far<=near||far>1048576||Math.max(Math.max(Math.abs(eyeX),Math.abs(eyeY)),Math.abs(eyeZ))>1048576||Math.max(Math.max(Math.abs(targetX),Math.abs(targetY)),Math.abs(targetZ))>1048576)throw new IllegalArgumentException("Camera exceeds supported precision or clipping bounds");
    }
    public WorldDrawOrder.View drawView() { return new WorldDrawOrder.View(anchor,eyeX,eyeY,eyeZ,targetX,targetY,targetZ); }
    float[] matrix(double aspect) {
        if(!Double.isFinite(aspect)||aspect<=0)throw new IllegalArgumentException("Invalid camera aspect");
        var basis=basis();double fx=basis[0],fy=basis[1],fz=basis[2],sx=basis[3],sy=basis[4],sz=basis[5],ux=basis[6],uy=basis[7],uz=basis[8];
        double[] view={sx,ux,-fx,0, sy,uy,-fy,0, sz,uz,-fz,0, -(sx*eyeX+sy*eyeY+sz*eyeZ),-(ux*eyeX+uy*eyeY+uz*eyeZ),fx*eyeX+fy*eyeY+fz*eyeZ,1};
        double f=1/Math.tan(Math.toRadians(fieldOfView)/2),range=near-far;double[] projection={f/aspect,0,0,0, 0,f,0,0, 0,0,(far+near)/range,-1, 0,0,2*far*near/range,0};
        float[] matrix=new float[16];for(int column=0;column<4;column++)for(int row=0;row<4;row++) { double value=0;for(int k=0;k<4;k++)value+=projection[k*4+row]*view[column*4+k];matrix[column*4+row]=(float)value; }
        return matrix;
    }
    /** Forward, right, up; shared by rendering, pointer rays and display panning. */
    double[] basis() {
        double fx=targetX-eyeX,fy=targetY-eyeY,fz=targetZ-eyeZ,length=Math.hypot(Math.hypot(fx,fy),fz);
        if(length<1e-8)throw new GlFailure(GlFailure.Code.STATE,"Camera eye and target coincide");fx/=length;fy/=length;fz/=length;
        double upY=Math.abs(fy)>0.999999?0:1,upZ=upY==0?1:0,sx=fy*upZ-fz*upY,sy=-fx*upZ,sz=fx*upY,side=Math.hypot(Math.hypot(sx,sy),sz);sx/=side;sy/=side;sz/=side;
        return new double[]{fx,fy,fz,sx,sy,sz,sy*fz-sz*fy,sz*fx-sx*fz,sx*fy-sy*fx};
    }
    /** Continuous top-left viewport coordinates; use x+0.5/y+0.5 when querying an integer pixel center. */
    public WorldRay ray(double x,double y,int width,int height) {
        if(width<1||width>4096||height<1||height>4096||!Double.isFinite(x)||!Double.isFinite(y)||x<0||x>=width||y<0||y>=height)throw new IllegalArgumentException("Pointer lies outside supported viewport dimensions");
        var basis=basis();double tangent=Math.tan(Math.toRadians(fieldOfView)/2),sx=(2*x/width-1)*tangent*width/height,sy=(1-2*y/height)*tangent;
        double dx=basis[0]+basis[3]*sx+basis[6]*sy,dy=basis[1]+basis[4]*sx+basis[7]*sy,dz=basis[2]+basis[5]*sx+basis[8]*sy,length=Math.hypot(Math.hypot(dx,dy),dz),forward=(dx*basis[0]+dy*basis[1]+dz*basis[2])/length;
        return new WorldRay(anchor,eyeX,eyeY,eyeZ,dx,dy,dz,near/forward,far/forward);
    }
    void offset(WorldSnapshot.Position origin,float[] offsets,int at) {
        try {
            long x=Math.subtractExact(origin.x(),anchor.x()),z=Math.subtractExact(origin.z(),anchor.z()),y=(long)origin.y()-anchor.y();
            if(Math.abs((double)x)>1048576||Math.abs((double)y)>1048576||Math.abs((double)z)>1048576)throw new GlFailure(GlFailure.Code.LIMIT,"Section is too far from the camera anchor for supported float precision");offsets[at]=x;offsets[at+1]=y;offsets[at+2]=z;
        }catch(ArithmeticException overflow) { throw new GlFailure(GlFailure.Code.LIMIT,"World/camera anchor difference overflows its exact coordinate range"); }
    }
}
