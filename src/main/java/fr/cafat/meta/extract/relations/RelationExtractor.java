package fr.cafat.meta.extract.relations;

import static fr.cafat.meta.spoon.Annotations.JPA;

import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.extract.entities.EntityDraft;
import fr.cafat.meta.extract.entities.EntityDraft.Association;
import fr.cafat.meta.extract.entities.EntityExtractor;
import fr.cafat.meta.extract.entities.InheritanceResolver;
import fr.cafat.meta.extract.entities.NamingStrategy;
import fr.cafat.meta.model.Column;
import fr.cafat.meta.model.Relation;
import fr.cafat.meta.model.TableRef;
import fr.cafat.meta.spoon.Annotations;
import fr.cafat.meta.spoon.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

/**
 * Relations JPA. À appeler après {@link InheritanceResolver#link()} (tables et clés primaires
 * effectives connues) et avant {@link InheritanceResolver#propagate()} : les colonnes de clé
 * étrangère des {@code ToOne} propriétaires sont ajoutées aux colonnes propres du porteur.
 *
 * <p>Les relations d'une mapped superclass sont émises sur chaque entité qui en hérite (avec
 * {@code inherited_from}) ; celles d'un embeddable sont émises avec l'embeddable pour origine.
 */
public final class RelationExtractor {

  private static final Map<String, String> TYPES = Map.of("OneToOne", "ONE_TO_ONE", "ManyToOne", "MANY_TO_ONE",
      "OneToMany", "ONE_TO_MANY", "ManyToMany", "MANY_TO_MANY", "ElementCollection", "ELEMENT_COLLECTION");

  private final ExtractionContext ctx;
  private final NamingStrategy naming;
  private final Map<String, EntityDraft> drafts;
  private final Set<Association> declared = Collections.newSetFromMap(new IdentityHashMap<>());

  public RelationExtractor(ExtractionContext ctx, Map<String, EntityDraft> drafts) {
    this.ctx = ctx;
    this.naming = ctx.naming();
    this.drafts = drafts;
  }

  /** Jointure calculée d'une association, du point de vue d'une entité porteuse. */
  private record Join(List<String> columns, List<String> inverseColumns, TableRef table) {
  }

  public List<Relation> extract() {
    List<Relation> out = new ArrayList<>();
    for (EntityDraft d : drafts.values()) {
      if (EntityDraft.MAPPED_SUPERCLASS.equals(d.kind)) {
        for (Association a : d.associations()) {
          declare(d, d, a);
        }
        continue;
      }
      for (Association a : d.associations()) {
        out.add(relation(d, d, a, null));
      }
      if (!d.isEntity()) {
        continue;
      }
      for (EntityDraft p = d.parentDraft; p != null && !p.isEntity(); p = p.parentDraft) {
        for (Association a : p.associations()) {
          out.add(relation(d, p, a, p.id));
        }
      }
    }
    return out;
  }

  private Relation relation(EntityDraft from, EntityDraft holder, Association a, String inheritedFrom) {
    declare(from, holder, a);
    CtAnnotation<?> m = a.mapping();
    String type = TYPES.get(a.annotation());
    CtTypeReference<?> target = targetOf(a);
    EntityDraft targetDraft = draftOf(target);
    String mappedBy = ctx.str(m, "mappedBy");
    boolean owning = mappedBy == null;
    Join join;
    if (owning) {
      join = owningJoin(from, a, targetDraft);
    } else {
      join = inverseJoin(from, a, targetDraft, mappedBy);
    }
    return new Relation(from.id + "." + a.field(), from.id, targetDraft == null ? null : targetDraft.id,
        target == null ? null : Types.baseName(target), a.field(), type, owning, mappedBy, join.columns,
        join.inverseColumns, join.table, optional(a), inheritedFrom, ctx.source(a.element()));
  }

  /**
   * Première rencontre d'une association : diagnostic de cible et colonnes de clé étrangère
   * ajoutées une seule fois au porteur (la mapped superclass le cas échéant).
   */
  private void declare(EntityDraft from, EntityDraft holder, Association a) {
    if (!declared.add(a)) {
      return;
    }
    CtTypeReference<?> target = targetOf(a);
    EntityDraft targetDraft = draftOf(target);
    boolean element = "ElementCollection".equals(a.annotation());
    if (targetDraft == null && !(element && isBasic(target))) {
      ctx.diagnostics().warning("RELATION_TARGET_NOT_FOUND", "cible " + (target == null ? "?" : Types.render(target))
          + " de " + holder.className + "." + a.field() + " absente du dépôt ou non persistante",
          ctx.source(a.element()));
    }
    boolean toOne = "ManyToOne".equals(a.annotation()) || "OneToOne".equals(a.annotation());
    if (toOne && ctx.str(a.mapping(), "mappedBy") == null
        && Annotations.find(a.element(), JPA, "JoinTable") == null) {
      addForeignKey(holder, a, targetDraft);
    }
  }

