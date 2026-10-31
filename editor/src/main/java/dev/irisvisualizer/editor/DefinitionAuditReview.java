package dev.irisvisualizer.editor;

import java.util.*;
import java.util.function.*;

/** Pages recorded definition-audit metadata only. Filtering never scans resources or grants edit authority. */
public final class DefinitionAuditReview {
    private static String text(String raw){if(raw==null || raw.length()>256 || raw.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Use up to 256 review filter characters without controls");return raw.strip().toLowerCase(Locale.ROOT);}
    private static boolean contains(String value,String term){return value!=null && (term.isEmpty() || value.toLowerCase(Locale.ROOT).contains(term));}
    public enum Scope {ALL,OUTGOING,INCOMING}
    public enum Status {
        ALL("All recorded links"),PRESENT("Exact target present"),MISSING("Exact target absent"),IMPLICIT("Implicit default"),CONDITIONAL("Conditional backup"),HIGHER_PRIORITY("Higher-priority field configured");
        private final String label;Status(String label){this.label=label;}public String label(){return label;}
    }
    public static List<Status> aliasStatuses(){return List.of(Status.ALL,Status.PRESENT,Status.IMPLICIT,Status.CONDITIONAL);}
    public static List<Status> expressionStatuses(){return List.of(Status.ALL,Status.PRESENT,Status.MISSING,Status.HIGHER_PRIORITY);}
    public record TextQuery(String text){public TextQuery{text=DefinitionAuditReview.text(text);}}
    public record LinkQuery(String text,Scope scope,Status status,String selected){
        public LinkQuery{text=DefinitionAuditReview.text(text);Objects.requireNonNull(scope);Objects.requireNonNull(status);if(scope==Scope.ALL)selected=null;else if(selected==null || selected.isEmpty())throw new IllegalArgumentException("Choose a resource for incoming/outgoing review");}
        boolean matches(String source,String field,String key,String target,boolean present,boolean implicit,boolean conditional,boolean priority){
            if(scope==Scope.OUTGOING && !source.equals(selected) || scope==Scope.INCOMING && !target.equals(selected))return false;
            if(!switch(status){case ALL->true;case PRESENT->present;case MISSING->!present;case IMPLICIT->implicit;case CONDITIONAL->conditional;case HIGHER_PRIORITY->priority;})return false;
            return contains(source,text)||contains(field,text)||contains(key,text)||contains(target,text);
        }
    }
    public record GroupQuery(String text,String member,boolean conditionalOnly){
        public GroupQuery{text=DefinitionAuditReview.text(text);}
        boolean matches(List<String> members,boolean conditional){return (!conditionalOnly || conditional) && (member==null || members.contains(member)) && members.stream().anyMatch(path->contains(path,text));}
    }
    public record NoticeQuery(String text,String path){
        public NoticeQuery{text=DefinitionAuditReview.text(text);}
        boolean matches(String source,String field,String message){return (path==null || path.equals(source)) && (contains(source,text)||contains(field,text)||contains(message,text));}
    }
    public record LinkRow(int index,String source,String field,String key,String target,boolean present,boolean implicit,boolean conditional,boolean higherPriority){}
    /** Index addresses the original component list; members are not an ordered cycle witness. */
    public record GroupRow(int index,int members,boolean conditional){}
    public record NoticeRow(int index,String path,String field,String message){}
    private record MemberKey(int group,TextQuery query){}
    private abstract static class Memo<T> {
        private Object owner,key;private ReviewPagination.Page<T> page;
        public void clear(){owner=null;key=null;page=null;}
        final ReviewPagination.Page<T> memo(Object current,Object next,int requested,Supplier<ReviewPagination.Page<T>> compute){
            Objects.requireNonNull(current);Objects.requireNonNull(next);
            if(owner==current && next.equals(key) && page!=null && page.window().equals(ReviewPagination.Window.at(page.window().matches(),requested)))return page;
            var result=compute.get();owner=current;key=next;return page=result;
        }
    }
    /** Counts against the original list, then maps only one bounded result page. Original indices stay stable. */
    private static <E,T> ReviewPagination.Page<T> select(List<E> values,Predicate<E> predicate,BiFunction<Integer,E,T> map,int requested){
        int matches=0;for(var value:values)if(predicate.test(value))matches++;
        var window=ReviewPagination.Window.at(matches,requested);var rows=new ArrayList<T>(window.end()-window.start());int seen=0;
        if(matches>0)for(int index=0;index<values.size();index++){var value=values.get(index);if(!predicate.test(value))continue;if(seen>=window.start())rows.add(map.apply(index,value));if(++seen>=window.end())break;}
        return new ReviewPagination.Page<>(window,rows);
    }
    public static final class Links extends Memo<LinkRow> {
        public ReviewPagination.Page<LinkRow> page(BlockAliasAudit.Audit audit,LinkQuery query,int requested){
            return memo(audit,query,requested,()->select(audit.edges(),e->query.matches(e.source(),e.field(),e.key(),e.target(),true,e.implicit(),e.conditional(),false),(i,e)->new LinkRow(i,e.source(),e.field(),e.key(),e.target(),true,e.implicit(),e.conditional(),false),requested));
        }
        public ReviewPagination.Page<LinkRow> page(ExpressionReferenceAudit.Audit audit,LinkQuery query,int requested){
            return memo(audit,query,requested,()->select(audit.edges(),e->query.matches(e.source(),e.field(),e.key(),e.target(),e.present(),false,false,e.higherPriorityConfigured()),(i,e)->new LinkRow(i,e.source(),e.field(),e.key(),e.target(),e.present(),false,false,e.higherPriorityConfigured()),requested));
        }
    }
    public static final class Groups extends Memo<GroupRow> {
        public ReviewPagination.Page<GroupRow> page(BlockAliasAudit.Audit audit,GroupQuery query,int requested){return memo(audit,query,requested,()->select(audit.cycles(),c->query.matches(c.members(),c.backupLinks()),(i,c)->new GroupRow(i,c.members().size(),c.backupLinks()),requested));}
        public ReviewPagination.Page<GroupRow> page(ExpressionReferenceAudit.Audit audit,GroupQuery query,int requested){return memo(audit,query,requested,()->select(audit.cycles(),c->query.matches(c,false),(i,c)->new GroupRow(i,c.size(),false),requested));}
    }
    public static final class Members extends Memo<String> {
        public ReviewPagination.Page<String> page(BlockAliasAudit.Audit audit,int group,TextQuery query,int requested){if(group<0 || group>=audit.cycles().size())throw new IllegalArgumentException("Choose a recorded cycle group");return memo(audit,new MemberKey(group,query),requested,()->ReviewPagination.select(audit.cycles().get(group).members(),p->contains(p,query.text()),requested));}
        public ReviewPagination.Page<String> page(ExpressionReferenceAudit.Audit audit,int group,TextQuery query,int requested){if(group<0 || group>=audit.cycles().size())throw new IllegalArgumentException("Choose a recorded cycle group");return memo(audit,new MemberKey(group,query),requested,()->ReviewPagination.select(audit.cycles().get(group),p->contains(p,query.text()),requested));}
    }
    public static final class Notices extends Memo<NoticeRow> {
        public ReviewPagination.Page<NoticeRow> page(BlockAliasAudit.Audit audit,NoticeQuery query,int requested){return memo(audit,query,requested,()->select(audit.notices(),n->query.matches(n.path(),n.field(),n.message()),(i,n)->new NoticeRow(i,n.path(),n.field(),n.message()),requested));}
        public ReviewPagination.Page<NoticeRow> page(ExpressionReferenceAudit.Audit audit,NoticeQuery query,int requested){return memo(audit,query,requested,()->select(audit.notices(),n->query.matches(n.path(),n.field(),n.message()),(i,n)->new NoticeRow(i,n.path(),n.field(),n.message()),requested));}
    }
    public static final class Inspected extends Memo<String> {
        public ReviewPagination.Page<String> page(BlockAliasAudit.Audit audit,TextQuery query,int requested){return memo(audit,query,requested,()->ReviewPagination.select(audit.inspected(),p->contains(p,query.text()),requested));}
        public ReviewPagination.Page<String> page(ExpressionReferenceAudit.Audit audit,TextQuery query,int requested){return memo(audit,query,requested,()->ReviewPagination.select(audit.inspected(),p->contains(p,query.text()),requested));}
    }
    private DefinitionAuditReview(){}
}
