package dev.irisvisualizer.editor;

/** UI request geometry from SPECIFICATION section 7. No data allocation or native readiness claim. */
record PreviewFraming(double blocksPerSample,int width,int depth) {
    static final int MAP_SAMPLES=512,DEFAULT_AREA=128;
    static final PreviewFraming DEFAULT=new PreviewFraming(1,DEFAULT_AREA,DEFAULT_AREA);

    PreviewFraming {
        if(!Double.isFinite(blocksPerSample) || blocksPerSample<=0)throw new IllegalArgumentException("Map scale must be a positive finite number.");
        if(width<1 || depth<1)throw new IllegalArgumentException("Preview width and depth must be positive integers.");
    }
    PreviewFraming mapScale(String input){
        String value=input==null?"":input.strip();
        if(value.length()>128 || !LosslessJson.numericToken(value))throw new IllegalArgumentException("Enter a positive decimal map scale.");
        try{return new PreviewFraming(Double.parseDouble(value),width,depth);}
        catch(NumberFormatException invalid){throw new IllegalArgumentException("Enter a positive finite map scale.",invalid);}
    }
    PreviewFraming terrainArea(String width,String depth){return new PreviewFraming(blocksPerSample,positive(width,"Width"),positive(depth,"Depth"));}
    PreviewFraming resetMap(){return new PreviewFraming(DEFAULT.blocksPerSample,width,depth);}
    PreviewFraming resetTerrain(){return new PreviewFraming(blocksPerSample,DEFAULT_AREA,DEFAULT_AREA);}
    private static int positive(String input,String label){
        String value=input==null?"":input.strip();
        if(value.length()>10 || !value.matches("[0-9]+"))throw new IllegalArgumentException(label+" must be a positive 32-bit integer.");
        try{int number=Integer.parseInt(value);if(number<1)throw new NumberFormatException();return number;}
        catch(NumberFormatException invalid){throw new IllegalArgumentException(label+" must be between 1 and 2147483647.",invalid);}
    }
    /** X/Z are the first map sample or terrain block, never a silently clamped center. */
    double mapCoordinate(int origin,int index){
        if(index<0 || index>=MAP_SAMPLES)throw new IllegalArgumentException("Map sample index is outside 0..511.");
        return origin+index*blocksPerSample;
    }
    String mapProblem(int x,int z){
        double endX=mapCoordinate(x,MAP_SAMPLES-1),endZ=mapCoordinate(z,MAP_SAMPLES-1);
        if(!Double.isFinite(endX) || !Double.isFinite(endZ) || endX>Integer.MAX_VALUE || endZ>Integer.MAX_VALUE)return "The requested map extends beyond signed 32-bit world coordinates. Reduce map scale or change X/Z; samples are not omitted or clamped.";
        return null;
    }
    String terrainProblem(int x,int z){
        if((long)x+width-1>Integer.MAX_VALUE || (long)z+depth-1>Integer.MAX_VALUE)return "The requested terrain area extends beyond signed 32-bit world coordinates. Reduce width/depth or change X/Z; the area is not truncated.";
        return null;
    }
}
