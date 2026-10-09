package fr.cafat.meta.resolve;

import fr.cafat.meta.config.Config;
import fr.cafat.meta.config.DatasourceDetector.Detected;
import fr.cafat.meta.config.PersistenceUnit;
import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.extract.entities.EntityDraft;
import fr.cafat.meta.extract.sql.SqlAnalyzer;
import fr.cafat.meta.extract.sql.SqlExtractor;
import fr.cafat.meta.extract.sql.SqlExtractor.DatasourceHint;
import fr.cafat.meta.extract.sql.SqlExtractor.SqlDraft;
import fr.cafat.meta.model.Datasource;
import fr.cafat.meta.model.Relation;
import fr.cafat.meta.model.SqlAccess;
import fr.cafat.meta.model.SqlTable;
import fr.cafat.meta.model.TableRef;
import fr.cafat.meta.spoon.Annotations;
import fr.cafat.meta.spoon.Provenance;
import fr.cafat.meta.spoon.Types;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import spoon.reflect.code.CtComment;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * Rattachement des entités et des accès SQL à une source de données, puis schéma par défaut, vues
 * et entités non référencées.
 *
 * <p>Entité : classe listée dans une unité de persistance, sinon paquet scanné par une fabrique
 * d'EntityManager ({@code packages(..)}, {@code setPackagesToScan(..)}), sinon unique unité du
 * module qui n'exclut pas les classes non listées, sinon unique source de données ; à défaut null
 * et {@code DATASOURCE_AMBIGUOUS}.
 *
 * <p>Accès SQL : unité ({@code @PersistenceContext(unitName)}), bean qualifié, dépôt couvert par
 * {@code @EnableJpaRepositories}, mapper couvert par {@code @MapperScan}, entité porteuse (requête
 * nommée), bean du même nom que le champ, bean {@code @Primary} ou unique de la famille
 * (JdbcTemplate, EntityManagerFactory, SqlSessionFactory…) ; si la famille n'a aucun bean
 * déclaré (auto-configuration), DataSource {@code @Primary} ou unique ; enfin unique source. À
 * défaut null et {@code DATASOURCE_AMBIGUOUS}.
 *
 * <p>Bean Spring → source : {@code @ConfigurationProperties} dont le préfixe est celui d'une source
 * détectée, nom JNDI littéral, sinon paramètres et appels d'autres méthodes {@code @Bean} (une
 * seule source possible, sinon inconnu).
 *
 * <p>Ensuite : schéma par défaut de la source pour les tables sans schéma (entités, tables
 * secondaires, tables de jointure, SQL) ; {@code is_view} vrai si le schéma est un schéma de vues,
 * si le nom de table l'indique ({@code V_*}, {@code VW_*}, {@code *_V}, {@code *_VW}) ou si un
 * commentaire de la classe parle de « vue » ou « view » ; sinon inchangé (null). Diagnostic
 * {@code ENTITY_UNREFERENCED} (info) pour une entité jamais nommée hors de sa propre classe
 * (type, nom JPQL dans une chaîne, nom de classe dans un XML), ni héritière d'une entité nommée.
 */
public final class DatasourceResolver {

  public record Result(List<SqlAccess> sqlAccesses, List<Relation> relations) {
  }

  static final String DATASOURCE = "DataSource";
  static final String JDBC = "JdbcTemplate";
  static final String NAMED = "NamedParameterJdbcTemplate";
  static final String CLIENT = "JdbcClient";
  static final String EMF = "EntityManagerFactory";
  static final String MYBATIS = "SqlSessionFactory";
  static final String JDBI = "Jdbi";
  private static final String DEFAULT_EMF = "entityManagerFactory";

  private static final Set<String> SPRING_CONTEXT = Set.of("org.springframework.context.annotation");
  private static final Set<String> BOOT_PROPERTIES = Set.of("org.springframework.boot.context.properties");
  private static final Set<String> QUALIFIERS = Set.of("org.springframework.beans.factory.annotation",
      "javax.inject", "jakarta.inject");
  private static final Set<String> SPRING_DATA_JPA = Set.of("org.springframework.data.jpa.repository.config");
  private static final Set<String> MYBATIS_SPRING = Set.of("org.mybatis.spring.annotation");
  private static final Pattern VIEW_NAME = Pattern.compile("(?i)^(v|vw|vue|view)_.+|.+_(v|vw|vue|view)$");
  private static final Pattern VIEW_COMMENT = Pattern.compile("(?iu)(?<![\\p{L}\\d_])(vues?|views?)(?![\\p{L}\\d_])");
  private static final Pattern WORD = Pattern.compile("[\\p{L}_$][\\p{L}\\d_$]*");

