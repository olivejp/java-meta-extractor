package fr.cafat.meta.extract.entities;

import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.model.Column;
import fr.cafat.meta.model.Discriminator;
import fr.cafat.meta.model.Inheritance;
import fr.cafat.meta.spoon.Annotations;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.reference.CtTypeReference;

/**
 * Héritage en deux temps. {@link #link} relie chaque classe persistante à son ancêtre persistant,
 * calcule la stratégie, la racine, le discriminateur et la table effective. {@link #propagate}
 * recopie ensuite les colonnes héritées (après l'ajout des colonnes de jointure des relations).
 */
public final class InheritanceResolver {

  private static final int MAX_DEPTH = 30;

  private final ExtractionContext ctx;
  private final NamingStrategy naming;
  private final Map<String, EntityDraft> drafts;
  private final Set<EntityDraft> linked = new HashSet<>();

  public InheritanceResolver(ExtractionContext ctx, Map<String, EntityDraft> drafts) {
    this.ctx = ctx;
    this.naming = ctx.naming();
    this.drafts = drafts;
  }

  // ---------------------------------------------------------------- link

  public void link() {
    for (EntityDraft d : drafts.values()) {
      findParent(d);
    }
    for (EntityDraft d : drafts.values()) {
      link(d);
    }
  }

  /** Ancêtre persistant le plus proche ; les classes intermédiaires non persistantes sont traversées. */
  private void findParent(EntityDraft d) {
    CtTypeReference<?> sup = d.type.getSuperclass();
    for (int guard = 0; sup != null && guard < MAX_DEPTH; guard++) {
      var st = ctx.types().resolve(sup);
      if (st == null) {
        String qn = sup.getQualifiedName();
        if (!"java.lang.Object".equals(qn) && !"Object".equals(qn) && !"kotlin.Any".equals(qn)) {
          d.parent = qn;
          ctx.diagnostics().warning("PARENT_NOT_FOUND", "classe parente " + qn + " de " + d.className
              + " absente du dépôt : colonnes héritées inconnues", d.source);
        }
        return;
      }
      EntityDraft p = drafts.get(st.getQualifiedName());
      if (p != null) {
        d.parent = p.id;
        d.parentDraft = p;
        return;
      }
      sup = st.getSuperclass();
    }
  }

  static EntityDraft entityParent(EntityDraft d) {
    for (EntityDraft p = d.parentDraft; p != null; p = p.parentDraft) {
      if (p.isEntity()) {
        return p;
      }
    }
    return null;
  }

  private boolean hasEntityChildren(EntityDraft d) {
    for (EntityDraft e : drafts.values()) {
      if (e.isEntity() && entityParent(e) == d) {
        return true;
      }
    }
    return false;
  }

  private void link(EntityDraft d) {
    if (!d.isEntity() || !linked.add(d)) {
      return;
    }
    EntityDraft parent = entityParent(d);
    if (parent != null) {
      link(parent);
    }
    EntityDraft root = d;
    for (EntityDraft p = parent; p != null; p = entityParent(p)) {
      root = p;
    }
    if (root == d && d.declaredStrategy == null && !hasEntityChildren(d)) {
      return;
    }
    String strategy = root.declaredStrategy != null ? root.declaredStrategy : "SINGLE_TABLE";
    if ("SINGLE_TABLE".equals(strategy) && root != d) {
      d.table = root.table;
      d.schema = root.schema;
    }
    List<String> joinColumns = null;
    if ("JOINED".equals(strategy) && root != d) {
      joinColumns = !d.pkJoinColumns.isEmpty() ? d.pkJoinColumns : names(pkColumnsOf(parent));
      if (joinColumns.isEmpty()) {
        joinColumns = null;
      }
    }
    d.inheritance = new Inheritance(strategy, root.id, discriminator(d, root, strategy), joinColumns);
  }

  private Discriminator discriminator(EntityDraft d, EntityDraft root, String strategy) {
    CtAnnotation<?> dc = root.discriminatorColumn;
    if ("TABLE_PER_CLASS".equals(strategy) || ("JOINED".equals(strategy) && dc == null)) {
      return null;
    }
    String column = naming.explicit(str(dc, "name"));
    if (column == null) {
      column = naming.explicit("DTYPE");
    }
    String type = dc == null ? null : Annotations.enumConstant(Annotations.value(dc, "discriminatorType"));
    if (type == null) {
      type = "STRING";
    }
    String value = d.discriminatorValue;
    if (value == null && "STRING".equals(type) && !d.type.hasModifier(ModifierKind.ABSTRACT)) {
      value = d.name;
    }
    return new Discriminator(column, type, value);
  }

  /**
   * Colonnes de clé primaire d'une entité, en remontant la hiérarchie ; pour une sous-classe JOINED,
   * renommées selon ses colonnes de jointure.
   */
  public static List<Column> pkColumnsOf(EntityDraft e) {
    if (e == null) {
      return List.of();
    }
    List<Column> base = List.of();
    for (EntityDraft c = e; c != null; c = c.parentDraft) {
      List<Column> pks = c.ownColumns.stream().filter(Column::pk).toList();
      if (!pks.isEmpty()) {
        base = pks;
        break;
      }
    }
    Inheritance inh = e.inheritance;
    if (inh != null && "JOINED".equals(inh.strategy()) && inh.joinColumns() != null) {
      List<Column> renamed = new ArrayList<>();
      for (int i = 0; i < inh.joinColumns().size(); i++) {
        Column ref = i < base.size() ? base.get(i) : null;
        renamed.add(new Column(inh.joinColumns().get(i), ref == null ? null : ref.field(),
            ref == null ? null : ref.javaType(), e.table, "pk_join", true, false, null, null, null, null, null,
            null, null, null, null));
      }
      return renamed;
    }
    return base;
  }

