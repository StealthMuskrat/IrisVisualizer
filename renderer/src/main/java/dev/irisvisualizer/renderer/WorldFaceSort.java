package dev.irisvisualizer.renderer;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Bounded pair heapsort in the final depth/key order; no face-sized scratch or recursion. */
final class WorldFaceSort {
    private final long[] keys;private final double[] depths;private final BooleanSupplier cancelled;
    private long comparisons,moves;
    private WorldFaceSort(long[] keys,double[] depths,BooleanSupplier cancelled) { this.keys=keys;this.depths=depths;this.cancelled=cancelled; }
    static WorldDrawOrder.SortStats sort(long[] keys,double[] depths,BooleanSupplier cancelled) {
        Objects.requireNonNull(keys);Objects.requireNonNull(depths);Objects.requireNonNull(cancelled);
        if(keys.length!=depths.length)throw new IllegalArgumentException("Face sort pairs have different lengths");
        WorldProblem.check(cancelled);var work=new WorldFaceSort(keys,depths,cancelled);boolean ordered=true;
        for(int at=0;at<keys.length;at++) {
            if((at&1023)==0)WorldProblem.check(cancelled);
            if(!Double.isFinite(depths[at]))throw new IllegalArgumentException("Face sort requires finite depths");
            if(at>0&&work.after(keys[at-1],depths[at-1],keys[at],depths[at]))ordered=false;
        }
        if(!ordered) {
            // The root is the pair that belongs latest, so extraction fills the array from its end.
            for(int root=keys.length/2-1;root>=0;root--)work.sift(root,keys.length);
            for(int end=keys.length-1;end>0;end--) {
                if((end&1023)==0)WorldProblem.check(cancelled);
                long key=keys[end];double depth=depths[end];
                keys[end]=keys[0];depths[end]=depths[0];keys[0]=key;depths[0]=depth;work.moves+=2;
                work.sift(0,end);
            }
        }
        WorldProblem.check(cancelled);return new WorldDrawOrder.SortStats(keys.length,work.comparisons,work.moves,ordered);
    }
    /** Signed zero ties exactly as in the original depth expression/comparison; keys resolve every face tie. */
    private boolean after(long a,double da,long b,double db) {
        if((comparisons++&1023)==0)WorldProblem.check(cancelled);
        return da<db||da==db&&a>b;
    }
    private void sift(int root,int size) {
        long key=keys[root];double depth=depths[root];int original=root;
        while(root<size/2) {
            int child=root*2+1;
            if(child+1<size&&after(keys[child+1],depths[child+1],keys[child],depths[child]))child++;
            if(!after(keys[child],depths[child],key,depth))break;
            keys[root]=keys[child];depths[root]=depths[child];moves++;root=child;
        }
        if(root!=original) { keys[root]=key;depths[root]=depth;moves++; }
    }
}
