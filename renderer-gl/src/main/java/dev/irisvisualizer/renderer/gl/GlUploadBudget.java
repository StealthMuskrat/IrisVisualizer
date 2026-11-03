package dev.irisvisualizer.renderer.gl;

/** Hard transfer/action/work ceilings and a cooperative time target for one upload step. */
public record GlUploadBudget(int transferBytes,int actions,int workUnits,long nanoseconds) {
    public static final GlUploadBudget DEFAULT=new GlUploadBudget(4*1024*1024,32,65536,4_000_000);
    public static final GlUploadBudget MAXIMUM=new GlUploadBudget(16*1024*1024,1024,262144,50_000_000);
    public GlUploadBudget {
        if(transferBytes<65536||transferBytes>16*1024*1024||transferBytes%4!=0||actions<1||actions>1024||workUnits<1||workUnits>262144||nanoseconds<1||nanoseconds>50_000_000)
            throw new IllegalArgumentException("Upload step limits exceed backend bounds");
    }
}
