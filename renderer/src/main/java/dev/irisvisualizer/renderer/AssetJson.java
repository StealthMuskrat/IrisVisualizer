package dev.irisvisualizer.renderer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Bounded, read-only asset metadata parsing. No pack writer or generation semantics live here. */
final class AssetJson {
    sealed interface Value permits ObjectValue,ArrayValue,Text,NumberToken,Flag,Empty {}
    record ObjectValue(Map<String,Value> fields) implements Value {
        ObjectValue { fields=Collections.unmodifiableMap(new LinkedHashMap<>(fields)); }
    }
    record ArrayValue(List<Value> values) implements Value { ArrayValue { values=List.copyOf(values); } }
    record Text(String value) implements Value {}
    record NumberToken(String spelling) implements Value {}
    record Flag(boolean value) implements Value {}
    enum Empty implements Value { NULL }
    record Limits(int bytes,int nodes,int depth,int stringCharacters) {
        Limits {
            if(bytes<1 || bytes>16*1024*1024 || nodes<1 || nodes>262_144 || depth<1 || depth>128 || stringCharacters<1 || stringCharacters>1_048_576)
                throw new IllegalArgumentException("Asset JSON limits exceed renderer metadata bounds");
        }
    }
    private final String source;private final Limits limits;private final BooleanSupplier cancelled;
    private int at,nodes,ticks;
    private AssetJson(String source,Limits limits,BooleanSupplier cancelled) { this.source=source;this.limits=limits;this.cancelled=cancelled; }
    static Value parse(byte[] bytes,Limits limits,BooleanSupplier cancelled)throws IOException {
        Objects.requireNonNull(bytes);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);
        if(cancelled.getAsBoolean())throw new CancellationException("Asset JSON reading cancelled");
        if(bytes.length>limits.bytes())throw new IOException("Asset JSON exceeds its byte limit");
        String text;
        try { text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch(CharacterCodingException invalid) { throw new IOException("Asset JSON is not strict UTF-8",invalid); }
        var parser=new AssetJson(text,limits,cancelled);var result=parser.value(0);parser.space();
        if(parser.at!=text.length())throw parser.error("Trailing JSON content");
        if(cancelled.getAsBoolean())throw new CancellationException("Asset JSON reading cancelled");return result;
    }
    private void poll() { if((ticks++&1023)==0 && cancelled.getAsBoolean())throw new CancellationException("Asset JSON reading cancelled"); }
    private IOException error(String message) { return new IOException(message+" at JSON character "+at); }
    private char peek() { return at<source.length()?source.charAt(at):0; }
    private void space() { while(at<source.length()) { poll();char c=source.charAt(at);if(c!=' ' && c!='\n' && c!='\r' && c!='\t')return;at++; } }
    private void require(char expected)throws IOException { space();if(peek()!=expected)throw error("Expected '"+expected+"'");at++; }
    private Value value(int depth)throws IOException {
        poll();space();if(++nodes>limits.nodes() || depth>limits.depth())throw error("Asset JSON node/depth limit reached");
        return switch(peek()) {
            case '{'->object(depth);case '['->array(depth);case '"'->new Text(string());
            case 't'->{literal("true");yield new Flag(true);}case 'f'->{literal("false");yield new Flag(false);}
            case 'n'->{literal("null");yield Empty.NULL;}
            default->number();
        };
    }
    private ObjectValue object(int depth)throws IOException {
        require('{');space();var fields=new LinkedHashMap<String,Value>();
        if(peek()=='}'){at++;return new ObjectValue(fields);}
        while(true) {
            space();String key=string();if(fields.containsKey(key))throw error("Duplicate asset JSON object key");
            require(':');fields.put(key,value(depth+1));space();
            if(peek()=='}'){at++;return new ObjectValue(fields);}require(',');
        }
    }
    private ArrayValue array(int depth)throws IOException {
        require('[');space();var values=new ArrayList<Value>();
        if(peek()==']'){at++;return new ArrayValue(values);}
        while(true) { values.add(value(depth+1));space();if(peek()==']'){at++;return new ArrayValue(values);}require(','); }
    }
    private void literal(String token)throws IOException { if(!source.startsWith(token,at))throw error("Invalid JSON literal");at+=token.length(); }
    private NumberToken number()throws IOException {
        int start=at;if(peek()=='-')at++;
        if(peek()=='0')at++;
        else { if(peek()<'1' || peek()>'9')throw error("Invalid JSON value");while(digit(peek())){poll();at++;} }
        if(peek()=='.'){at++;if(!digit(peek()))throw error("Missing fractional digits");while(digit(peek())){poll();at++;}}
        if(peek()=='e' || peek()=='E'){at++;if(peek()=='+' || peek()=='-')at++;if(!digit(peek()))throw error("Missing exponent digits");while(digit(peek())){poll();at++;}}
        if(at-start>256)throw error("Asset JSON number exceeds its token limit");return new NumberToken(source.substring(start,at));
    }
    private static boolean digit(char c) { return c>='0' && c<='9'; }
    private String string()throws IOException {
        if(peek()!='"')throw error("Expected a JSON string");at++;var text=new StringBuilder();boolean closed=false;
        while(at<source.length()) {
            poll();char c=source.charAt(at++);if(c=='"'){closed=true;break;}
            if(c<32)throw error("Unescaped JSON control character");
            if(c=='\\') {
                if(at>=source.length())throw error("Incomplete JSON escape");char escape=source.charAt(at++);
                c=switch(escape) {
                    case '"','\\','/'->escape;case 'b'->'\b';case 'f'->'\f';case 'n'->'\n';case 'r'->'\r';case 't'->'\t';
                    case 'u'->{int code=0;for(int i=0;i<4;i++){if(at>=source.length())throw error("Incomplete Unicode escape");char h=source.charAt(at++);int hex=h>='0'&&h<='9'?h-'0':h>='a'&&h<='f'?h-'a'+10:h>='A'&&h<='F'?h-'A'+10:-1;if(hex<0)throw error("Invalid Unicode escape");code=(code<<4)|hex;}yield(char)code;}
                    default->throw error("Invalid JSON escape");
                };
            }
            if(text.length()>=limits.stringCharacters())throw error("Asset JSON string exceeds its character limit");text.append(c);
        }
        if(!closed)throw error("Unterminated JSON string");
        for(int i=0;i<text.length();i++) {
            poll();char c=text.charAt(i);
            if(Character.isHighSurrogate(c)){if(++i>=text.length() || !Character.isLowSurrogate(text.charAt(i)))throw error("Unpaired Unicode surrogate");}
            else if(Character.isLowSurrogate(c))throw error("Unpaired Unicode surrogate");
        }
        return text.toString();
    }
}
