package fr.cafat.meta.extract.entities;

import static fr.cafat.meta.spoon.Annotations.JPA;

import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.model.Column;
import fr.cafat.meta.model.SecondaryTable;
import fr.cafat.meta.spoon.Annotations;
import fr.cafat.meta.spoon.Types;
import java.beans.Introspector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.reference.CtTypeReference;

/**
 * Entités, embeddables et mapped superclasses du modèle, avec leurs colonnes propres. L'héritage
 * ({@link InheritanceResolver}) et les colonnes de jointure (relations) sont traités ensuite.
 */
public final class EntityExtractor {

  static final Set<String> ASSOCIATIONS = Set.of("OneToOne", "ManyToOne", "OneToMany", "ManyToMany",
      "ElementCollection");
  private static final int MAX_EMBED_DEPTH = 6;

  private final ExtractionContext ctx;
  private final NamingStrategy naming;

  public EntityExtractor(ExtractionContext ctx) {
    this.ctx = ctx;
    this.naming = ctx.naming();
  }

  /** Classes persistantes dans l'ordre des noms qualifiés, indexées par nom qualifié. */
  public Map<String, EntityDraft> extract() {
    Map<String, EntityDraft> drafts = new LinkedHashMap<>();
    for (CtType<?> t : ctx.types().all()) {
      String kind = kindOf(t);
      if (kind == null) {
        continue;
      }
      EntityDraft d = new EntityDraft(t, ctx.entityId(t.getQualifiedName()), kind, entityName(t, kind),
          ctx.source(t));
      readTypeLevel(d);
      readAttributes(d);
      drafts.put(d.className, d);
    }
    return drafts;
  }

  static String kindOf(CtType<?> t) {
    if (t.isInterface() || t.isAnnotationType() || t.isEnum()) {
      return null;
    }
    if (Annotations.has(t, JPA, "Entity")) {
      return EntityDraft.ENTITY;
    }
    if (Annotations.has(t, JPA, "Embeddable")) {
      return EntityDraft.EMBEDDABLE;
    }
    if (Annotations.has(t, JPA, "MappedSuperclass")) {
      return EntityDraft.MAPPED_SUPERCLASS;
    }
    return null;
  }

  private String entityName(CtType<?> t, String kind) {
    if (!EntityDraft.ENTITY.equals(kind)) {
      return null;
    }
    String declared = str(Annotations.find(t, JPA, "Entity"), "name");
    return declared != null ? declared : t.getSimpleName();
  }

  private void readTypeLevel(EntityDraft d) {
    CtType<?> t = d.type;
    if (d.isEntity()) {
      CtAnnotation<?> table = Annotations.find(t, JPA, "Table");
      d.table = naming.explicit(str(table, "name"));
      d.schema = naming.explicit(str(table, "schema"));
      if (d.table == null) {
        d.table = naming.table(d.name);
      }
    }
    CtAnnotation<?> inh = Annotations.find(t, JPA, "Inheritance");
    if (inh != null) {
      String s = Annotations.enumConstant(Annotations.value(inh, "strategy"));
      d.declaredStrategy = s == null ? "SINGLE_TABLE" : s;
    }
    d.discriminatorColumn = Annotations.find(t, JPA, "DiscriminatorColumn");
    d.discriminatorValue = str(Annotations.find(t, JPA, "DiscriminatorValue"), "value");
    d.pkJoinColumns = joinColumnNames(repeated(t, "PrimaryKeyJoinColumn", "PrimaryKeyJoinColumns"));
    for (CtAnnotation<?> st : repeated(t, "SecondaryTable", "SecondaryTables")) {
      String name = naming.explicit(str(st, "name"));
      if (name != null) {
        List<String> jc = joinColumnNames(Annotations.nested(st, "pkJoinColumns"));
        d.secondaryTables.add(new SecondaryTable(naming.explicit(str(st, "schema")), name,
            jc.isEmpty() ? null : jc));
      }
    }
    for (CtAnnotation<?> ao : repeated(t, "AttributeOverride", "AttributeOverrides")) {
      String name = str(ao, "name");
      List<CtAnnotation<?>> col = Annotations.nested(ao, "column");
      if (name != null && !col.isEmpty()) {
        d.classOverrides.put(name, col.get(0));
      }
    }
  }

  /** Annotation répétable, directe ou dans son conteneur, dans l'ordre du source. */
  public static List<CtAnnotation<?>> repeated(CtElement e, String single, String container) {
    List<CtAnnotation<?>> out = new ArrayList<>(Annotations.findAll(e, JPA, single));
    for (CtAnnotation<?> c : Annotations.findAll(e, JPA, container)) {
      out.addAll(Annotations.nested(c, "value"));
    }
    return out;
  }

