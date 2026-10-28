package dev.irisvisualizer.editor;

/** Bounded display labels. Does not alter source values or decode JSON tokens. */
public final class InspectorText {
    public static String label(String value,int maximum) {
        if (maximum<1 || maximum>16_384) throw new IllegalArgumentException("Invalid label limit");
        if (value==null) return "(none)";
        var out=new StringBuilder();int offset=0;
        while (offset<value.length()) {
            int code=value.codePointAt(offset);String token;
            if (Character.isISOControl(code) || code<=0xffff && Character.isSurrogate((char)code)) { String hex=Integer.toHexString(code);token="\\u"+"0".repeat(4-hex.length())+hex; }
            else token=new String(Character.toChars(code));
            if (out.length()+token.length()>maximum) break;
            out.append(token);offset+=Character.charCount(code);
        }
        if (offset<value.length()) out.append("...");return out.toString();
    }
}