  /** Bean déclaré par une méthode {@code @Bean} : noms (le premier est le nom principal). */
  private record Bean(List<String> names, String family, boolean primary, CtMethod<?> method) {
    String name() {
      return names.get(0);
    }
  }

  /** Paquet couvert par une configuration (scan d'entités, de dépôts, de mappers) et sa source. */
  private record PackageRule(String pkg, String datasource) {
  }

  private final ExtractionContext ctx;
  private final Map<String, EntityDraft> drafts;
  private final List<Datasource> datasources = new ArrayList<>();
  private final Map<String, Datasource> byId = new LinkedHashMap<>();
  private final Map<String, String> byUnit = new HashMap<>();
  private final List<Detected> detected;

  private final List<Bean> beans = new ArrayList<>();
  private final Map<String, Bean> beansByName = new HashMap<>();
  private final Map<String, String> beanDatasource = new HashMap<>();
  private final Set<String> resolving = new HashSet<>();
  private final List<PackageRule> entityPackages = new ArrayList<>();
  private final List<PackageRule> repositoryPackages = new ArrayList<>();
  private final List<PackageRule> mapperPackages = new ArrayList<>();
  private final Map<String, Set<String>> listedClasses = new HashMap<>();

  public DatasourceResolver(ExtractionContext ctx, List<Detected> detected, Map<String, EntityDraft> drafts) {
    this.ctx = ctx;
    this.drafts = drafts;
    this.detected = detected;
    for (Detected d : detected) {
      datasources.add(d.datasource());
      byId.putIfAbsent(d.datasource().id(), d.datasource());
      if (d.unit() != null) {
        byUnit.putIfAbsent(d.unit().name(), d.datasource().id());
        for (String c : d.unit().classes()) {
          listedClasses.computeIfAbsent(c, k -> new TreeSet<>()).add(d.datasource().id());
        }
      }
    }
  }

  public Result resolve(List<SqlDraft> sql, List<Relation> relations) {
    indexBeans();
    indexScans();
    List<EntityDraft> sorted = new ArrayList<>(drafts.values());
    sorted.sort(Comparator.comparing(d -> d.id));
    for (EntityDraft d : sorted) {
      d.datasource = entityDatasource(d);
      Datasource ds = d.datasource == null ? null : byId.get(d.datasource);
      d.defaultSchema(ds == null ? null : ds.defaultSchema());
      if (d.isView == null && d.table != null && isView(d)) {
        d.isView = true;
      }
    }
    unreferenced(sorted);
    List<SqlAccess> accesses = new ArrayList<>();
    for (SqlDraft s : sql) {
      accesses.add(sqlAccess(s));
    }
    List<Relation> rels = new ArrayList<>();
    for (Relation r : relations) {
      rels.add(withDefaultJoinSchema(r));
    }
    return new Result(accesses, rels);
  }

  // ---------------------------------------------------------------- beans Spring

  private void indexBeans() {
    for (CtType<?> t : ctx.types().all()) {
      List<CtMethod<?>> methods = new ArrayList<>(t.getMethods());
      methods.sort(Comparator.comparingInt(Provenance::offset));
      for (CtMethod<?> m : methods) {
        CtAnnotation<?> bean = Annotations.find(m, SPRING_CONTEXT, "Bean");
        if (bean == null) {
          continue;
        }
        List<String> names = new ArrayList<>();
        for (String key : List.of("name", "value")) {
          for (CtExpression<?> e : Annotations.values(bean, key)) {
            String n = ctx.eval().constant(e);
            if (n != null && !n.isBlank() && !names.contains(n)) {
              names.add(n);
            }
          }
        }
        if (names.isEmpty()) {
          names.add(m.getSimpleName());
        }
        Bean b = new Bean(List.copyOf(names), family(m.getType()), Annotations.has(m, SPRING_CONTEXT, "Primary"), m);
        beans.add(b);
        for (String n : names) {
          beansByName.putIfAbsent(n, b);
        }
      }
    }
  }

