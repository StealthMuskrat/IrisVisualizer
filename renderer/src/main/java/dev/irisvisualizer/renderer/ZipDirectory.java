package dev.irisvisualizer.renderer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Preflight before ZipFile allocates its central-directory index, including ZIP64 entry counts. */
final class ZipDirectory {
    private static final int MAX_DIRECTORY_BYTES=32*1024*1024;
    /** Tail, central bytes and simultaneous ZIP64 locator/end headers; JDK ZIP index objects are separate. */
    static long workingBytes(long archiveBytes) { return Math.min(archiveBytes,MAX_DIRECTORY_BYTES)+Math.min(archiveBytes,22+65535)+76; }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("Client ZIP directory reading cancelled"); }
    private static ByteBuffer read(FileChannel channel,long position,int bytes,BooleanSupplier cancelled)throws IOException {
        if(position<0 || bytes<0 || position>channel.size()-bytes)throw new IOException("Client ZIP directory lies outside its archive");
        var result=ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        while(result.hasRemaining()) { check(cancelled);int count=channel.read(result,position+result.position());if(count<0)throw new IOException("Truncated client ZIP directory"); }
        return result.flip();
    }
    private static int u16(ByteBuffer bytes,int at) { return Short.toUnsignedInt(bytes.getShort(at)); }
    private static long u32(ByteBuffer bytes,int at) { return Integer.toUnsignedLong(bytes.getInt(at)); }
    static void verify(Path archive,long capturedBytes,ClientAssets.Limits limits,BooleanSupplier cancelled)throws IOException {
        check(cancelled);
        try(var channel=FileChannel.open(archive,StandardOpenOption.READ)) {
            long size=channel.size();if(size!=capturedBytes || size<22)throw new IOException("Client ZIP is empty, truncated or changed");
            int tailSize=(int)Math.min(size,22+65_535);long tailStart=size-tailSize;var tail=read(channel,tailStart,tailSize,cancelled);int end=-1;
            for(int at=tailSize-22;at>=0;at--) {
                check(cancelled);
                if(tail.getInt(at)==0x06054b50 && at+22+u16(tail,at+20)==tailSize){end=at;break;}
            }
            if(end<0)throw new IOException("Client ZIP has no complete end record");
            if(u16(tail,end+4)!=0 || u16(tail,end+6)!=0)throw new IOException("Split client ZIP archives are unsupported");
            long count=u16(tail,end+10),directoryBytes=u32(tail,end+12),directoryOffset=u32(tail,end+16),endOffset=tailStart+end;
            if(u16(tail,end+8)!=count)throw new IOException("Client ZIP has inconsistent split-disk entry counts");
            boolean needsZip64=directoryBytes==0xffff_ffffL || directoryOffset==0xffff_ffffL;
            ByteBuffer locator=(needsZip64 || count==65_535) && endOffset>=20?read(channel,endOffset-20,20,cancelled):null;
            boolean hasZip64=locator!=null && locator.getInt(0)==0x07064b50;
            if(needsZip64 && !hasZip64)throw new IOException("Client ZIP64 locator is missing");
            if(hasZip64) {
                if(u32(locator,4)!=0 || u32(locator,16)!=1)throw new IOException("Client ZIP64 locator is invalid or split");
                long recordOffset=locator.getLong(8);var record=read(channel,recordOffset,56,cancelled);long recordSize=record.getLong(4);
                if(record.getInt(0)!=0x06064b50 || recordSize<44 || recordOffset>endOffset-32 || recordSize!=endOffset-32-recordOffset || u32(record,16)!=0 || u32(record,20)!=0)
                    throw new IOException("Client ZIP64 end record is invalid or split");
                count=record.getLong(32);if(count<0 || record.getLong(24)!=count)throw new IOException("Client ZIP64 entry count is invalid");
                directoryBytes=record.getLong(40);directoryOffset=record.getLong(48);endOffset=recordOffset;
            }
            if(count<1 || count>limits.entries() || directoryBytes<0 || directoryBytes>MAX_DIRECTORY_BYTES || directoryOffset<0 || directoryBytes>endOffset || directoryOffset>endOffset-directoryBytes)
                throw new IOException("Client ZIP central directory exceeds its bounds or metadata budget");
            var directory=read(channel,directoryOffset,(int)directoryBytes,cancelled);int at=0,found=0;long names=0;
            while(at<directory.limit()) {
                check(cancelled);
                // Optional central-directory signature carries no entries and must occupy the remaining bytes.
                if(directory.limit()-at>=6 && directory.getInt(at)==0x05054b50 && directory.limit()-at==6+u16(directory,at+4)){at=directory.limit();break;}
                if(directory.limit()-at<46 || directory.getInt(at)!=0x02014b50)throw new IOException("Malformed client ZIP central entry");
                if(++found>limits.entries() || u16(directory,at+34)!=0)throw new IOException("Client ZIP entry count/disk exceeds its bounds");
                int name=u16(directory,at+28),extra=u16(directory,at+30),comment=u16(directory,at+32);long next=(long)at+46+name+extra+comment;
                names+=name;if(name==0 || name>4*4096 || names>4L*limits.pathCharacters() || next>directory.limit())throw new IOException("Client ZIP entry names/lengths exceed their bounds");
                at=(int)next;
            }
            if(found!=count)throw new IOException("Client ZIP central entry count disagrees with its end record");
        }
    }
}
