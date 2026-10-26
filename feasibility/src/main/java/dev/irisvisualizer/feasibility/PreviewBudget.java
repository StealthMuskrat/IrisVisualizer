package dev.irisvisualizer.feasibility;

/** Proposed application-owned budgets. These do not bound Iris's internal working set. */
public record PreviewBudget(long generationBytes, long meshBytes) {
    public static final int DEFAULT_AREA = 128;
    public static final int DEFAULT_MAP_SAMPLES = 512;
    public static final PreviewBudget PROPOSED = new PreviewBudget(256L << 20, 256L << 20);

    public PreviewBudget {
        if (generationBytes <= 0 || meshBytes <= 0) throw new IllegalArgumentException("Budgets must be positive");
    }

    public long volumeBytes(int width, int depth, int minY, int maxY) {
        if (width <= 0 || depth <= 0 || maxY <= minY) throw new IllegalArgumentException("Invalid extent");
        // Two palette-index int arrays: block state and biome per voxel, with no Y truncation.
        long cells = Math.multiplyExact(Math.multiplyExact((long) width, depth), Math.subtractExact((long) maxY, minY));
        return Math.multiplyExact(cells, 2L * Integer.BYTES);
    }

    public void requireVolume(int width, int depth, int minY, int maxY) {
        long bytes = volumeBytes(width, depth, minY, maxY);
        if (bytes > generationBytes) throw new IllegalArgumentException(
                "Preview needs " + bytes + " bytes of voxel indices; budget is " + generationBytes
                + ". Explicitly reduce horizontal area. The configured height will not be truncated.");
    }
}