  /** Noms déclarés (attribut {@code name}) d'une liste de colonnes de jointure. */
  List<String> joinColumnNames(List<CtAnnotation<?>> annotations) {
    List<String> out = new ArrayList<>();
    for (CtAnnotation<?> a : annotations) {
      String n = naming.explicit(str(a, "name"));
      if (n != null) {
        out.add(n);
      }
    }
    return out;
  }

  // ---------------------------------------------------------------- attributs

  /** Attribut persistant candidat : champ ou accesseur. */
  record Attr(CtElement element, String name, CtTypeReference<?> type) {
  }

  private void readAttributes(EntityDraft d) {
    boolean property = usesPropertyAccess(d.type);
    for (Attr a : attributes(d.type, property)) {
      readAttribute(d, a, "", Map.of(), 0, d.ownColumns);
    }
  }

  private void readAttribute(EntityDraft d, Attr a, String prefix, Map<String, CtAnnotation<?>> overrides,
      int depth, List<Column> out) {
    String path = prefix + a.name;
    String assoc = associationOf(a.element);
    if (assoc != null) {
      if (prefix.isEmpty()) {
        d.associations.add(new EntityDraft.Association(a.element, a.name, a.type, assoc,
            Annotations.find(a.element, JPA, assoc), null));
      }
      return;
    }
    boolean embeddedId = Annotations.has(a.element, JPA, "EmbeddedId");
    CtType<?> target = ctx.types().resolve(a.type);
    boolean embedded = embeddedId || Annotations.has(a.element, JPA, "Embedded")
        || (target != null && Annotations.has(target, JPA, "Embeddable"));
    if (embedded) {
      if (target == null || depth >= MAX_EMBED_DEPTH) {
        ctx.diagnostics().warning("EMBEDDABLE_NOT_FOUND", "embeddable " + Types.render(a.type)
            + " introuvable dans le dépôt : colonnes de " + d.className + "." + path + " inconnues",
            ctx.source(a.element));
        return;
      }
      Map<String, CtAnnotation<?>> nested = new LinkedHashMap<>(overrides);
      for (CtAnnotation<?> ao : repeated(a.element, "AttributeOverride", "AttributeOverrides")) {
        String name = str(ao, "name");
        List<CtAnnotation<?>> col = Annotations.nested(ao, "column");
        if (name != null && !col.isEmpty()) {
          nested.putIfAbsent(path + "." + name, col.get(0));
        }
      }
      for (Attr inner : attributes(target, usesPropertyAccess(target))) {
        int before = out.size();
        readAttribute(d, inner, path + ".", nested, depth + 1, out);
        if (embeddedId) {
          for (int i = before; i < out.size(); i++) {
            out.set(i, asPk(out.get(i)));
          }
        }
      }
      return;
    }
    out.add(basic(a, path, overrides.get(path)));
  }

  private static Column asPk(Column c) {
    return new Column(c.name(), c.field(), c.javaType(), c.table(), "id", true, false, c.unique(),
        c.length(), c.precision(), c.scale(), c.generated(), c.enumValues(), c.enumStorage(), c.references(),
        c.inheritedFrom());
  }

  /** Colonne simple (id, version ou basic). */
  Column basic(Attr a, String path, CtAnnotation<?> override) {
    CtElement el = a.element;
    CtAnnotation<?> col = override != null ? override : Annotations.find(el, JPA, "Column");
    String name = naming.explicit(str(col, "name"));
    if (name == null) {
      name = naming.implicit(a.name);
    }
    boolean pk = Annotations.has(el, JPA, "Id");
    String kind = pk ? "id" : Annotations.has(el, JPA, "Version") ? "version" : "basic";
    Boolean nullable = bool(col, "nullable");
    if (nullable == null) {
      Boolean optional = bool(Annotations.find(el, JPA, "Basic"), "optional");
      nullable = optional;
    }
    if (pk) {
      nullable = false;
    }
    String generated = null;
    CtAnnotation<?> gv = Annotations.find(el, JPA, "GeneratedValue");
    if (gv != null) {
      String s = Annotations.enumConstant(Annotations.value(gv, "strategy"));
      generated = s == null ? "AUTO" : s;
    } else if (Annotations.has(el, Annotations.ANY, "UuidGenerator")) {
      generated = "UUID";
    }
    List<String> enumValues = null;
    String enumStorage = null;
    CtType<?> t = ctx.types().resolve(a.type);
    if (t instanceof CtEnum<?> en) {
      enumValues = new ArrayList<>();
      for (CtEnumValue<?> v : en.getEnumValues()) {
        enumValues.add(v.getSimpleName());
      }
    }
    CtAnnotation<?> enumerated = Annotations.find(el, JPA, "Enumerated");
    if (enumerated != null) {
      String s = Annotations.enumConstant(Annotations.value(enumerated, "value"));
      enumStorage = s == null ? "ORDINAL" : s;
    }
    return new Column(name, path, Types.render(a.type), naming.explicit(str(col, "table")), kind, pk, nullable,
        bool(col, "unique"), integer(col, "length"), integer(col, "precision"), integer(col, "scale"),
        generated, enumValues, enumStorage, null, null);
  }