  /** Famille d'un type : ce qui permet l'injection par type entre beans. */
  static String family(CtTypeReference<?> ref) {
    if (ref == null) {
      return null;
    }
    String s = Types.simpleName(ref);
    if (s.endsWith("DataSource")) {
      return DATASOURCE;
    }
    if (s.equals("JdbcTemplate") || s.equals("JdbcOperations")) {
      return JDBC;
    }
    if (s.startsWith("NamedParameterJdbc")) {
      return NAMED;
    }
    if (s.equals("JdbcClient")) {
      return CLIENT;
    }
    // Les fabriques de fabrique (EntityManagerFactoryBuilder, SqlSessionFactoryBuilder) n'en font pas partie.
    if (s.endsWith("EntityManagerFactory") || s.endsWith("EntityManagerFactoryBean")) {
      return EMF;
    }
    if (s.equals("SqlSessionFactory") || s.equals("SqlSessionFactoryBean") || s.equals("SqlSessionTemplate")) {
      return MYBATIS;
    }
    return s;
  }

  private String datasourceOf(Bean b) {
    if (beanDatasource.containsKey(b.name())) {
      return beanDatasource.get(b.name());
    }
    if (!resolving.add(b.name())) {
      return null;
    }
    String ds = computeDatasource(b);
    resolving.remove(b.name());
    beanDatasource.put(b.name(), ds);
    return ds;
  }

  private String computeDatasource(Bean b) {
    CtMethod<?> m = b.method();
    CtAnnotation<?> props = Annotations.find(m, BOOT_PROPERTIES, "ConfigurationProperties");
    if (props != null) {
      String prefix = ctx.str(props, "prefix");
      if (prefix == null) {
        prefix = ctx.str(props, "value");
      }
      String ds = byPrefix(prefix);
      if (ds != null) {
        return ds;
      }
    }
    Set<String> candidates = new TreeSet<>();
    if (m.getBody() != null) {
      for (CtLiteral<?> lit : m.getBody().getElements(new TypeFilter<>(CtLiteral.class))) {
        if (lit.getValue() instanceof String s) {
          String ds = byJndi(s);
          if (ds != null) {
            candidates.add(ds);
          }
        }
      }
    }
    for (CtParameter<?> p : m.getParameters()) {
      String family = family(p.getType());
      String qualifier = qualifier(p);
      if (qualifier == null && !isDataFamily(family)) {
        continue;
      }
      String ds = reference(qualifier, family, p.getSimpleName());
      if (ds != null) {
        candidates.add(ds);
      }
    }
    if (m.getBody() != null) {
      for (CtInvocation<?> inv : m.getBody().getElements(new TypeFilter<>(CtInvocation.class))) {
        if (inv.getTarget() != null && !inv.getTarget().isImplicit()
            && !inv.getTarget().toString().equals("this")) {
          continue;
        }
        Bean other = beansByName.get(inv.getExecutable().getSimpleName());
        if (other != null && other != b) {
          String ds = datasourceOf(other);
          if (ds != null) {
            candidates.add(ds);
          }
        }
      }
    }
    return candidates.size() == 1 ? candidates.iterator().next() : null;
  }

  private static boolean isDataFamily(String family) {
    return DATASOURCE.equals(family) || JDBC.equals(family) || NAMED.equals(family) || CLIENT.equals(family)
        || EMF.equals(family) || MYBATIS.equals(family) || JDBI.equals(family);
  }

  /** Injection : qualificateur, sinon unique bean ou bean {@code @Primary} de la famille, sinon par nom. */
  private String reference(String qualifier, String family, String name) {
    if (qualifier != null) {
      Bean b = beansByName.get(qualifier);
      return b != null ? datasourceOf(b) : byJndi(qualifier);
    }
    Bean b = byFamily(family, name);
    return b == null ? null : datasourceOf(b);
  }

