package dev.irisvisualizer.editor;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Detached selected-source preparation for the complete read-only JSON browser stage. */
final class JsonSourceReview {
    static final class Source {
        final String path;final int bytes;final boolean present;final SourceMatch.Location location;private final byte[] data;
        Source(String path,byte[] owned,SourceMatch.Location location){
            this.path=path;this.location=location;present=owned!=null;bytes=owned==null?0:owned.length;
            if(bytes>PackWorkspace.MAX_BYTES)throw new IllegalArgumentException("Source exceeds workspace limit");
            data=JsonStructure.supports(path) && owned!=null && bytes<=(location==null?JsonStructure.MAX_BYTES:WorkspaceTextSearch.MAX_FILE_BYTES)?owned:null;
        }
        int retainedBytes(){return data==null?0:data.length;}
        byte[] copy(BooleanSupplier cancelled){check(cancelled);if(data==null)throw new IllegalArgumentException("Source payload was not retained");var result=new byte[data.length];for(int i=0;i<data.length;i+=65536){check(cancelled);System.arraycopy(data,i,result,i,Math.min(65536,data.length-i));}check(cancelled);return result;}
    }
    record Report(String path,int bytes,JsonStructure.Snapshot structure,SourceTextPages.Index lines,SourceMatch.Location location,SourceMatch.Context context){
        Report {Objects.requireNonNull(structure);if(bytes!=structure.bytes() || (lines!=null)!=SourceTextPages.available(structure) || (location==null)!=(context==null))throw new IllegalArgumentException("Invalid prepared JSON source report");}
    }
    static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancellationException("JSON source review cancelled");}
    static Report inspect(Source source,BooleanSupplier cancelled)throws Exception{
        Objects.requireNonNull(source);Objects.requireNonNull(cancelled);check(cancelled);
        JsonStructure.Snapshot structure;byte[] data=null;
        if(!source.present || !JsonStructure.supports(source.path))structure=new JsonStructure.Snapshot(JsonStructure.Status.NOT_JSON,source.bytes,"",List.of(),"Select an existing .json resource to inspect its source.");
        else if(source.bytes>JsonStructure.MAX_BYTES)structure=new JsonStructure.Snapshot(JsonStructure.Status.TOO_LARGE,source.bytes,"",List.of(),"Structure/source display is limited to 1 MB; original bytes remain saveable/exportable.");
        else {data=source.copy(cancelled);structure=JsonStructure.inspect(data);}
        check(cancelled);var lines=SourceTextPages.available(structure)?SourceTextPages.index(structure,cancelled):null;SourceMatch.Context context=null;
        if(source.location!=null){if(data==null)data=source.copy(cancelled);String decoded=SourceTextPages.available(structure)?structure.source():WorkspaceTextSearch.decode(data);check(cancelled);context=SourceMatch.context(decoded,source.location);}
        check(cancelled);return new Report(source.path,source.bytes,structure,lines,source.location,context);
    }
    private JsonSourceReview(){}
}