  private void addForeignKey(EntityDraft holder, Association a, EntityDraft targetDraft) {
    List<CtAnnotation<?>> jcs = EntityExtractor.repeated(a.element(), "JoinColumn", "JoinColumns");
    List<String> names = toOneColumns(a, jcs, targetDraft);
    boolean pk = Annotations.has(a.element(), JPA, "Id") || Annotations.has(a.element(), JPA, "MapsId");
    Boolean optional = Annotations.bool(a.mapping(), "optional");
    for (int i = 0; i < Math.max(1, names == null ? 1 : names.size()); i++) {
      CtAnnotation<?> jc = i < jcs.size() ? jcs.get(i) : null;
      Boolean nullable = Annotations.bool(jc, "nullable");
      if (nullable == null && Boolean.FALSE.equals(optional)) {
        nullable = false;
      }
      if (pk) {
        nullable = false;
      }
      String name = names == null ? null : names.get(i);
      holder.ownColumns().add(new Column(name, a.field(), Types.render(a.type()), naming.explicit(ctx.str(jc, "table")),
          "join", pk, nullable, Annotations.bool(jc, "unique"), null, null, null, null, null, null,
          targetDraft == null ? null : targetDraft.id, null));
    }
  }

  /** Colonnes d'une clé étrangère {@code ToOne} : déclarées, sinon attribut + "_" + colonne de PK cible. */
  private List<String> toOneColumns(Association a, List<CtAnnotation<?>> jcs, EntityDraft targetDraft) {
    List<Column> targetPk = InheritanceResolver.pkColumnsOf(targetDraft);
    List<String> out = new ArrayList<>();
    int n = jcs.isEmpty() ? targetPk.size() : jcs.size();
    for (int i = 0; i < n; i++) {
      CtAnnotation<?> jc = i < jcs.size() ? jcs.get(i) : null;
      String name = naming.explicit(ctx.str(jc, "name"));
      if (name == null) {
        String ref = ctx.str(jc, "referencedColumnName");
        ref = ref != null ? naming.explicit(ref) : i < targetPk.size() ? targetPk.get(i).name() : null;
        name = naming.joinColumn(a.field(), ref);
      }
      out.add(name);
    }
    return out.isEmpty() || out.contains(null) ? null : out;
  }

  private Join owningJoin(EntityDraft from, Association a, EntityDraft targetDraft) {
    String ann = a.annotation();
    CtAnnotation<?> joinTable = Annotations.find(a.element(), JPA, "JoinTable");
    List<CtAnnotation<?>> jcs = EntityExtractor.repeated(a.element(), "JoinColumn", "JoinColumns");
    if ("ElementCollection".equals(ann)) {
      CtAnnotation<?> ct = Annotations.find(a.element(), JPA, "CollectionTable");
      String name = naming.explicit(ctx.str(ct, "name"));
      if (name == null) {
        name = from.name == null ? null : naming.collectionTable(from.name, a.field());
      }
      List<CtAnnotation<?>> keys = ct == null ? jcs : Annotations.nested(ct, "joinColumns");
      return new Join(ownerKey(from, keys, entityNaming(from)), null,
          new TableRef(naming.explicit(ctx.str(ct, "schema")), name));
    }
    boolean toOne = "ManyToOne".equals(ann) || "OneToOne".equals(ann);
    if (toOne && joinTable == null) {
      return new Join(toOneColumns(a, jcs, targetDraft), null, null);
    }
    if ("OneToMany".equals(ann) && joinTable == null && !jcs.isEmpty()) {
      // Clé étrangère portée par la table cible.
      return new Join(ownerKey(from, jcs, a.field()), null, null);
    }
    String name = naming.explicit(ctx.str(joinTable, "name"));
    if (name == null) {
      name = naming.joinTable(from.table, a.field(), targetDraft == null ? null : targetDraft.table);
    }
    Association inverse = inverseOf(targetDraft, a.field());
    List<String> cols = ownerKey(from, Annotations.nested(joinTable, "joinColumns"),
        inverse != null ? inverse.field() : entityNaming(from));
    List<String> inv = ownerKey(targetDraft, Annotations.nested(joinTable, "inverseJoinColumns"), a.field());
    return new Join(cols, inv, new TableRef(naming.explicit(ctx.str(joinTable, "schema")), name));
  }