  private Bean byFamily(String family, String name) {
    List<Bean> candidates = beans.stream().filter(b -> b.family().equals(family)).toList();
    if (candidates.size() == 1) {
      return candidates.get(0);
    }
    List<Bean> primary = candidates.stream().filter(Bean::primary).toList();
    if (primary.size() == 1) {
      return primary.get(0);
    }
    if (name != null) {
      for (Bean b : candidates) {
        if (b.names().contains(name)) {
          return b;
        }
      }
    }
    return null;
  }

  private String qualifier(CtParameter<?> p) {
    CtAnnotation<?> q = Annotations.findAny(p, QUALIFIERS, "Qualifier", "Named");
    return q == null ? null : ctx.str(q, "value");
  }

  private String byPrefix(String prefix) {
    if (prefix == null) {
      return null;
    }
    String p = Config.normalize(prefix);
    String best = null;
    int bestLength = -1;
    for (Detected d : detected) {
      if (d.configPrefix() == null) {
        continue;
      }
      String cp = Config.normalize(d.configPrefix());
      if ((p.equals(cp) || p.startsWith(cp + ".") || cp.startsWith(p + ".")) && cp.length() > bestLength) {
        best = d.datasource().id();
        bestLength = cp.length();
      }
    }
    return best;
  }

  /** Nom JNDI exact, sinon dernier segment ({@code jdbc/GppDS} ↔ {@code java:jboss/datasources/GppDS}). */
  private String byJndi(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    for (Datasource d : datasources) {
      if (name.equals(d.jndiName())) {
        return d.id();
      }
    }
    String last = lastSegment(name);
    Set<String> matches = new TreeSet<>();
    for (Datasource d : datasources) {
      if (d.jndiName() != null && name.contains("/") && lastSegment(d.jndiName()).equals(last)) {
        matches.add(d.id());
      }
    }
    return matches.size() == 1 ? matches.iterator().next() : null;
  }

  private static String lastSegment(String jndi) {
    int i = Math.max(jndi.lastIndexOf('/'), jndi.lastIndexOf(':'));
    return jndi.substring(i + 1);
  }

  // ---------------------------------------------------------------- scans (entités, dépôts, mappers)

  private void indexScans() {
    for (Bean b : beans) {
      if (!EMF.equals(b.family()) || b.method().getBody() == null) {
        continue;
      }
      String ds = datasourceOf(b);
      for (CtInvocation<?> inv : b.method().getBody().getElements(new TypeFilter<>(CtInvocation.class))) {
        String n = inv.getExecutable().getSimpleName();
        if (n.equals("packages") || n.equals("setPackagesToScan")) {
          for (CtExpression<?> arg : inv.getArguments()) {
            for (String pkg : strings(arg)) {
              entityPackages.add(new PackageRule(pkg, ds));
            }
          }
        } else if ((n.equals("persistenceUnit") || n.equals("setPersistenceUnitName"))
            && inv.getArguments().size() == 1 && ds != null) {
          String unit = ctx.eval().constant(inv.getArguments().get(0));
          if (unit != null) {
            byUnit.putIfAbsent(unit, ds);
          }
        }
      }
    }
    for (CtType<?> t : ctx.types().all()) {
      for (CtAnnotation<?> a : Annotations.findAll(t, SPRING_DATA_JPA, "EnableJpaRepositories")) {
        String ref = ctx.str(a, "entityManagerFactoryRef");
        Bean emf = beansByName.get(ref == null ? DEFAULT_EMF : ref);
        if (emf == null && ref == null) {
          emf = byFamily(EMF, DEFAULT_EMF);
        }
        String ds = emf == null ? null : datasourceOf(emf);
        for (String pkg : scanPackages(t, a)) {
          repositoryPackages.add(new PackageRule(pkg, ds));
        }
      }
      for (CtAnnotation<?> a : Annotations.findAll(t, MYBATIS_SPRING, "MapperScan")) {
        String ref = ctx.str(a, "sqlSessionFactoryRef");
        if (ref == null) {
          ref = ctx.str(a, "sqlSessionTemplateRef");
        }
        Bean factory = ref == null ? byFamily(MYBATIS, null) : beansByName.get(ref);
        String ds = factory == null ? null : datasourceOf(factory);
        for (String pkg : scanPackages(t, a)) {
          mapperPackages.add(new PackageRule(pkg, ds));
        }
      }
    }
  }

