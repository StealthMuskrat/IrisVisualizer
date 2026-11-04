package dev.irisvisualizer.renderer;

import java.util.List;

/** Source-order model faces, winding-aware normals and pre-rotation UV rectangles. */
final class FaceGeometry {
    static ModelTransforms.V corner(ModelPlan.Element element,ModelPlan.Direction direction,int corner) {
        var a=element.from();var b=element.to();return corner(a.x(),a.y(),a.z(),b.x(),b.y(),b.z(),direction,corner);
    }
    private static ModelTransforms.V corner(double ax,double ay,double az,double bx,double by,double bz,ModelPlan.Direction direction,int corner) {
        if(corner<0 || corner>3)throw new IndexOutOfBoundsException("Quad corner must be 0..3");
        return switch(direction) {
            case DOWN->switch(corner){case 0->new ModelTransforms.V(ax,ay,bz);case 1->new ModelTransforms.V(ax,ay,az);case 2->new ModelTransforms.V(bx,ay,az);default->new ModelTransforms.V(bx,ay,bz);};
            case UP->switch(corner){case 0->new ModelTransforms.V(ax,by,az);case 1->new ModelTransforms.V(ax,by,bz);case 2->new ModelTransforms.V(bx,by,bz);default->new ModelTransforms.V(bx,by,az);};
            case NORTH->switch(corner){case 0->new ModelTransforms.V(bx,by,az);case 1->new ModelTransforms.V(bx,ay,az);case 2->new ModelTransforms.V(ax,ay,az);default->new ModelTransforms.V(ax,by,az);};
            case SOUTH->switch(corner){case 0->new ModelTransforms.V(ax,by,bz);case 1->new ModelTransforms.V(ax,ay,bz);case 2->new ModelTransforms.V(bx,ay,bz);default->new ModelTransforms.V(bx,by,bz);};
            case WEST->switch(corner){case 0->new ModelTransforms.V(ax,by,az);case 1->new ModelTransforms.V(ax,ay,az);case 2->new ModelTransforms.V(ax,ay,bz);default->new ModelTransforms.V(ax,by,bz);};
            case EAST->switch(corner){case 0->new ModelTransforms.V(bx,by,bz);case 1->new ModelTransforms.V(bx,ay,bz);case 2->new ModelTransforms.V(bx,ay,az);default->new ModelTransforms.V(bx,by,az);};
        };
    }
    /** Signed area in the face's original two tangent axes; the normal-axis extent is irrelevant. */
    static double signedArea(ModelPlan.Element element,ModelPlan.Direction direction) {
        var a=element.from();var b=element.to();double x=(double)b.x()-a.x(),y=(double)b.y()-a.y(),z=(double)b.z()-a.z();
        return switch(direction) { case DOWN,UP->x*z;case NORTH,SOUTH->x*y;case WEST,EAST->y*z; };
    }
    static ModelPlan.Direction opposite(ModelPlan.Direction direction) {
        return switch(direction) { case DOWN->ModelPlan.Direction.UP;case UP->ModelPlan.Direction.DOWN;case NORTH->ModelPlan.Direction.SOUTH;case SOUTH->ModelPlan.Direction.NORTH;case WEST->ModelPlan.Direction.EAST;case EAST->ModelPlan.Direction.WEST; };
    }
    /** Uses final float vertices, so coordinate rounding can collapse an otherwise nonzero input. */
    static boolean hasArea(List<BlockMesh.Vertex> vertices) {
        var a=vertices.get(0);var b=vertices.get(1);var c=vertices.get(2);
        double x=(double)b.x()-a.x(),y=(double)b.y()-a.y(),z=(double)b.z()-a.z(),u=(double)c.x()-a.x(),v=(double)c.y()-a.y(),w=(double)c.z()-a.z();
        return y*w-z*v!=0||z*u-x*w!=0||x*v-y*u!=0;
    }
    /** Reorders complete position/UV tuples into an axis-aligned face's canonical corner order. */
    static boolean canonicalize(List<BlockMesh.Vertex> vertices,ModelPlan.Direction direction) {
        if(vertices.size()!=4)throw new IllegalArgumentException("A face must contain four vertices");
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        for(var vertex:vertices) {
            minX=Math.min(minX,vertex.x());minY=Math.min(minY,vertex.y());minZ=Math.min(minZ,vertex.z());
            maxX=Math.max(maxX,vertex.x());maxY=Math.max(maxY,vertex.y());maxZ=Math.max(maxZ,vertex.z());
        }
        for(int at=0;at<4;at++) {
            var target=corner(minX,minY,minZ,maxX,maxY,maxZ,direction,at);int found=at;
            while(found<4) { var value=vertices.get(found);if(value.x()==target.x()&&value.y()==target.y()&&value.z()==target.z())break;found++; }
            if(found==4)return false;
            if(found!=at) { var value=vertices.get(at);vertices.set(at,vertices.get(found));vertices.set(found,value); }
        }
        return true;
    }
    static ModelPlan.Uv uv(ModelPlan.Element element,ModelPlan.Direction direction) {
        var a=element.from();var b=element.to();
        return switch(direction) {
            case DOWN->new ModelPlan.Uv(a.x(),16-b.z(),b.x(),16-a.z());
            case UP->new ModelPlan.Uv(a.x(),a.z(),b.x(),b.z());
            case NORTH->new ModelPlan.Uv(16-b.x(),16-b.y(),16-a.x(),16-a.y());
            case SOUTH->new ModelPlan.Uv(a.x(),16-b.y(),b.x(),16-a.y());
            case WEST->new ModelPlan.Uv(a.z(),16-b.y(),b.z(),16-a.y());
            case EAST->new ModelPlan.Uv(16-b.z(),16-b.y(),16-a.z(),16-a.y());
        };
    }
    static double u(ModelPlan.Uv uv,int vertex,int rotation) { int at=(vertex+rotation/90)%4;return at==0 || at==1?uv.u0():uv.u1(); }
    static double v(ModelPlan.Uv uv,int vertex,int rotation) { int at=(vertex+rotation/90)%4;return at==0 || at==3?uv.v0():uv.v1(); }
}
