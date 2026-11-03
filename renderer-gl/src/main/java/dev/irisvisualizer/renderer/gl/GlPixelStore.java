package dev.irisvisualizer.renderer.gl;

import static org.lwjgl.opengl.GL33C.*;

/** Complete core pixel-store group; transfer helpers never inherit a caller's row layout. */
final class GlPixelStore {
    private static final int[] PACK={GL_PACK_ALIGNMENT,GL_PACK_ROW_LENGTH,GL_PACK_SKIP_ROWS,GL_PACK_SKIP_PIXELS,GL_PACK_IMAGE_HEIGHT,GL_PACK_SKIP_IMAGES,GL_PACK_SWAP_BYTES,GL_PACK_LSB_FIRST};
    private static final int[] UNPACK={GL_UNPACK_ALIGNMENT,GL_UNPACK_ROW_LENGTH,GL_UNPACK_SKIP_ROWS,GL_UNPACK_SKIP_PIXELS,GL_UNPACK_IMAGE_HEIGHT,GL_UNPACK_SKIP_IMAGES,GL_UNPACK_SWAP_BYTES,GL_UNPACK_LSB_FIRST};
    private final int[] parameters,values;
    private GlPixelStore(int[] parameters) {
        this.parameters=parameters;values=new int[parameters.length];for(int i=0;i<parameters.length;i++)values[i]=glGetInteger(parameters[i]);
    }
    static GlPixelStore pack() { return new GlPixelStore(PACK); }
    static GlPixelStore unpack() { return new GlPixelStore(UNPACK); }
    static void tightlyUnpacked() { tightlyPacked(UNPACK); }
    private static void tightlyPacked(int[] parameters) { for(int i=0;i<parameters.length;i++)glPixelStorei(parameters[i],i==0?1:0); }
    void tightlyPacked() { tightlyPacked(parameters); }
    void restore() { for(int i=0;i<parameters.length;i++)glPixelStorei(parameters[i],values[i]); }
}
