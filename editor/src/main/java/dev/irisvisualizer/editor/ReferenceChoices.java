package dev.irisvisualizer.editor;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Pages already-authorized exact key metadata. This helper never validates references or grants edit authority. */
public final class ReferenceChoices {
    public static final int MAX_QUERY_CHARACTERS=256,MAX_KEYS=100_000,MAX_CHARACTERS=16_000_000;
    public static String query(String text){
        if(text==null || text.length()>MAX_QUERY_CHARACTERS || text.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Use up to 256 filter characters without controls");return text.strip().toLowerCase(Locale.ROOT);
    }
    public static final class Catalog {
        private final List<String> keys;
        public Catalog(List<String> keys){
            Objects.requireNonNull(keys);if(keys.size()>MAX_KEYS)throw new IllegalArgumentException("Reference catalog exceeds 100,000 keys");long characters=0;
            for(String key:keys){Objects.requireNonNull(key);characters+=key.length();if(characters>MAX_CHARACTERS)throw new IllegalArgumentException("Reference catalog exceeds 16 million key characters");}this.keys=List.copyOf(keys);
        }
        public List<String> keys(){return keys;}
        public ReviewPagination.Page<String> page(String text,int requested){return select(query(text),requested);}
        private ReviewPagination.Page<String> select(String term,int requested){return ReviewPagination.select(keys,key->key.toLowerCase(Locale.ROOT).contains(term),requested);}
    }
    /** A view caches one bounded page. Independent views share the immutable catalog rather than copying all keys. */
    public static final class View {
        private Catalog catalog;private String query;private ReviewPagination.Page<String> page;
        public void clear(){catalog=null;query=null;page=null;}
        public ReviewPagination.Page<String> page(Catalog current,String text,int requested){
            Objects.requireNonNull(current);String term=ReferenceChoices.query(text);
            if(catalog==current && term.equals(query) && page!=null && page.window().equals(ReviewPagination.Window.at(page.window().matches(),requested)))return page;
            var next=current.select(term,requested);catalog=current;query=term;return page=next;
        }
    }
    private ReferenceChoices(){}
}