  /** {@code basePackages}/{@code value}, {@code basePackageClasses}, sinon paquet de la classe annotée. */
  private List<String> scanPackages(CtType<?> t, CtAnnotation<?> a) {
    List<String> out = new ArrayList<>();
    for (String key : List.of("basePackages", "value")) {
      CtExpression<?> e = Annotations.value(a, key);
      if (e != null) {
        out.addAll(strings(e));
      }
    }
    for (CtExpression<?> e : Annotations.values(a, "basePackageClasses")) {
      CtTypeReference<?> ref = Annotations.classLiteral(e);
      if (ref != null && ref.getPackage() != null) {
        out.add(ref.getPackage().getQualifiedName());
      }
    }
    if (out.isEmpty() && t.getPackage() != null) {
      out.add(t.getPackage().getQualifiedName());
    }
    return out;
  }

  private List<String> strings(CtExpression<?> e) {
    List<String> out = new ArrayList<>();
    for (CtExpression<?> item : Annotations.flatten(e)) {
      String s = ctx.eval().constant(item);
      if (s != null) {
        for (String part : s.split("[,;\\s]+")) {
          if (!part.isBlank()) {
            out.add(part.strip());
          }
        }
      }
    }
    return out;
  }

  /** Règle du paquet le plus long contenant la classe ; null si aucune ou si les règles divergent. */
  private static String byPackage(List<PackageRule> rules, String className) {
    if (className == null) {
      return null;
    }
    int best = -1;
    Set<String> found = new TreeSet<>();
    boolean unknown = false;
    for (PackageRule r : rules) {
      if (!className.startsWith(r.pkg() + ".")) {
        continue;
      }
      if (r.pkg().length() > best) {
        best = r.pkg().length();
        found.clear();
        unknown = false;
      }
      if (r.pkg().length() == best) {
        if (r.datasource() == null) {
          unknown = true;
        } else {
          found.add(r.datasource());
        }
      }
    }
    return !unknown && found.size() == 1 ? found.iterator().next() : null;
  }

  // ---------------------------------------------------------------- entités

  private String entityDatasource(EntityDraft d) {
    Set<String> listed = listedClasses.get(d.className);
    if (listed != null) {
      return listed.size() == 1 ? listed.iterator().next() : ambiguous(d);
    }
    String ds = byPackage(entityPackages, d.className);
    if (ds != null) {
      return ds;
    }
    Set<String> units = new TreeSet<>();
    Path file = d.source == null || d.source.file() == null ? null : ctx.root().resolve(d.source.file()).normalize();
    for (Detected det : detected) {
      PersistenceUnit pu = det.unit();
      if (pu != null && !pu.excludeUnlistedClasses() && file != null && pu.moduleDir() != null
          && file.startsWith(pu.moduleDir().normalize())) {
        units.add(det.datasource().id());
      }
    }
    if (units.size() == 1) {
      return units.iterator().next();
    }
    if (byId.size() == 1) {
      return byId.keySet().iterator().next();
    }
    return ambiguous(d);
  }

  private String ambiguous(EntityDraft d) {
    if (d.isEntity() && byId.size() > 1) {
      ctx.diagnostics().warning("DATASOURCE_AMBIGUOUS", "Source de données indéterminée pour l'entité "
          + d.className + " (" + String.join(", ", byId.keySet()) + ")", d.source);
    }
    return null;
  }

  private boolean isView(EntityDraft d) {
    if (ctx.isViewSchema(d.schema) || VIEW_NAME.matcher(d.table).matches()) {
      return true;
    }
    String doc = d.type.getDocComment();
    if (doc != null && VIEW_COMMENT.matcher(doc).find()) {
      return true;
    }
    for (CtComment c : d.type.getComments()) {
      if (VIEW_COMMENT.matcher(c.getContent()).find()) {
        return true;
      }
    }
    return false;
  }