  static String associationOf(CtElement el) {
    for (String a : List.of("OneToOne", "ManyToOne", "OneToMany", "ManyToMany", "ElementCollection")) {
      if (Annotations.has(el, JPA, a)) {
        return a;
      }
    }
    return null;
  }

  /** Accès par propriété si {@code @Access(PROPERTY)} ou si l'identifiant est sur un accesseur. */
  boolean usesPropertyAccess(CtType<?> type) {
    CtAnnotation<?> access = Annotations.find(type, JPA, "Access");
    if (access != null) {
      return "PROPERTY".equals(Annotations.enumConstant(Annotations.value(access, "value")));
    }
    int guard = 0;
    for (CtType<?> c = type; c != null && guard++ < 20; c = superType(c)) {
      for (CtField<?> f : c.getFields()) {
        if (Annotations.has(f, JPA, "Id") || Annotations.has(f, JPA, "EmbeddedId")) {
          return false;
        }
      }
      for (CtMethod<?> m : c.getMethods()) {
        if (Annotations.has(m, JPA, "Id") || Annotations.has(m, JPA, "EmbeddedId")) {
          return true;
        }
      }
    }
    if (Annotations.has(type, JPA, "Embeddable")) {
      boolean fieldMapping = false;
      for (CtField<?> f : type.getFields()) {
        if (isPersistentField(f)) {
          fieldMapping = true;
        }
      }
      if (!fieldMapping) {
        return true;
      }
    }
    return false;
  }

  private CtType<?> superType(CtType<?> c) {
    CtTypeReference<?> s = c.getSuperclass();
    return s == null ? null : ctx.types().resolve(s);
  }

  /** Attributs persistants déclarés par la classe, dans l'ordre du source. */
  List<Attr> attributes(CtType<?> type, boolean property) {
    List<Attr> out = new ArrayList<>();
    if (!property) {
      for (CtField<?> f : type.getFields()) {
        if (isPersistentField(f)) {
          out.add(new Attr(f, f.getSimpleName(), f.getType()));
        }
      }
      return out;
    }
    List<CtMethod<?>> methods = new ArrayList<>(type.getMethods());
    methods.sort((a, b) -> Integer.compare(fr.cafat.meta.spoon.Provenance.offset(a),
        fr.cafat.meta.spoon.Provenance.offset(b)));
    for (CtMethod<?> m : methods) {
      String n = m.getSimpleName();
      String prop = null;
      if (n.startsWith("get") && n.length() > 3) {
        prop = n.substring(3);
      } else if (n.startsWith("is") && n.length() > 2) {
        prop = n.substring(2);
      }
      if (prop == null || !m.getParameters().isEmpty() || m.hasModifier(ModifierKind.STATIC)
          || Types.isVoid(m.getType()) || Annotations.has(m, Annotations.ANY, "Transient")) {
        continue;
      }
      out.add(new Attr(m, Introspector.decapitalize(prop), m.getType()));
    }
    return out;
  }

  static boolean isPersistentField(CtField<?> f) {
    return !f.hasModifier(ModifierKind.STATIC) && !f.hasModifier(ModifierKind.TRANSIENT)
        && !Annotations.has(f, Annotations.ANY, "Transient") && !Annotations.has(f, Annotations.ANY, "Formula");
  }

  // ---------------------------------------------------------------- valeurs d'annotation

  String str(CtAnnotation<?> a, String key) {
    return ctx.str(a, key);
  }

  Integer integer(CtAnnotation<?> a, String key) {
    return ctx.integer(a, key);
  }

  static Boolean bool(CtAnnotation<?> a, String key) {
    return a == null ? null : Annotations.bool(a, key);
  }
}
