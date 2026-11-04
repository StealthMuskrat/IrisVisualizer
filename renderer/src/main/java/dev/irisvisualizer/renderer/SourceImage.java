package dev.irisvisualizer.renderer;

import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Detached original pack PNG pixels. No native map samples, animation schedule or world epoch is invented. */
public final class SourceImage {
    public record Identity(String resource,long revision) {
        public Identity {
            Objects.requireNonNull(resource);
            if(revision<1||resource.isBlank()||resource.length()>4096||resource.startsWith("/")||resource.endsWith("/")||resource.indexOf('\\')>=0||resource.indexOf(':')>=0||resource.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid image resource identity");
            for(String part:resource.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IllegalArgumentException("Image identity must be a relative resource path");
        }
    }
    public record Limits(int dimension,long pixels,long encodedBytes,long rgbaBytes,long workingBytes) {
        public static final Limits DEFAULT=new Limits(8192,16_777_216,32L*1024*1024,64L*1024*1024,128L*1024*1024);
        public Limits { if(dimension<1||dimension>8192||pixels<1||pixels>16_777_216||encodedBytes<0||encodedBytes>32L*1024*1024||rgbaBytes<0||rgbaBytes>64L*1024*1024||workingBytes<0||workingBytes>128L*1024*1024)throw new IllegalArgumentException("Source image limits exceed their bounds"); }
    }
    /** Frozen caller-captured bytes. Capture performs one bounded copy, never file I/O or PNG decoding. */
    public static final class Encoded {
        private final Identity identity;private final byte[] png;
        private Encoded(Identity identity,byte[] png) { this.identity=identity;this.png=png; }
        public static Encoded capture(Identity identity,byte[] png) {
            Objects.requireNonNull(identity);Objects.requireNonNull(png);if(png.length>Limits.DEFAULT.encodedBytes())throw new IllegalArgumentException("Encoded image exceeds 32 MiB");return new Encoded(identity,png.clone());
        }
        public Identity identity() { return identity; }
        public int bytes() { return png.length; }
    }
    public enum Code { FORMAT, LIMIT, MEMORY, WORKER }
    public record Problem(Code code,Identity source,String reason) { public Problem { Objects.requireNonNull(code);Objects.requireNonNull(source);Objects.requireNonNull(reason);if(reason.length()>8192)throw new IllegalArgumentException("Unbounded image diagnostic"); } }
    public record Receipt(Identity source,String sha256,int encodedBytes,int width,int height) {
        public Receipt { Objects.requireNonNull(source);Objects.requireNonNull(sha256);if(!sha256.matches("[0-9a-f]{64}")||encodedBytes<0||encodedBytes>32L*1024*1024||width<1||height<1||width>8192||height>8192||(long)width*height>16_777_216)throw new IllegalArgumentException("Invalid original image receipt"); }
    }
    // Two maximum RGBA16 input rows, filter/finish bytes, bounded palette/transparency arrays and digest scratch.
    private static final long SCRATCH=2L*8192*8+2+768+768+256;
    private final Identity identity;private final Optional<Receipt> receipt;private final Optional<RgbaImage> image;private final List<Problem> problems;
    private SourceImage(Identity identity,Optional<Receipt> receipt,Optional<RgbaImage> image,List<Problem> problems) {
        this.identity=identity;this.receipt=receipt;this.image=image;this.problems=List.copyOf(problems);
        if(receipt.isPresent()!=image.isPresent()||!problems.isEmpty()&&image.isPresent())throw new IllegalArgumentException("Partial original image escaped failure");
    }
    static long reservation(Encoded source,Limits limits) { return source.bytes()+limits.rgbaBytes()+SCRATCH; }
    static SourceImage failed(Identity identity,Code code,String reason) { return new SourceImage(identity,Optional.empty(),Optional.empty(),List.of(new Problem(code,identity,reason))); }
    public static SourceImage decode(Encoded source,BooleanSupplier cancelled) { return decode(source,Limits.DEFAULT,cancelled); }
    public static SourceImage decode(Encoded source,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(source);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        try {
            if(source.bytes()>limits.encodedBytes()||reservation(source,limits)>limits.workingBytes())return failed(source.identity(),Code.LIMIT,"Original image encoded/output/scratch allowance exceeds its budget");
            var bounds=new TextureMaterials.Limits(1,1,limits.dimension(),limits.pixels(),limits.rgbaBytes(),limits.encodedBytes(),0,1,1,1,0);
            var image=PngDecoder.decode(AssetId.minecraft("original_image"),source.png,bounds,limits.rgbaBytes(),cancelled);
            MessageDigest digest;try { digest=MessageDigest.getInstance("SHA-256"); }catch(NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
            for(int at=0;at<source.png.length;) { WorldProblem.check(cancelled);int count=Math.min(65536,source.png.length-at);digest.update(source.png,at,count);at+=count; }
            var receipt=new Receipt(source.identity(),HexFormat.of().formatHex(digest.digest()),source.bytes(),image.width(),image.height());WorldProblem.check(cancelled);
            return new SourceImage(source.identity(),Optional.of(receipt),Optional.of(image),List.of());
        }catch(ModelFields.Failure failure) { WorldProblem.check(cancelled);return failed(source.identity(),failure.problem.code()==ModelPlan.Code.LIMIT?Code.LIMIT:Code.FORMAT,failure.problem.reason()); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return failed(source.identity(),Code.MEMORY,"Not enough memory to decode the original image; no partial pixels were published"); }
    }
    public Identity identity() { return identity; }
    public Optional<Receipt> receipt() { return receipt; }
    public Optional<RgbaImage> image() { return image; }
    public List<Problem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    public long payloadBytes() { return image.map(value->(long)value.bytes()).orElse(0L); }
    public record Pixel(Receipt source,int x,int y,int rgba) { public Pixel { Objects.requireNonNull(source);if(x<0||y<0||x>=source.width()||y>=source.height())throw new IllegalArgumentException("Pixel outside original image"); } }
    public Optional<Pixel> hit(MapView view,double x,double y) {
        Objects.requireNonNull(view);if(requiresPlaceholder())return Optional.empty();var pixels=image.orElseThrow();if(view.width()!=pixels.width()||view.height()!=pixels.height())throw new IllegalArgumentException("Image transform must match original dimensions");
        return view.pixelAt(x,y).map(pixel->new Pixel(receipt.orElseThrow(),pixel.x(),pixel.z(),pixels.rgba(pixel.x(),pixel.z())));
    }
}
