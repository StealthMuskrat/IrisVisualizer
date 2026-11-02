package dev.irisvisualizer.editor;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Presentation state for editable root lists. Row indices always address the complete applied list. */
final class InspectorListPages {
    /** A native single-line buffer cannot faithfully represent NUL. Oversize source strings stay opaque. */
    static boolean editableString(String value){return value!=null && value.length()<=4096 && value.indexOf(0)<0;}
    record Scope(String id,String prefix,boolean descendants){
        Scope{Objects.requireNonNull(id);Objects.requireNonNull(prefix);if(id.isEmpty() || id.length()>256 || !prefix.endsWith("#"))throw new IllegalArgumentException("Invalid inspector list scope");}
        /** Only canonical generated row keys participate. New-entry forms and other scopes stay separate. */
        int row(String key){
            if(key==null || !key.startsWith(prefix))return -1;
            int at=prefix.length(),start=at;long value=0;
            while(at<key.length() && key.charAt(at)>='0' && key.charAt(at)<='9'){
                if(at>start && key.charAt(start)=='0')return -1;
                value=value*10+key.charAt(at++)-'0';if(value>Integer.MAX_VALUE)return -1;
            }
            if(at==start || at<key.length() && (!descendants || key.charAt(at)!='.' || at+1==key.length()))return -1;
            return (int)value;
        }
        boolean offPage(String key,ReviewPagination.Window window){int row=row(key);return row>=0 && (row<window.start() || row>=window.end());}
        boolean evict(String key,ReviewPagination.Window window,InspectorDrafts drafts){return offPage(key,window) && !drafts.contains(key);}
    }
    static Scope strings(String field){if(field==null || !field.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}"))throw new IllegalArgumentException("Choose a known list field");return new Scope("strings:"+field,field+"#",false);}
    static final Scope GENERATORS=new Scope("generator-links","generator#",true);
    record View(long generation,ReviewPagination.Window window,int revealRow){}
    private static final class Position {int page,reveal=-1;}
    private PackWorkspace owner;private String path;private long generation;
    private final Map<Scope,Position> positions=new HashMap<>();
    private Position bind(PackWorkspace current,String selected,Scope scope,int count){
        Objects.requireNonNull(current);Objects.requireNonNull(scope);
        if(selected==null || !current.paths().contains(selected) || count<0)throw new IllegalArgumentException("Choose an applied resource and a nonnegative list size");
        if(owner!=current || !Objects.equals(path,selected)){clear();owner=current;path=selected;}
        return positions.computeIfAbsent(scope,ignored->new Position());
    }
    View select(PackWorkspace current,String selected,Scope scope,int count){
        var position=bind(current,selected,scope,count);var window=ReviewPagination.Window.at(count,position.page);position.page=window.page();
        if(position.reveal>=count || position.reveal>=0 && (position.reveal<window.start() || position.reveal>=window.end()))position.reveal=-1;
        return new View(generation,window,position.reveal);
    }
    View navigate(PackWorkspace current,String selected,Scope scope,int count,int requested){
        var position=bind(current,selected,scope,count);int page=ReviewPagination.Window.at(count,requested).page();if(page!=position.page)position.reveal=-1;position.page=page;
        return select(current,selected,scope,count);
    }
    /** Observe an already committed structural action; cancellation must never call this method. */
    View reveal(PackWorkspace current,String selected,Scope scope,int count,int row){
        if(row<0 || count<0 || count==0 && row!=0 || count>0 && row>=count)throw new IllegalArgumentException("Reveal an existing row or the empty list");
        var position=bind(current,selected,scope,count);position.page=row/ReviewPagination.PAGE_SIZE;position.reveal=count==0?-1:row;
        return select(current,selected,scope,count);
    }
    boolean consumeReveal(PackWorkspace current,String selected,Scope scope,int row){
        if(owner!=current || !Objects.equals(path,selected))return false;var position=positions.get(scope);
        if(position==null || position.reveal!=row || row<0)return false;position.reveal=-1;return true;
    }
    /** Hidden/empty inspectors release old ownership without parsing or scheduling any work. */
    void poll(PackWorkspace current,String selected){
        Objects.requireNonNull(current);
        if(owner!=null && (owner!=current || !Objects.equals(path,selected) || selected==null || !current.paths().contains(selected)))clear();
    }
    void clear(){owner=null;path=null;positions.clear();generation++;}
}
