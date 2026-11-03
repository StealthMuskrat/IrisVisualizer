package dev.irisvisualizer.renderer;

/** Stable outward-wound model faces and pre-rotation default UV rectangles in 0..16 sprite units. */
final class FaceGeometry {
    static ModelTransforms.V corner(ModelPlan.Element element,ModelPlan.Direction direction,int corner) {
        if(corner<0 || corner>3)throw new IndexOutOfBoundsException("Quad corner must be 0..3");
        var a=element.from();var b=element.to();
        return switch(direction) {
            case DOWN->switch(corner){case 0->new ModelTransforms.V(a.x(),a.y(),b.z());case 1->new ModelTransforms.V(a.x(),a.y(),a.z());case 2->new ModelTransforms.V(b.x(),a.y(),a.z());default->new ModelTransforms.V(b.x(),a.y(),b.z());};
            case UP->switch(corner){case 0->new ModelTransforms.V(a.x(),b.y(),a.z());case 1->new ModelTransforms.V(a.x(),b.y(),b.z());case 2->new ModelTransforms.V(b.x(),b.y(),b.z());default->new ModelTransforms.V(b.x(),b.y(),a.z());};
            case NORTH->switch(corner){case 0->new ModelTransforms.V(b.x(),b.y(),a.z());case 1->new ModelTransforms.V(b.x(),a.y(),a.z());case 2->new ModelTransforms.V(a.x(),a.y(),a.z());default->new ModelTransforms.V(a.x(),b.y(),a.z());};
            case SOUTH->switch(corner){case 0->new ModelTransforms.V(a.x(),b.y(),b.z());case 1->new ModelTransforms.V(a.x(),a.y(),b.z());case 2->new ModelTransforms.V(b.x(),a.y(),b.z());default->new ModelTransforms.V(b.x(),b.y(),b.z());};
            case WEST->switch(corner){case 0->new ModelTransforms.V(a.x(),b.y(),a.z());case 1->new ModelTransforms.V(a.x(),a.y(),a.z());case 2->new ModelTransforms.V(a.x(),a.y(),b.z());default->new ModelTransforms.V(a.x(),b.y(),b.z());};
            case EAST->switch(corner){case 0->new ModelTransforms.V(b.x(),b.y(),b.z());case 1->new ModelTransforms.V(b.x(),a.y(),b.z());case 2->new ModelTransforms.V(b.x(),a.y(),a.z());default->new ModelTransforms.V(b.x(),b.y(),a.z());};
        };
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
