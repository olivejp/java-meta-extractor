package fr.cafat.meta.output;

import fr.cafat.meta.model.Application;
import fr.cafat.meta.model.Call;
import fr.cafat.meta.model.Column;
import fr.cafat.meta.model.Datasource;
import fr.cafat.meta.model.Diagnostic;
import fr.cafat.meta.model.Endpoint;
import fr.cafat.meta.model.Entity;
import fr.cafat.meta.model.ExtractionResult;
import fr.cafat.meta.model.Messaging;
import fr.cafat.meta.model.Relation;
import fr.cafat.meta.model.SecondaryTable;
import fr.cafat.meta.model.Source;
import fr.cafat.meta.model.SqlAccess;
import fr.cafat.meta.model.SqlTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Assemblage final : identifiants des diagnostics, suppression des doublons exacts, suffixes
 * {@code ~2}, {@code ~3} pour les identifiants en collision (dans l'ordre fichier, ligne, puis
 * contenu), et tri de tous les tableaux.
 */
public final class Assembler {

  private Assembler() {
  }

  public static ExtractionResult assemble(Application app, List<Entity> entities,
      List<Relation> relations, List<SqlAccess> sql, List<Endpoint> endpoints, List<Call> calls,
      List<Messaging> messaging, List<Diagnostic> diagnostics) {
    String appId = app.id();
    Application sortedApp = new Application(app.id(), app.name(), app.version(),
        List.copyOf(new TreeSet<>(app.tech())), app.repository(), app.commit(), app.module(),
        app.profiles(), app.contextPath(),
        sorted(app.datasources(), Comparator.comparing(Datasource::id)));

    List<Entity> ents = new ArrayList<>();
    for (Entity e : dedupExact(entities)) {
      ents.add(new Entity(e.id(), e.className(), e.name(), e.kind(), e.datasource(), e.schema(),
          e.table(), e.isView(), e.parent(), e.inheritance(),
          sorted(e.secondaryTables(), Comparator.comparing(
              (SecondaryTable t) -> (t.schema() == null ? "" : t.schema()) + "\u0000" + t.name())),
          e.source(), sorted(e.columns(), Comparator.comparing(Column::sortKey))));
    }

    List<SqlAccess> sqls = new ArrayList<>();
    for (SqlAccess s : sql) {
      sqls.add(new SqlAccess(s.id(), s.origin(), s.datasource(), s.sql(),
          sorted(new ArrayList<>(new LinkedHashSet<>(s.tables())), Comparator.comparing(SqlTable::sortKey)),
          s.parsed(), s.caller(), s.source()));
    }

    List<Diagnostic> diags = new ArrayList<>();
    for (Diagnostic d : diagnostics) {
      Source src = d.source();
      String key = d.level() + "|" + d.code() + "|" + d.message() + "|"
          + (src == null ? "" : src.className() + "|" + src.file() + "|" + src.line());
      diags.add(d.withId(appId + ":" + d.code() + ":" + Hashes.sha1(key).substring(0, 12)));
    }

    return new ExtractionResult(ExtractionResult.CONTRACT_VERSION, sortedApp,
        finish(ents, Entity::id, null, Entity::source),
        finish(relations, Relation::id, null, Relation::source),
        finish(sqls, SqlAccess::id, SqlAccess::withId, SqlAccess::source),
        finish(endpoints, Endpoint::id, Endpoint::withId, Endpoint::source),
        finish(calls, Call::id, Call::withId, Call::source),
        finish(messaging, Messaging::id, Messaging::withId, Messaging::source),
        finish(diags, Diagnostic::id, Diagnostic::withId, Diagnostic::source));
  }

  /** Supprime les doublons exacts, suffixe les collisions d'id, trie par id. */
  static <T> List<T> finish(List<T> items, Function<T, String> id, BiFunction<T, String, T> withId,
      Function<T, Source> source) {
    Map<String, List<T>> groups = new LinkedHashMap<>();
    for (T t : dedupExact(items)) {
      groups.computeIfAbsent(id.apply(t), k -> new ArrayList<>()).add(t);
    }
    List<T> out = new ArrayList<>();
    for (Map.Entry<String, List<T>> g : groups.entrySet()) {
      List<T> members = g.getValue();
      if (members.size() == 1 || withId == null) {
        out.addAll(members);
        continue;
      }
      members.sort(Comparator.<T, String>comparing(t -> sourceKey(source.apply(t)))
          .thenComparing(CanonicalJson::compact));
      for (int i = 0; i < members.size(); i++) {
        out.add(i == 0 ? members.get(i) : withId.apply(members.get(i), g.getKey() + "~" + (i + 1)));
      }
    }
    out.sort(Comparator.comparing(id).thenComparing(CanonicalJson::compact));
    return out;
  }

  private static String sourceKey(Source s) {
    return s == null ? "" : s.sortKey();
  }

  private static <T> List<T> dedupExact(List<T> items) {
    Set<String> seen = new java.util.HashSet<>();
    List<T> out = new ArrayList<>();
    for (T t : items) {
      if (seen.add(CanonicalJson.compact(t))) {
        out.add(t);
      }
    }
    return out;
  }

  private static <T> List<T> sorted(List<T> items, Comparator<T> cmp) {
    if (items == null) {
      return List.of();
    }
    List<T> out = new ArrayList<>(items);
    out.sort(cmp);
    return out;
  }

  static boolean same(Object a, Object b) {
    return Objects.equals(a, b);
  }
}
