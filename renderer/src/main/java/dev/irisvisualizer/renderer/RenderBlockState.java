package dev.irisvisualizer.renderer;

import java.util.*;

/** Native identity plus a native property domain; the renderer never infers or rewrites engine states. */
public record RenderBlockState(int nativeStateId,String original,AssetId block,
                               Map<String,String> properties,Map<String,Set<String>> propertyDomain) {
    public RenderBlockState {
        Objects.requireNonNull(original);Objects.requireNonNull(block);Objects.requireNonNull(properties);Objects.requireNonNull(propertyDomain);
        if(nativeStateId<0 || original.isEmpty() || original.length()>16_384 || properties.size()>128 || propertyDomain.size()>128)
            throw new IllegalArgumentException("Native render-state identity exceeds its bounds");
        var values=new TreeMap<String,String>();var domains=new TreeMap<String,Set<String>>();int domainValues=0,domainCharacters=0;
        for(var entry:propertyDomain.entrySet()) {
            property(entry.getKey());Objects.requireNonNull(entry.getValue());
            if(entry.getValue().isEmpty() || entry.getValue().size()>1024)throw new IllegalArgumentException("Native property domain exceeds its bounds");
            var allowed=new TreeSet<String>();for(String value:entry.getValue()){
                value(value);if(++domainValues>4096 || (domainCharacters+=value.length())>262_144)throw new IllegalArgumentException("Native property domain exceeds its aggregate budget");allowed.add(value);
            }
            domains.put(entry.getKey(),Collections.unmodifiableSet(allowed));
        }
        if(!properties.keySet().equals(domains.keySet()))throw new IllegalArgumentException("Supply the complete native property domain and values");
        for(var entry:properties.entrySet()) {
            property(entry.getKey());value(entry.getValue());
            if(!domains.get(entry.getKey()).contains(entry.getValue()))throw new IllegalArgumentException("Native property value is outside its supplied domain");
            values.put(entry.getKey(),entry.getValue());
        }
        properties=Collections.unmodifiableMap(values);propertyDomain=Collections.unmodifiableMap(domains);
    }
    static void property(String name) { if(name==null || !name.matches("[a-z0-9_]{1,128}"))throw new IllegalArgumentException("Unsupported native property name"); }
    static void value(String value) { if(value==null || !value.matches("[a-z0-9_.:/-]{1,256}"))throw new IllegalArgumentException("Unsupported native property value"); }
}