  /** Entités jamais nommées hors de leur propre classe : info {@code ENTITY_UNREFERENCED}. */
  private void unreferenced(List<EntityDraft> sorted) {
    Map<String, EntityDraft> byClass = new HashMap<>();
    Map<String, List<EntityDraft>> byName = new HashMap<>();
    for (EntityDraft d : sorted) {
      if (d.kind.equals(EntityDraft.ENTITY) || d.kind.equals(EntityDraft.MAPPED_SUPERCLASS)) {
        byClass.put(d.className, d);
        if (d.name != null) {
          byName.computeIfAbsent(d.name, k -> new ArrayList<>()).add(d);
        }
      }
    }
    Set<String> referenced = new HashSet<>();
    for (CtType<?> t : ctx.types().all()) {
      if (t.getDeclaringType() != null) {
        continue;
      }
      String self = t.getQualifiedName();
      for (CtTypeReference<?> ref : t.getElements(new TypeFilter<>(CtTypeReference.class))) {
        String qn = ref.getQualifiedName();
        if (byClass.containsKey(qn) && !isInside(qn, self)) {
          referenced.add(qn);
        }
      }
      for (CtLiteral<?> lit : t.getElements(new TypeFilter<>(CtLiteral.class))) {
        if (lit.getValue() instanceof String s) {
          Matcher m = WORD.matcher(s);
          while (m.find()) {
            for (EntityDraft d : byName.getOrDefault(m.group(), List.of())) {
              if (!isInside(d.className, self)) {
                referenced.add(d.className);
              }
            }
          }
        }
      }
    }
    for (Path p : ctx.resources()) {
      String text;
      try {
        text = Files.readString(p, StandardCharsets.UTF_8);
      } catch (IOException | RuntimeException e) {
        continue;
      }
      for (String c : byClass.keySet()) {
        if (text.contains(c)) {
          referenced.add(c);
        }
      }
    }
    for (EntityDraft d : sorted) {
      if (d.isEntity() && !isReferenced(d, referenced)) {
        ctx.diagnostics().info("ENTITY_UNREFERENCED", "Entité jamais référencée dans le code : " + d.className,
            d.source);
      }
    }
  }

  private static boolean isInside(String className, String topLevel) {
    return className.equals(topLevel) || className.startsWith(topLevel + "$");
  }

