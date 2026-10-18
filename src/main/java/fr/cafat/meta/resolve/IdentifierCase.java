package fr.cafat.meta.resolve;

import fr.cafat.meta.model.Column;
import fr.cafat.meta.model.Datasource;
import fr.cafat.meta.model.Discriminator;
import fr.cafat.meta.model.Entity;
import fr.cafat.meta.model.Inheritance;
import fr.cafat.meta.model.Relation;
import fr.cafat.meta.model.SecondaryTable;
import fr.cafat.meta.model.SqlAccess;
import fr.cafat.meta.model.SqlTable;
import fr.cafat.meta.model.TableRef;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Noms de tables, schémas et colonnes ramenés à la casse que leur base leur donne quand ils sont
 * écrits sans guillemets : minuscules en PostgreSQL, majuscules en DB2. Les noms se comparent alors
 * directement aux catalogues des bases et entre entités et SQL d'une même base. Autres bases, ou
 * source inconnue : noms inchangés.
 *
 * <p>Limite : un nom entre guillemets dans le code (casse imposée) est aussi ramené, les guillemets
 * étant retirés à l'extraction.
 */
public final class IdentifierCase {

  private IdentifierCase() {
  }

  /**
   * Nom dans la casse de la base.
   *
   * @param kind type de la source ({@link Datasource#kind()}) ; null accepté
   * @param name nom de table, schéma ou colonne ; null accepté
   * @return nom en minuscules (postgresql), en majuscules (db2), sinon inchangé
   */
  public static String fold(String kind, String name) {
    if (name == null || kind == null) {
      return name;
    }
    return switch (kind) {
      case "postgresql" -> name.toLowerCase(Locale.ROOT);
      case "db2" -> name.toUpperCase(Locale.ROOT);
      default -> name;
    };
  }

  /**
   * Entités, relations et accès SQL dans la casse de leur source.
   *
   * @param entities entités rattachées
   * @param relations relations ; la source est celle de l'entité de départ
   * @param sql accès SQL rattachés
   * @param datasources sources par id
   * @return copies, dans le même ordre
   */
  public static Result apply(List<Entity> entities, List<Relation> relations, List<SqlAccess> sql,
      Map<String, Datasource> datasources) {
    Map<String, String> kindOfEntity = new HashMap<>();
    for (Entity e : entities) {
      kindOfEntity.put(e.id(), kind(datasources, e.datasource()));
    }
    return new Result(
        entities.stream().map(e -> entity(e, kindOfEntity.get(e.id()))).toList(),
        relations.stream().map(r -> relation(r, kindOfEntity.get(r.fromEntity()))).toList(),
        sql.stream().map(a -> sql(a, kind(datasources, a.datasource()))).toList());
  }

  /** Entités, relations et accès SQL dans la casse de leur source. */
  public record Result(List<Entity> entities, List<Relation> relations, List<SqlAccess> sql) {
  }

  private static String kind(Map<String, Datasource> datasources, String id) {
    Datasource d = id == null ? null : datasources.get(id);
    return d == null ? null : d.kind();
  }

  private static List<String> fold(String kind, List<String> names) {
    return names == null ? null : names.stream().map(n -> fold(kind, n)).toList();
  }

  private static Entity entity(Entity e, String kind) {
    if (kind == null) {
      return e;
    }
    Inheritance inh = e.inheritance();
    if (inh != null) {
      Discriminator d = inh.discriminator();
      inh = new Inheritance(inh.strategy(), inh.root(),
          d == null ? null : new Discriminator(fold(kind, d.column()), d.type(), d.value()),
          fold(kind, inh.joinColumns()));
    }
    List<SecondaryTable> secondary = e.secondaryTables() == null ? null : e.secondaryTables().stream()
        .map(t -> new SecondaryTable(fold(kind, t.schema()), fold(kind, t.name()), fold(kind, t.joinColumns())))
        .toList();
    List<Column> columns = e.columns() == null ? null : e.columns().stream()
        .map(c -> new Column(fold(kind, c.name()), c.field(), c.javaType(), fold(kind, c.table()), c.kind(), c.pk(),
            c.nullable(), c.unique(), c.length(), c.precision(), c.scale(), c.generated(), c.enumValues(),
            c.enumStorage(), c.references(), c.inheritedFrom()))
        .toList();
    return new Entity(e.id(), e.className(), e.name(), e.kind(), e.datasource(), fold(kind, e.schema()),
        fold(kind, e.table()), e.isView(), e.parent(), inh, secondary, e.source(), columns);
  }

  private static Relation relation(Relation r, String kind) {
    if (kind == null) {
      return r;
    }
    TableRef jt = r.joinTable() == null ? null
        : new TableRef(fold(kind, r.joinTable().schema()), fold(kind, r.joinTable().name()));
    return new Relation(r.id(), r.fromEntity(), r.toEntity(), r.toClass(), r.field(), r.type(), r.owningSide(),
        r.mappedBy(), fold(kind, r.joinColumns()), fold(kind, r.inverseJoinColumns()), jt, r.optional(),
        r.inheritedFrom(), r.source());
  }

  private static SqlAccess sql(SqlAccess a, String kind) {
    if (kind == null) {
      return a;
    }
    List<SqlTable> tables = a.tables().stream()
        .map(t -> new SqlTable(fold(kind, t.schema()), fold(kind, t.name()), t.access())).toList();
    return new SqlAccess(a.id(), a.origin(), a.datasource(), a.sql(), tables, a.parsed(), a.caller(), a.source());
  }
}
