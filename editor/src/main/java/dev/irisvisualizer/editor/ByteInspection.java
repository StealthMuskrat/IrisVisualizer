package dev.irisvisualizer.editor;

import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Read-only encoded-byte pages. No text/image/object decoding and no disk or native access. */
public final class ByteInspection {
    public static final int PAGE_BYTES=1024,ROW_BYTES=16;
    private static final char[] HEX="0123456789ABCDEF".toCharArray();
    /** Immutable-by-ownership workspace array; the single inspection worker alone memoizes its digest. */
    static final class Source {
        final String path;private final byte[] data;private String sha256;
        Source(String path,byte[] data) {this.path=Objects.requireNonNull(path);this.data=Objects.requireNonNull(data);if(data.length>PackWorkspace.MAX_BYTES)throw new IllegalArgumentException("Resource exceeds workspace byte limit");}
        int size() {return data.length;}
    }
    public record Window(int page,int bytes) {
        public Window {if(bytes<0 || bytes>PackWorkspace.MAX_BYTES || page<0 || page>Math.max(0,(bytes-1)/PAGE_BYTES))throw new IllegalArgumentException("Invalid byte-inspection page");}
        public static Window at(int bytes,int requested) {if(bytes<0 || bytes>PackWorkspace.MAX_BYTES)throw new IllegalArgumentException("Invalid resource size");return new Window(Math.clamp(requested,0,Math.max(0,(bytes-1)/PAGE_BYTES)),bytes);}
        public int pages() {return Math.max(1,(bytes-1)/PAGE_BYTES+1);}
        public int start() {return page*PAGE_BYTES;}
        public int end() {return (int)Math.min((long)bytes,(long)start()+PAGE_BYTES);}
        public boolean previous() {return page>0;}
        public boolean next() {return page+1<pages();}
    }
    public record Row(int offset,int bytes,String hex,String ascii) {
        public Row {if(offset<0 || bytes<1 || bytes>ROW_BYTES || hex==null || hex.length()!=3*bytes-1 || ascii==null || ascii.length()!=bytes)throw new IllegalArgumentException("Invalid byte row");}
    }
    public record Result(Window window,String sha256,List<Row> rows) {
        public Result {
            Objects.requireNonNull(window);if(sha256==null || !sha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Expected SHA-256 digest");rows=List.copyOf(rows);
            if(rows.size()!=(window.end()-window.start()+ROW_BYTES-1)/ROW_BYTES)throw new IllegalArgumentException("Incomplete byte page");
            for(int i=0;i<rows.size();i++){var row=rows.get(i);int offset=window.start()+i*ROW_BYTES;if(row.offset()!=offset || row.bytes()!=Math.min(ROW_BYTES,window.end()-offset))throw new IllegalArgumentException("Byte row outside page");}
        }
    }
    /** Decimal or 0x-prefixed zero-based byte offsets; invalid input never replaces an accepted request. */
    public static int offsetPage(String input,int bytes) {
        Window.at(bytes,0);if(bytes==0)throw new IllegalArgumentException("This resource has no addressable bytes");
        if(input==null || input.length()>18)throw new IllegalArgumentException("Use a decimal or 0x hexadecimal byte offset (up to 18 characters)");String text=input.strip();boolean hex=text.startsWith("0x") || text.startsWith("0X");String digits=hex ? text.substring(2) : text;
        if(!digits.matches(hex ? "[0-9a-fA-F]+" : "[0-9]+"))throw new IllegalArgumentException("Use a decimal or 0x hexadecimal byte offset");
        long value;try {value=Long.parseLong(digits,hex ? 16 : 10);}catch(NumberFormatException overflow){throw new IllegalArgumentException("Byte offset is outside this resource");}
        if(value<0 || value>=bytes)throw new IllegalArgumentException("Use a byte offset from 0 through "+(bytes-1));return (int)(value/PAGE_BYTES);
    }
    static Result inspect(Source source,int requested,BooleanSupplier cancelled,LongConsumer progress) throws Exception {
        Objects.requireNonNull(source);Objects.requireNonNull(cancelled);Objects.requireNonNull(progress);check(cancelled);var window=Window.at(source.size(),requested);
        if(source.sha256==null) {
            var digest=MessageDigest.getInstance("SHA-256");
            for(int offset=0;offset<source.data.length;offset+=65536){check(cancelled);int count=Math.min(65536,source.data.length-offset);digest.update(source.data,offset,count);progress.accept((long)offset+count);}
            check(cancelled);source.sha256=HexFormat.of().formatHex(digest.digest());
        }
        progress.accept(source.size());var rows=new ArrayList<Row>((window.end()-window.start()+ROW_BYTES-1)/ROW_BYTES);
        for(int offset=window.start();offset<window.end();offset+=ROW_BYTES) {
            check(cancelled);int count=Math.min(ROW_BYTES,window.end()-offset);char[] hex=new char[count*3-1],ascii=new char[count];
            for(int i=0;i<count;i++){int value=source.data[offset+i]&255;hex[i*3]=HEX[value>>>4];hex[i*3+1]=HEX[value&15];if(i+1<count)hex[i*3+2]=' ';ascii[i]=value>=32 && value<=126 ? (char)value : '.';}
            rows.add(new Row(offset,count,new String(hex),new String(ascii)));
        }
        check(cancelled);return new Result(window,source.sha256,rows);
    }
    private static void check(BooleanSupplier cancelled) {if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancellationException("Byte inspection cancelled");}
    private ByteInspection() {}
}
