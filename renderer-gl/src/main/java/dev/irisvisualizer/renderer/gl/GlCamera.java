package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.WorldSnapshot;
import dev.irisvisualizer.renderer.WorldDrawOrder;
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
        double fx=targetX-eyeX,fy=targetY-eyeY,fz=targetZ-eyeZ,length=Math.sqrt(fx*fx+fy*fy+fz*fz);
        if(length<1e-8)throw new GlFailure(GlFailure.Code.STATE,"Camera eye and target coincide");fx/=length;fy/=length;fz/=length;
        // Use Z as the reference up near the Y poles, including exact top/bottom views.
        double upY=Math.abs(fy)>0.999999?0:1,upZ=upY==0?1:0;
        double sx=fy*upZ-fz*upY,sy=-fx*upZ,sz=fx*upY,side=Math.sqrt(sx*sx+sy*sy+sz*sz);sx/=side;sy/=side;sz/=side;
        double ux=sy*fz-sz*fy,uy=sz*fx-sx*fz,uz=sx*fy-sy*fx;
        double[] view={sx,ux,-fx,0, sy,uy,-fy,0, sz,uz,-fz,0, -(sx*eyeX+sy*eyeY+sz*eyeZ),-(ux*eyeX+uy*eyeY+uz*eyeZ),fx*eyeX+fy*eyeY+fz*eyeZ,1};
        double f=1/Math.tan(Math.toRadians(fieldOfView)/2),range=near-far;double[] projection={f/aspect,0,0,0, 0,f,0,0, 0,0,(far+near)/range,-1, 0,0,2*far*near/range,0};
        float[] matrix=new float[16];for(int column=0;column<4;column++)for(int row=0;row<4;row++) { double value=0;for(int k=0;k<4;k++)value+=projection[k*4+row]*view[column*4+k];matrix[column*4+row]=(float)value; }
        return matrix;
    }
    void offset(WorldSnapshot.Position origin,float[] offsets,int at) {
        try {
            long x=Math.subtractExact(origin.x(),anchor.x()),z=Math.subtractExact(origin.z(),anchor.z()),y=(long)origin.y()-anchor.y();
            if(Math.abs((double)x)>1048576||Math.abs((double)y)>1048576||Math.abs((double)z)>1048576)throw new GlFailure(GlFailure.Code.LIMIT,"Section is too far from the camera anchor for supported float precision");offsets[at]=x;offsets[at+1]=y;offsets[at+2]=z;
        }catch(ArithmeticException overflow) { throw new GlFailure(GlFailure.Code.LIMIT,"World/camera anchor difference overflows its exact coordinate range"); }
    }
}