  private static List<String> names(List<Column> columns) {
    List<String> out = new ArrayList<>();
    for (Column c : columns) {
      if (c.name() != null) {
        out.add(c.name());
      }
    }
    return out;
  }

  // ---------------------------------------------------------------- propagate

  public void propagate() {
    for (EntityDraft d : drafts.values()) {
      propagate(d);
    }
  }

  private void propagate(EntityDraft d) {
    if (d.propagated) {
      return;
    }
    EntityDraft p = d.parentDraft;
    if (p != null) {
      propagate(p);
    }
    String strategy = d.inheritance == null ? null : d.inheritance.strategy();
    String ownTable = d.isEntity() ? d.table : null;
    List<Column> out = new ArrayList<>();
    if (p != null) {
      for (Column c : p.columns()) {
        Column x = c.inheritedFrom() == null ? c.withInheritedFrom(p.id) : c;
        if (d.isEntity()) {
          if (!p.isEntity()) {
            x = x.table() == null ? x.withTable(ownTable) : x;
          } else if ("TABLE_PER_CLASS".equals(strategy)) {
            x = x.table() == null || x.table().equals(p.table) ? x.withTable(ownTable) : x;
          }
          x = override(x, d.classOverrides.get(x.field()));
        }
        out.add(x);
      }
    }
    if (d.isEntity() && "JOINED".equals(strategy) && p != null && d.inheritance.joinColumns() != null) {
      EntityDraft parent = entityParent(d);
      List<Column> parentPk = pkColumnsOf(parent);
      List<String> jc = d.inheritance.joinColumns();
      for (int i = 0; i < jc.size(); i++) {
        Column ref = i < parentPk.size() ? parentPk.get(i) : null;
        out.add(new Column(jc.get(i), ref == null ? null : ref.field(), ref == null ? null : ref.javaType(),
            ownTable, "pk_join", true, false, null, null, null, null, null, null, null,
            parent == null ? null : parent.id, null));
      }
    }
    for (Column c : d.ownColumns) {
      out.add(d.isEntity() && c.table() == null ? c.withTable(ownTable) : c);
    }
    if (d.isEntity() && d.inheritance != null && d.inheritance.root().equals(d.id)
        && d.inheritance.discriminator() != null) {
      Discriminator disc = d.inheritance.discriminator();
      out.add(new Column(disc.column(), null, null, ownTable, "discriminator", false,
          Annotations.bool(d.discriminatorColumn, "nullable"), null, integer(d.discriminatorColumn, "length"),
          null, null, null, null, null, null, null));
    }
    d.columns.clear();
    d.columns.addAll(uniquePk(out));
    d.propagated = true;
  }

  /** Surcharge {@code @AttributeOverride} de niveau classe appliquée à une colonne héritée. */
  private Column override(Column c, CtAnnotation<?> col) {
    if (col == null || c.inheritedFrom() == null) {
      return c;
    }
    String name = naming.explicit(str(col, "name"));
    Boolean nullable = Annotations.bool(col, "nullable");
    Boolean unique = Annotations.bool(col, "unique");
    Integer length = integer(col, "length");
    Integer precision = integer(col, "precision");
    Integer scale = integer(col, "scale");
    return new Column(name != null ? name : c.name(), c.field(), c.javaType(), c.table(), c.kind(), c.pk(),
        c.pk() ? Boolean.FALSE : nullable != null ? nullable : c.nullable(), unique != null ? unique : c.unique(),
        length != null ? length : c.length(), precision != null ? precision : c.precision(),
        scale != null ? scale : c.scale(), c.generated(), c.enumValues(), c.enumStorage(), c.references(),
        c.inheritedFrom());
  }

  /** Une clé primaire à une seule colonne dans sa table est unique. */
  private static List<Column> uniquePk(List<Column> columns) {
    Map<String, Integer> pkCount = new HashMap<>();
    for (Column c : columns) {
      if (c.pk()) {
        pkCount.merge(String.valueOf(c.table()), 1, Integer::sum);
      }
    }
    List<Column> out = new ArrayList<>();
    for (Column c : columns) {
      if (c.pk() && pkCount.get(String.valueOf(c.table())) == 1 && !Boolean.TRUE.equals(c.unique())) {
        c = new Column(c.name(), c.field(), c.javaType(), c.table(), c.kind(), true, false, true, c.length(),
            c.precision(), c.scale(), c.generated(), c.enumValues(), c.enumStorage(), c.references(),
            c.inheritedFrom());
      }
      out.add(c);
    }
    return out;
  }

  String str(CtAnnotation<?> a, String key) {
    return ctx.str(a, key);
  }

  Integer integer(CtAnnotation<?> a, String key) {
    return ctx.integer(a, key);
  }
}