  /** Nom logique d'une entité pour les colonnes implicites (nom JPA, sinon nom simple). */
  private static String entityNaming(EntityDraft d) {
    return d == null ? null : d.name != null ? d.name : d.type.getSimpleName();
  }

  /**
   * Colonnes référençant la clé primaire de {@code owner} : déclarées, sinon {@code prefix} + "_" +
   * colonne de PK.
   */
  private List<String> ownerKey(EntityDraft owner, List<CtAnnotation<?>> declaredColumns, String prefix) {
    List<Column> pk = InheritanceResolver.pkColumnsOf(owner);
    List<String> out = new ArrayList<>();
    int n = declaredColumns.isEmpty() ? pk.size() : declaredColumns.size();
    for (int i = 0; i < n; i++) {
      CtAnnotation<?> jc = i < declaredColumns.size() ? declaredColumns.get(i) : null;
      String name = naming.explicit(ctx.str(jc, "name"));
      if (name == null && prefix != null) {
        String ref = ctx.str(jc, "referencedColumnName");
        ref = ref != null ? naming.explicit(ref) : i < pk.size() ? pk.get(i).name() : null;
        name = naming.joinColumn(prefix, ref);
      }
      out.add(name);
    }
    return out.isEmpty() || out.contains(null) ? null : out;
  }

  /** Côté inverse : jointure recopiée du côté propriétaire (colonnes permutées pour une table de jointure). */
  private Join inverseJoin(EntityDraft from, Association a, EntityDraft targetDraft, String mappedBy) {
    EntityDraft owner = targetDraft;
    Association owning = null;
    for (EntityDraft d = targetDraft; d != null && owning == null; d = d.parentDraft) {
      for (Association o : d.associations()) {
        if (o.field().equals(mappedBy)) {
          owning = o;
          break;
        }
      }
    }
    if (owning == null || owner == null) {
      return new Join(null, null, null);
    }
    Join j = owningJoin(owner, owning, from);
    if (j.table != null) {
      return new Join(j.inverseColumns, j.columns, j.table);
    }
    return new Join(j.columns, null, null);
  }

  /** Association de la cible qui déclare {@code mappedBy = field}. */
  private Association inverseOf(EntityDraft target, String field) {
    for (EntityDraft d = target; d != null; d = d.parentDraft) {
      for (Association o : d.associations()) {
        if (field.equals(ctx.str(o.mapping(), "mappedBy"))) {
          return o;
        }
      }
    }
    return null;
  }

  private Boolean optional(Association a) {
    Boolean optional = Annotations.bool(a.mapping(), "optional");
    if (optional != null) {
      return optional;
    }
    for (CtAnnotation<?> jc : EntityExtractor.repeated(a.element(), "JoinColumn", "JoinColumns")) {
      if (Boolean.FALSE.equals(Annotations.bool(jc, "nullable"))) {
        return false;
      }
    }
    return null;
  }

  private CtTypeReference<?> targetOf(Association a) {
    boolean element = "ElementCollection".equals(a.annotation());
    CtTypeReference<?> declaredTarget = Annotations.classLiteral(
        Annotations.value(a.mapping(), element ? "targetClass" : "targetEntity"));
    if (declaredTarget != null) {
      return declaredTarget;
    }
    boolean many = element || "OneToMany".equals(a.annotation()) || "ManyToMany".equals(a.annotation());
    return many ? Types.elementType(a.type()) : a.type();
  }

  private EntityDraft draftOf(CtTypeReference<?> ref) {
    CtType<?> t = ref == null ? null : ctx.types().resolve(ref);
    return t == null ? null : drafts.get(t.getQualifiedName());
  }

  /** Type valeur sans entité : primitif, JDK, Kotlin ou énumération du dépôt. */
  private boolean isBasic(CtTypeReference<?> ref) {
    if (ref == null) {
      return false;
    }
    if (ref.isPrimitive()) {
      return true;
    }
    String qn = ref.getQualifiedName();
    if (qn.startsWith("java.") || qn.startsWith("kotlin.") || !qn.contains(".")) {
      return true;
    }
    CtType<?> t = ctx.types().resolve(ref);
    return t != null && t.isEnum();
  }
}
