package fr.cafat.meta.extract.entities;

import fr.cafat.meta.model.Column;
import fr.cafat.meta.model.Entity;
import fr.cafat.meta.model.Inheritance;
import fr.cafat.meta.model.SecondaryTable;
import fr.cafat.meta.model.Source;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

/**
 * Classe persistante en cours de construction. Les extracteurs successifs (entités, héritage,
 * relations, sources de données) la complètent avant sa conversion en {@link Entity}.
 *
 * <p>Une colonne dont {@code table} vaut null appartient à la table principale de son porteur.
 */
public final class EntityDraft {

  public static final String ENTITY = "entity";
  public static final String EMBEDDABLE = "embeddable";
  public static final String MAPPED_SUPERCLASS = "mapped_superclass";

  /** Attribut d'association ({@code @OneToMany}…) en attente du traitement des relations. */
  public record Association(CtElement element, String field, CtTypeReference<?> type, String annotation,
      CtAnnotation<?> mapping, String inheritedFrom) {

    public Association inheritedFrom(String id) {
      return new Association(element, field, type, annotation, mapping, id);
    }
  }

  public final CtType<?> type;
  public final String className;
  public final String id;
  public final String kind;
  public final String name;
  public final Source source;

  public String schema;
  public String table;
  public Boolean isView;
  public String parent;
  public EntityDraft parentDraft;
  public Inheritance inheritance;
  public String datasource;

  String declaredStrategy;
  CtAnnotation<?> discriminatorColumn;
  String discriminatorValue;
  List<String> pkJoinColumns = List.of();
  final List<SecondaryTable> secondaryTables = new ArrayList<>();
  /** Surcharges de colonnes déclarées sur la classe (attributs hérités), par chemin d'attribut. */
  final Map<String, CtAnnotation<?>> classOverrides = new LinkedHashMap<>();
  /** Colonnes propres (déclarées par la classe elle-même). */
  final List<Column> ownColumns = new ArrayList<>();
  /** Colonnes effectives après héritage. */
  final List<Column> columns = new ArrayList<>();
  final List<Association> associations = new ArrayList<>();
  boolean propagated;

  EntityDraft(CtType<?> type, String id, String kind, String name, Source source) {
    this.type = type;
    this.className = type.getQualifiedName();
    this.id = id;
    this.kind = kind;
    this.name = name;
    this.source = source;
  }

  /** Applique le schéma par défaut de la source de données aux tables (principale, secondaires) sans schéma. */
  public void defaultSchema(String defaultSchema) {
    if (defaultSchema == null) {
      return;
    }
    if (schema == null && table != null) {
      schema = defaultSchema;
    }
    secondaryTables.replaceAll(t -> t.schema() == null
        ? new SecondaryTable(defaultSchema, t.name(), t.joinColumns()) : t);
  }

  public boolean isEntity() {
    return ENTITY.equals(kind);
  }

  public List<Column> ownColumns() {
    return ownColumns;
  }

  public List<Column> columns() {
    return propagated ? columns : ownColumns;
  }

  public List<Association> associations() {
    return associations;
  }

  public Entity toEntity() {
    List<Column> cols = new ArrayList<>(columns());
    cols.sort((a, b) -> a.sortKey().compareTo(b.sortKey()));
    List<SecondaryTable> secondary = new ArrayList<>(secondaryTables);
    secondary.sort((a, b) -> (a.schema() + "\u0000" + a.name()).compareTo(b.schema() + "\u0000" + b.name()));
    return new Entity(id, className, name, kind, datasource, schema, table, isView, parent, inheritance,
        secondary, source, cols);
  }
}
