package dev.irisvisualizer.renderer;

/** Local model-coordinate math. Dispatch quarter turns use the client clockwise convention. */
final class ModelTransforms {
    record V(double x,double y,double z) {
        V add(V other) { return new V(x+other.x,y+other.y,z+other.z); }
        V subtract(V other) { return new V(x-other.x,y-other.y,z-other.z); }
        V scale(double value) { return new V(x*value,y*value,z*value); }
        double dot(V other) { return x*other.x+y*other.y+z*other.z; }
        boolean finite() { return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z); }
    }
    static V vector(ModelPlan.Vec3 value) { return new V(value.x(),value.y(),value.z()); }
    static V normal(ModelPlan.Direction direction) {
        return switch(direction){case DOWN->new V(0,-1,0);case UP->new V(0,1,0);case NORTH->new V(0,0,-1);case SOUTH->new V(0,0,1);case WEST->new V(-1,0,0);case EAST->new V(1,0,0);};
    }
    static V uAxis(ModelPlan.Direction direction) {
        return switch(direction){case DOWN,UP,SOUTH->new V(1,0,0);case NORTH->new V(-1,0,0);case WEST->new V(0,0,1);case EAST->new V(0,0,-1);};
    }
    static V vAxis(ModelPlan.Direction direction) {
        return switch(direction){case DOWN->new V(0,0,-1);case UP->new V(0,0,1);default->new V(0,-1,0);};
    }
    record Quarter(int x,int y,int z) {
        Quarter { if(!ModelPlan.quarter(x)||!ModelPlan.quarter(y)||!ModelPlan.quarter(z))throw new IllegalArgumentException("Invalid dispatch rotation"); }
        V vector(V input) {
            V a=switch(x){case 0->input;case 90->new V(input.x(),input.z(),-input.y());case 180->new V(input.x(),-input.y(),-input.z());default->new V(input.x(),-input.z(),input.y());};
            V b=switch(y){case 0->a;case 90->new V(-a.z(),a.y(),a.x());case 180->new V(-a.x(),a.y(),-a.z());default->new V(a.z(),a.y(),-a.x());};
            return switch(z){case 0->b;case 90->new V(b.y(),-b.x(),b.z());case 180->new V(-b.x(),-b.y(),b.z());default->new V(-b.y(),b.x(),b.z());};
        }
        V position(V input) { return vector(input.subtract(new V(8,8,8))).add(new V(8,8,8)); }
        ModelPlan.Direction direction(ModelPlan.Direction input) {
            V result=vector(normal(input));
            if(result.x()==1)return ModelPlan.Direction.EAST;if(result.x()==-1)return ModelPlan.Direction.WEST;
            if(result.y()==1)return ModelPlan.Direction.UP;if(result.y()==-1)return ModelPlan.Direction.DOWN;
            return result.z()==1?ModelPlan.Direction.SOUTH:ModelPlan.Direction.NORTH;
        }
        UvTransform uvlock(ModelPlan.Direction face) {
            var target=direction(face);var u=vector(uAxis(face));var v=vector(vAxis(face));var targetU=uAxis(target);var targetV=vAxis(target);
            return new UvTransform(u.dot(targetU),v.dot(targetU),u.dot(targetV),v.dot(targetV));
        }
    }
    record UvTransform(double a,double b,double c,double d) {
        UvTransform {
            if(Math.abs(a)+Math.abs(b)!=1 || Math.abs(c)+Math.abs(d)!=1 || a*d-b*c!=1)
                throw new IllegalArgumentException("UV lock is not a proper face quarter turn");
        }
        double u(double u,double v) { return 8+a*(u-8)+b*(v-8); }
        double v(double u,double v) { return 8+c*(u-8)+d*(v-8); }
    }
    static final class Element {
        private final V origin;private final double a,b,c,d,e,f,g,h,i,scaleX,scaleY,scaleZ;private final boolean identity;
        Element(ModelPlan.Rotation rotation) {
            origin=vector(rotation.origin());identity=rotation.x()==0 && rotation.y()==0 && rotation.z()==0;
            double x=StrictMath.toRadians(rotation.x()),y=StrictMath.toRadians(rotation.y()),z=StrictMath.toRadians(rotation.z());
            double sx=StrictMath.sin(x),cx=StrictMath.cos(x),sy=StrictMath.sin(y),cy=StrictMath.cos(y),sz=StrictMath.sin(z),cz=StrictMath.cos(z);
            a=cz*cy;b=cz*sy*sx-sz*cx;c=cz*sy*cx+sz*sx;
            d=sz*cy;e=sz*sy*sx+cz*cx;f=sz*sy*cx-cz*sx;g=-sy;h=cy*sx;i=cy*cx;
            // Each rotated unit axis is expanded until its largest component reaches one.
            scaleX=rotation.rescale()&&!identity?1/Math.max(Math.abs(a),Math.max(Math.abs(d),Math.abs(g))):1;
            scaleY=rotation.rescale()&&!identity?1/Math.max(Math.abs(b),Math.max(Math.abs(e),Math.abs(h))):1;
            scaleZ=rotation.rescale()&&!identity?1/Math.max(Math.abs(c),Math.max(Math.abs(f),Math.abs(i))):1;
        }
        V position(V input) {
            if(identity)return input;V p=input.subtract(origin);
            // input + (R*S-I)(input-origin) preserves small coordinates near a large origin.
            return input.add(new V((a*scaleX-1)*p.x()+b*scaleY*p.y()+c*scaleZ*p.z(),d*scaleX*p.x()+(e*scaleY-1)*p.y()+f*scaleZ*p.z(),g*scaleX*p.x()+h*scaleY*p.y()+(i*scaleZ-1)*p.z()));
        }
        V normal(V input) {
            if(identity)return input;
            // Inverse transpose of R*S is R*inverse(S); the baker normalizes afterward.
            double x=input.x()/scaleX,y=input.y()/scaleY,z=input.z()/scaleZ;
            return new V(a*x+b*y+c*z,d*x+e*y+f*z,g*x+h*y+i*z);
        }
    }
}