  /** Nommée, ou héritière (chargement polymorphe) d'une classe persistante nommée. */
  private static boolean isReferenced(EntityDraft d, Set<String> referenced) {
    for (EntityDraft cur = d; cur != null; cur = cur.parentDraft) {
      if (referenced.contains(cur.className)) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------- SQL

  private SqlAccess sqlAccess(SqlDraft draft) {
    SqlAccess a = draft.access();
    String ds = sqlDatasource(a, draft.hint());
    if (ds == null && byId.size() > 1) {
      ctx.diagnostics().warning("DATASOURCE_AMBIGUOUS", "Source de données indéterminée pour le SQL de "
          + (a.caller() != null ? a.caller() : draft.hint() == null ? "?" : draft.hint().owner())
          + jdbiReason(a, draft.hint()) + " (" + String.join(", ", byId.keySet()) + ")", a.source());
    }
    Datasource d = ds == null ? null : byId.get(ds);
    List<SqlTable> tables = withDefaultSchema(a.tables(), d == null ? null : d.defaultSchema());
    return new SqlAccess(a.id(), a.origin(), ds, a.sql(), tables, a.parsed(), a.caller(), a.source());
  }

  /** Pourquoi un SQL JDBI n'est pas rattaché : bean Jdbi nommé mais défini hors du dépôt, ou inconnu. */
  private String jdbiReason(SqlAccess a, DatasourceHint h) {
    if (!a.origin().equals(SqlExtractor.JDBI)) {
      return "";
    }
    if (h != null && h.bean() != null && !beansByName.containsKey(h.bean())) {
      return " : bean Jdbi « " + h.bean() + " » défini hors du dépôt";
    }
    return h == null || h.bean() == null ? " : aucun bean Jdbi déclaré dans le dépôt ne crée cette interface" : "";
  }

  private String sqlDatasource(SqlAccess a, DatasourceHint h) {
    if (byId.size() == 1) {
      return byId.keySet().iterator().next();
    }
    String origin = a.origin();
    if (h != null && h.unit() != null && byUnit.containsKey(h.unit())) {
      return byUnit.get(h.unit());
    }
    if (h != null && h.bean() != null) {
      Bean b = beansByName.get(h.bean());
      String ds = b != null ? datasourceOf(b) : byJndi(h.bean());
      if (ds != null) {
        return ds;
      }
    }
    if (origin.equals(SqlExtractor.JDBI)) {
      // Pas d'auto-configuration de Jdbi : seul un bean Jdbi déclaré dans le dépôt rattache le SQL. Un bean
      // nommé mais défini ailleurs (bibliothèque) laisse la source inconnue plutôt que la DataSource principale.
      if (h != null && h.bean() != null) {
        return null;
      }
      Bean b = beans.stream().anyMatch(x -> x.family().equals(JDBI)) ? byFamily(JDBI, null) : null;
      return b == null ? null : datasourceOf(b);
    }
    String owner = h == null ? null : h.owner();
    if (origin.equals(SqlExtractor.NAMED_NATIVE_QUERY)) {
      EntityDraft e = owner == null ? null : drafts.values().stream()
          .filter(d -> d.className.equals(owner)).findFirst().orElse(null);
      return e == null ? null : e.datasource;
    }
    if (origin.equals(SqlExtractor.NATIVE_QUERY)) {
      String ds = byPackage(repositoryPackages, owner);
      if (ds != null) {
        return ds;
      }
    }
    if (origin.equals(SqlExtractor.MYBATIS_ANNOTATION) || origin.equals(SqlExtractor.MYBATIS_XML)) {
      String ds = byPackage(mapperPackages, owner);
      if (ds != null) {
        return ds;
      }
    }
    String family = switch (origin) {
      case SqlExtractor.JDBC_TEMPLATE -> JDBC;
      case SqlExtractor.NAMED_PARAMETER_JDBC_TEMPLATE -> NAMED;
      case SqlExtractor.NATIVE_QUERY -> EMF;
      case SqlExtractor.MYBATIS_ANNOTATION, SqlExtractor.MYBATIS_XML -> MYBATIS;
      default -> DATASOURCE;
    };
    String name = h == null ? null : h.name();
    if (name != null) {
      Bean b = beansByName.get(name);
      if (b != null && isDataFamily(b.family())) {
        String ds = datasourceOf(b);
        if (ds != null) {
          return ds;
        }
      }
    }
    // Bean déclaré de la famille ; sinon auto-configuration Spring Boot sur la DataSource principale.
    if (beans.stream().anyMatch(b -> b.family().equals(family))) {
      Bean b = byFamily(family, family.equals(EMF) ? DEFAULT_EMF : null);
      return b == null ? null : datasourceOf(b);
    }
    Bean primaryDs = byFamily(DATASOURCE, null);
    return primaryDs == null ? null : datasourceOf(primaryDs);
  }

  /** Schéma par défaut pour les tables sans schéma ; écriture prioritaire sur lecture en cas de fusion. */
  static List<SqlTable> withDefaultSchema(List<SqlTable> tables, String defaultSchema) {
    if (defaultSchema == null || tables.stream().allMatch(t -> t.schema() != null)) {
      return tables;
    }
    Map<String, SqlTable> merged = new LinkedHashMap<>();
    for (SqlTable t : tables) {
      SqlTable s = t.schema() == null ? new SqlTable(defaultSchema, t.name(), t.access()) : t;
      String key = s.schema() + "\u0000" + s.name();
      SqlTable prev = merged.get(key);
      if (prev == null || s.access().equals(SqlAnalyzer.WRITE)) {
        merged.put(key, s);
      }
    }
    List<SqlTable> out = new ArrayList<>(merged.values());
    out.sort(Comparator.comparing(SqlTable::sortKey));
    return out;
  }

  private Relation withDefaultJoinSchema(Relation r) {
    TableRef jt = r.joinTable();
    if (jt == null || jt.schema() != null) {
      return r;
    }
    EntityDraft from = drafts.values().stream().filter(d -> d.id.equals(r.fromEntity())).findFirst().orElse(null);
    Datasource ds = from == null || from.datasource == null ? null : byId.get(from.datasource);
    if (ds == null || ds.defaultSchema() == null) {
      return r;
    }
    return r.withJoinTable(new TableRef(ds.defaultSchema(), jt.name()));
  }

  /** Sources de données détectées, dans l'ordre de détection. */
  public List<Datasource> datasources() {
    return List.copyOf(new LinkedHashSet<>(byId.values()));
  }
}
