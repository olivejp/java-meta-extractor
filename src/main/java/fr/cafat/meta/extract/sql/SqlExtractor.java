package fr.cafat.meta.extract.sql;

import fr.cafat.meta.config.Config;
import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.model.Source;
import fr.cafat.meta.model.SqlAccess;
import fr.cafat.meta.output.Hashes;
import fr.cafat.meta.spoon.Annotations;
import fr.cafat.meta.spoon.Callers;
import fr.cafat.meta.spoon.PartialString;
import fr.cafat.meta.spoon.Provenance;
import fr.cafat.meta.spoon.Types;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.xml.stream.XMLStreamException;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtNewClass;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.CtVariableWrite;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeMember;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * Accès SQL hors entités : requêtes natives Spring Data et JPA, JdbcTemplate, MyBatis (XML et
 * annotations), JDBI SQL Object ({@code @SqlQuery}, {@code @SqlUpdate}, {@code @SqlBatch},
 * {@code @SqlCall}, {@code @SqlScript}) et littéraux SQL. Chaque accès porte un indice de source de données, résolu ensuite
 * par {@code DatasourceResolver}.
 *
 * <p>Un littéral déjà rattaché à une origine explicite (y compris via une constante, une variable
 * locale ou un StringBuilder) n'est pas réémis en {@code string_literal}.
 */
public final class SqlExtractor {

  public static final String NATIVE_QUERY = "native_query";
  public static final String NAMED_NATIVE_QUERY = "named_native_query";
  public static final String JDBC_TEMPLATE = "jdbc_template";
  public static final String NAMED_PARAMETER_JDBC_TEMPLATE = "named_parameter_jdbc_template";
  public static final String MYBATIS_XML = "mybatis_xml";
  public static final String MYBATIS_ANNOTATION = "mybatis_annotation";
  public static final String JDBI = "jdbi";
  public static final String STRING_LITERAL = "string_literal";

  /**
   * Ce que le code dit de la source de données d'un accès.
   *
   * @param owner type propriétaire (repository, entité, mapper, classe appelante)
   * @param bean bean désigné par {@code @Qualifier}, {@code @Named} ou {@code @Resource}
   * @param name nom du champ ou du paramètre receveur (autowiring par nom)
   * @param unit unité de persistance ({@code @PersistenceContext(unitName)})
   */
  public record DatasourceHint(String owner, String bean, String name, String unit) {
  }

  /** Accès SQL dont la source de données reste à rattacher. */
  public record SqlDraft(SqlAccess access, DatasourceHint hint) {
  }

  private static final Set<String> SPRING_DATA = Set.of("org.springframework.data.jpa.repository");
  private static final Set<String> NAMED_NATIVE = Set.of("javax.persistence", "jakarta.persistence",
      "org.hibernate.annotations");
  private static final Set<String> MYBATIS = Set.of("org.apache.ibatis.annotations");
  private static final Set<String> QUALIFIERS = Set.of("org.springframework.beans.factory.annotation",
      "javax.inject", "jakarta.inject", "javax.annotation", "jakarta.annotation");
  private static final List<String> MYBATIS_STATEMENTS = List.of("Select", "Insert", "Update", "Delete");
  /** JDBI 3 et JDBI 2 (org.skife). */
  private static final Set<String> JDBI_STATEMENT = Set.of("org.jdbi.v3.sqlobject.statement",
      "org.skife.jdbi.v2.sqlobject");
  private static final List<String> JDBI_STATEMENTS = List.of("SqlQuery", "SqlUpdate", "SqlBatch", "SqlCall",
      "SqlScript");
  private static final Set<String> JDBI_FACTORIES = Set.of("onDemand", "attach");
  private static final Set<String> SPRING_CONTEXT = Set.of("org.springframework.context.annotation");

  private static final Set<String> NATIVE_METHODS = Set.of("createNativeQuery", "createSQLQuery");
  private static final Set<String> JPQL_METHODS = Set.of("createQuery", "createNamedQuery", "createSelectionQuery",
      "createMutationQuery");
  private static final Set<String> JDBC_METHODS = Set.of("query", "queryForList", "queryForObject", "queryForMap",
      "queryForRowSet", "queryForStream", "queryForLong", "queryForInt", "update", "batchUpdate", "execute");
  private static final Set<String> JDBC_TYPES = Set.of("JdbcTemplate", "JdbcOperations");
  private static final Set<String> NAMED_JDBC_TYPES = Set.of("NamedParameterJdbcTemplate",
      "NamedParameterJdbcOperations");
  private static final Set<String> RAW_JDBC_METHODS = Set.of("prepareStatement", "prepareCall", "executeQuery",
      "executeUpdate", "executeLargeUpdate", "execute", "addBatch", "nativeSQL");
  private static final Set<String> RAW_JDBC_TYPES = Set.of("Connection", "Statement", "PreparedStatement",
      "CallableStatement");
  private static final Set<String> LOG_METHODS = Set.of("trace", "debug", "info", "warn", "warning", "error",
      "fatal", "log", "severe", "fine", "finer", "finest", "println", "print", "printf");
  private static final Set<String> APPEND_METHODS = Set.of("append", "add", "insert");
  private static final int MAX_DEPTH = 16;

  private final ExtractionContext ctx;
  private final List<SqlDraft> out = new ArrayList<>();
  /** Interface JDBI → bean Jdbi qui la crée (calculé à la demande). */
  private Map<String, String> jdbiBeans;
  /** Littéraux déjà rattachés à une origine explicite ou à une requête JPQL. */
  private final Set<CtLiteral<?>> consumed = Collections.newSetFromMap(new IdentityHashMap<>());

  /**
   * Prépare l'extraction des accès SQL.
   *
   * @param ctx contexte d'extraction de l'application (modèle, configuration, diagnostics)
   */
  public SqlExtractor(ExtractionContext ctx) {
    this.ctx = ctx;
  }

  /**
   * Accès SQL de tous les types et fichiers MyBatis du dépôt, avec leur indice de source de données.
   *
   * @return brouillons d'accès SQL, source de données encore null ; liste vide si aucun
   */
  public List<SqlDraft> extract() {
    for (CtType<?> t : ctx.types().all()) {
      annotations(t);
    }
    List<CtType<?>> tops = ctx.types().all().stream().filter(t -> t.getDeclaringType() == null).toList();
    for (CtType<?> t : tops) {
      List<CtInvocation<?>> invocations = new ArrayList<>(t.getElements(new TypeFilter<>(CtInvocation.class)));
      invocations.sort(Comparator.comparingInt(Provenance::offset));
      for (CtInvocation<?> inv : invocations) {
        invocation(inv);
      }
    }
    for (Path file : ctx.resources()) {
      if (file.getFileName().toString().endsWith(".xml")) {
        mapperXml(file);
      }
    }
    for (CtType<?> t : tops) {
      literals(t);
    }
    return out;
  }

  /**
   * Accès seuls, sans indice de source de données.
   *
   * @param drafts brouillons issus de {@link #extract()}
   * @return accès dans l'ordre des brouillons
   */
  public static List<SqlAccess> accesses(List<SqlDraft> drafts) {
    return drafts.stream().map(SqlDraft::access).toList();
  }

  // ---------------------------------------------------------------- annotations

  private void annotations(CtType<?> t) {
    String owner = t.getQualifiedName();
    for (CtAnnotation<?> nnq : namedNativeQueries(t)) {
      CtExpression<?> query = Annotations.value(nnq, "query");
      if (query != null) {
        add(NAMED_NATIVE_QUERY, ctx.eval().eval(query), false, null, nnq, new DatasourceHint(owner, null, null, null));
      }
    }
    List<CtMethod<?>> methods = new ArrayList<>();
    for (CtTypeMember m : t.getTypeMembers()) {
      if (m instanceof CtMethod<?> method) {
        methods.add(method);
      }
    }
    methods.sort(Comparator.comparingInt(Provenance::offset));
    for (CtMethod<?> m : methods) {
      String caller = Callers.of(m);
      DatasourceHint hint = new DatasourceHint(owner, null, null, null);
      CtAnnotation<?> query = Annotations.find(m, SPRING_DATA, "Query");
      CtAnnotation<?> nativeQuery = Annotations.find(m, SPRING_DATA, "NativeQuery");
      if (query != null && Boolean.TRUE.equals(Annotations.bool(query, "nativeQuery")) || nativeQuery != null) {
        CtAnnotation<?> a = nativeQuery != null ? nativeQuery : query;
        for (String key : List.of("value", "countQuery")) {
          CtExpression<?> e = Annotations.value(a, key);
          if (e != null) {
            add(NATIVE_QUERY, ctx.eval().eval(e), false, caller, a, hint);
          }
        }
      }
      for (String kind : MYBATIS_STATEMENTS) {
        CtAnnotation<?> a = Annotations.find(m, MYBATIS, kind);
        if (a != null && Annotations.value(a, "value") != null) {
          mybatisAnnotation(a, caller, hint);
        }
      }
      List<CtAnnotation<?>> jdbi = jdbiStatements(m);
      if (!jdbi.isEmpty()) {
        DatasourceHint jdbiHint = new DatasourceHint(owner, jdbiBeans().get(owner), null, null);
        for (CtAnnotation<?> a : jdbi) {
          jdbi(a, caller, jdbiHint);
        }
      }
    }
  }

  /** Annotations d'instruction JDBI d'une méthode, y compris les {@code @SqlScript} répétés ({@code @SqlScripts}). */
  private static List<CtAnnotation<?>> jdbiStatements(CtMethod<?> m) {
    List<CtAnnotation<?>> out = new ArrayList<>();
    for (String kind : JDBI_STATEMENTS) {
      out.addAll(Annotations.findAll(m, JDBI_STATEMENT, kind));
    }
    for (CtAnnotation<?> container : Annotations.findAll(m, JDBI_STATEMENT, "SqlScripts")) {
      out.addAll(Annotations.nested(container, "value"));
    }
    out.sort(Comparator.comparingInt(Provenance::offset));
    return out;
  }

  /**
   * Interface JDBI → nom du bean Jdbi qui la crée, lu dans les méthodes {@code @Bean} du dépôt qui ont un
   * seul paramètre de type Jdbi : {@code @Qualifier}, sinon nom du paramètre (injection par nom). L'interface
   * est le type rendu, ou la classe passée à {@code jdbi.onDemand(X.class)} / {@code attach}. Une interface
   * créée par deux beans Jdbi différents n'est pas rattachée.
   */
  private Map<String, String> jdbiBeans() {
    if (jdbiBeans != null) {
      return jdbiBeans;
    }
    Map<String, String> out = new HashMap<>();
    Set<String> conflicts = new HashSet<>();
    for (CtType<?> t : ctx.types().all()) {
      List<CtMethod<?>> methods = new ArrayList<>(t.getMethods());
      methods.sort(Comparator.comparingInt(Provenance::offset));
      for (CtMethod<?> m : methods) {
        if (!Annotations.has(m, SPRING_CONTEXT, "Bean")) {
          continue;
        }
        List<CtParameter<?>> jdbis = m.getParameters().stream()
            .filter(p -> p.getType() != null && "Jdbi".equals(Types.simpleName(p.getType()))).toList();
        if (jdbis.size() != 1) {
          continue;
        }
        String q = qualifier(jdbis.get(0));
        String bean = q != null ? q : jdbis.get(0).getSimpleName();
        Set<String> daos = new TreeSet<>();
        if (m.getType() != null && m.getType().getQualifiedName() != null) {
          daos.add(m.getType().getQualifiedName());
        }
        for (CtInvocation<?> inv : m.getElements(new TypeFilter<>(CtInvocation.class))) {
          if (JDBI_FACTORIES.contains(inv.getExecutable().getSimpleName()) && !inv.getArguments().isEmpty()
              && inv.getArguments().get(0) instanceof CtFieldRead<?> f && "class".equals(f.getVariable().getSimpleName())
              && f.getVariable().getDeclaringType() != null) {
            daos.add(f.getVariable().getDeclaringType().getQualifiedName());
          }
        }
        for (String dao : daos) {
          String prev = out.putIfAbsent(dao, bean);
          if (prev != null && !prev.equals(bean)) {
            conflicts.add(dao);
          }
        }
      }
    }
    conflicts.forEach(out::remove);
    jdbiBeans = out;
    return out;
  }

  /**
   * Instruction JDBI : le SQL est la valeur de l'annotation. Sans valeur, JDBI lit le SQL ailleurs
   * (localisateur de fichier .sql ou de gabarit, clé = nom de la méthode) : l'accès n'est pas lu et
   * le diagnostic le dit.
   */
  private void jdbi(CtAnnotation<?> a, String caller, DatasourceHint hint) {
    CtExpression<?> value = Annotations.value(a, "value");
    PartialString sql = value == null ? null : ctx.eval().eval(value);
    if (sql == null || literalText(sql).isBlank() && sql.isComplete()) {
      ctx.diagnostics().warning("SQL_UNRESOLVED", "SQL JDBI hors de l'annotation @"
          + a.getAnnotationType().getSimpleName() + " (fichier .sql ou gabarit lu par un localisateur JDBI) dans "
          + caller, ctx.source(a));
      return;
    }
    add(JDBI, sql, false, caller, a, hint);
  }

  private List<CtAnnotation<?>> namedNativeQueries(CtType<?> t) {
    List<CtAnnotation<?>> out = new ArrayList<>(Annotations.findAll(t, NAMED_NATIVE, "NamedNativeQuery"));
    for (CtAnnotation<?> container : Annotations.findAll(t, NAMED_NATIVE, "NamedNativeQueries")) {
      out.addAll(Annotations.nested(container, "value"));
    }
    return out;
  }

  /** {@code @Select} et consorts : chaîne ou tableau de chaînes joint par une espace ; {@code <script>} lu en XML. */
  private void mybatisAnnotation(CtAnnotation<?> a, String caller, DatasourceHint hint) {
    List<PartialString> parts = ctx.eval().evalAll(Annotations.value(a, "value"));
    List<PartialString> joined = new ArrayList<>();
    for (PartialString p : parts) {
      if (!joined.isEmpty()) {
        joined.add(PartialString.lit(" "));
      }
      joined.add(p);
    }
    PartialString sql = PartialString.concat(joined);
    String text = sql.render().strip();
    if (text.startsWith("<script>")) {
      MyBatisXml.Statement st = MyBatisXml.script(text);
      if (st == null) {
        ctx.diagnostics().warning("SQL_UNPARSED", "Script MyBatis illisible dans " + caller, ctx.source(a));
        return;
      }
      sql = st.dynamic() ? PartialString.lit(st.sql()).markDynamic() : PartialString.lit(st.sql());
    }
    add(MYBATIS_ANNOTATION, sql, true, caller, a, hint);
  }

  // ---------------------------------------------------------------- invocations

  private void invocation(CtInvocation<?> inv) {
    String name = inv.getExecutable().getSimpleName();
    List<CtExpression<?>> args = inv.getArguments();
    if (args.isEmpty()) {
      return;
    }
    CtExpression<?> sqlArg = args.get(0);
    CtExpression<?> target = inv.getTarget();
    if (JPQL_METHODS.contains(name)) {
      consume(sqlArg);
      return;
    }
    String origin = null;
    if (NATIVE_METHODS.contains(name)) {
      origin = NATIVE_QUERY;
    } else if (JDBC_METHODS.contains(name) && isReceiver(target, JDBC_TYPES, "getJdbcTemplate", "getJdbcOperations")) {
      origin = JDBC_TEMPLATE;
    } else if (JDBC_METHODS.contains(name)
        && isReceiver(target, NAMED_JDBC_TYPES, "getNamedParameterJdbcTemplate")) {
      origin = NAMED_PARAMETER_JDBC_TEMPLATE;
    } else if (name.equals("sql") && isReceiver(target, Set.of("JdbcClient"))) {
      origin = JDBC_TEMPLATE;
    } else if (RAW_JDBC_METHODS.contains(name) && isReceiver(target, RAW_JDBC_TYPES)) {
      origin = STRING_LITERAL;
    }
    if (origin == null || !isSqlText(sqlArg)) {
      return;
    }
    consume(sqlArg);
    PartialString sql = ctx.eval().eval(sqlArg);
    if (literalText(sql).isBlank()) {
      ctx.diagnostics().warning("SQL_UNRESOLVED", "Texte SQL non calculable (" + sqlArg + ") dans "
          + Callers.of(inv), ctx.source(inv));
      return;
    }
    add(origin, sql, false, Callers.of(inv), inv, hint(target, inv));
  }

  /** Receveur du type donné (déclaré), ou obtenu par l'un des accesseurs nommés. */
  private static boolean isReceiver(CtExpression<?> target, Set<String> types, String... getters) {
    if (target == null) {
      return false;
    }
    if (target instanceof CtInvocation<?> getter) {
      for (String g : getters) {
        if (getter.getExecutable().getSimpleName().equals(g)) {
          return true;
        }
      }
    }
    CtTypeReference<?> type = Types.typeOf(target);
    return type != null && types.contains(Types.simpleName(type));
  }

  /** Argument qui peut porter du texte SQL : ni lambda, ni objet construit, ni type connu autre que chaîne. */
  private static boolean isSqlText(CtExpression<?> e) {
    if (e instanceof CtLambda<?> || e instanceof CtConstructorCall<?>) {
      return false;
    }
    CtTypeReference<?> type = Types.typeOf(e);
    if (type == null) {
      return true;
    }
    String simple = Types.simpleName(type);
    return simple.equals("String") || simple.equals("CharSequence") || simple.equals("Object")
        || simple.contains("nulltype");
  }

  private DatasourceHint hint(CtExpression<?> receiver, CtElement at) {
    CtType<?> type = at.getParent(CtType.class);
    while (type != null && type.getDeclaringType() != null) {
      type = type.getDeclaringType();
    }
    String owner = type == null ? null : type.getQualifiedName();
    if (receiver instanceof CtFieldRead<?> fr) {
      CtField<?> f = ctx.eval().field(fr);
      String name = fr.getVariable().getSimpleName();
      if (f == null) {
        return new DatasourceHint(owner, null, name, null);
      }
      return new DatasourceHint(owner, qualifier(f), name, persistenceUnit(f));
    }
    if (receiver instanceof CtVariableRead<?> vr && vr.getVariable().getDeclaration() instanceof CtParameter<?> p) {
      return new DatasourceHint(owner, qualifier(p), p.getSimpleName(), null);
    }
    return new DatasourceHint(owner, null, null, null);
  }

  /** Bean désigné sur le champ, sinon sur le paramètre de constructeur qui lui est affecté. */
  private String qualifier(CtField<?> f) {
    String q = qualifier((CtElement) f);
    if (q != null || f.getDeclaringType() == null) {
      return q;
    }
    for (CtTypeMember m : f.getDeclaringType().getTypeMembers()) {
      if (!(m instanceof CtConstructor<?> c)) {
        continue;
      }
      for (CtAssignment<?, ?> a : c.getElements(new TypeFilter<>(CtAssignment.class))) {
        if (a.getAssigned() instanceof CtFieldWrite<?> fw
            && fw.getVariable().getSimpleName().equals(f.getSimpleName())
            && a.getAssignment() instanceof CtVariableRead<?> vr
            && vr.getVariable().getDeclaration() instanceof CtParameter<?> p) {
          String pq = qualifier(p);
          if (pq != null) {
            return pq;
          }
        }
      }
    }
    return null;
  }

  private String qualifier(CtElement e) {
    CtAnnotation<?> q = Annotations.findAny(e, QUALIFIERS, "Qualifier", "Named");
    if (q != null) {
      return ctx.str(q, "value");
    }
    CtAnnotation<?> resource = Annotations.find(e, QUALIFIERS, "Resource");
    return resource == null ? null : ctx.str(resource, "name");
  }

  private String persistenceUnit(CtField<?> f) {
    CtAnnotation<?> pc = Annotations.findAny(f, Annotations.JPA, "PersistenceContext", "PersistenceUnit");
    return pc == null ? null : ctx.str(pc, "unitName");
  }

  // ---------------------------------------------------------------- MyBatis XML

  private void mapperXml(Path file) {
    String rel = ctx.provenance().relative(file);
    List<MyBatisXml.Statement> statements;
    try {
      if (!MyBatisXml.isMapper(Files.readString(file, StandardCharsets.UTF_8))) {
        return;
      }
      statements = MyBatisXml.read(file);
    } catch (IOException | XMLStreamException | RuntimeException e) {
      ctx.diagnostics().warning("XML_UNREADABLE", "Mapper MyBatis illisible : " + rel, Source.file(rel, null));
      return;
    }
    for (MyBatisXml.Statement st : statements) {
      String caller = (st.namespace() == null ? "" : st.namespace()) + "#" + st.id();
      Source source = Source.of(st.namespace(), rel, st.line());
      PartialString sql = st.dynamic() ? PartialString.lit(st.sql()).markDynamic() : PartialString.lit(st.sql());
      emit(MYBATIS_XML, sql, true, caller, source, new DatasourceHint(st.namespace(), null, null, null));
    }
  }

  // ---------------------------------------------------------------- littéraux

  private void literals(CtType<?> t) {
    Set<CtExpression<?>> roots = Collections.newSetFromMap(new IdentityHashMap<>());
    List<CtExpression<?>> ordered = new ArrayList<>();
    for (CtLiteral<?> lit : t.getElements(new TypeFilter<>(CtLiteral.class))) {
      if (!(lit.getValue() instanceof String) || lit.getParent(CtAnnotation.class) != null) {
        continue;
      }
      CtExpression<?> root = lit;
      while (root.getParent() instanceof CtBinaryOperator<?> op && op.getKind() == BinaryOperatorKind.PLUS) {
        root = op;
      }
      if (roots.add(root)) {
        ordered.add(root);
      }
    }
    ordered.sort(Comparator.comparingInt(Provenance::offset));
    for (CtExpression<?> root : ordered) {
      if (root.getElements(new TypeFilter<>(CtLiteral.class)).stream().anyMatch(consumed::contains)
          || excludedContext(root)) {
        continue;
      }
      PartialString sql = ctx.eval().eval(root);
      if (!SqlAnalyzer.looksLikeSql(sql.render())) {
        continue;
      }
      add(STRING_LITERAL, sql, false, Callers.of(root), root, hint(null, root));
    }
  }

  /** Littéral passé à une méthode de journalisation, à une exception ou à une requête JPQL. */
  private static boolean excludedContext(CtExpression<?> root) {
    CtElement parent = root.getParent();
    if (parent instanceof CtInvocation<?> inv && inv.getArguments().contains(root)) {
      String name = inv.getExecutable().getSimpleName();
      return LOG_METHODS.contains(name) || JPQL_METHODS.contains(name);
    }
    if (parent instanceof CtConstructorCall<?> call && !(parent instanceof CtNewClass<?>)) {
      String type = call.getType() == null ? "" : call.getType().getSimpleName();
      return type.endsWith("Exception") || type.endsWith("Error");
    }
    return false;
  }

  // ---------------------------------------------------------------- littéraux consommés

  private void consume(CtExpression<?> e) {
    collect(e, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
  }

  /** Littéraux qui contribuent à la valeur de {@code e} : expression, constantes, variables, StringBuilder. */
  private void collect(CtElement e, Set<CtElement> seen, int depth) {
    if (e == null || depth > MAX_DEPTH || !seen.add(e)) {
      return;
    }
    consumed.addAll(e.getElements(new TypeFilter<>(CtLiteral.class)));
    for (CtFieldRead<?> fr : e.getElements(new TypeFilter<>(CtFieldRead.class))) {
      CtField<?> f = ctx.eval().field(fr);
      if (f != null) {
        collect(f.getDefaultExpression(), seen, depth + 1);
        if (f.getDeclaringType() != null) {
          for (CtAssignment<?, ?> a : f.getDeclaringType().getElements(new TypeFilter<>(CtAssignment.class))) {
            if (a.getAssigned() instanceof CtFieldWrite<?> fw
                && fw.getVariable().getSimpleName().equals(f.getSimpleName())) {
              collect(a.getAssignment(), seen, depth + 1);
            }
          }
        }
      }
    }
    for (CtVariableRead<?> vr : e.getElements(new TypeFilter<>(CtVariableRead.class))) {
      if (vr instanceof CtFieldRead<?>) {
        continue;
      }
      CtVariable<?> decl = vr.getVariable().getDeclaration();
      if (decl instanceof CtLocalVariable<?> lv) {
        collect(lv.getDefaultExpression(), seen, depth + 1);
        CtExecutable<?> scope = lv.getParent(CtExecutable.class);
        if (scope == null) {
          continue;
        }
        for (CtAssignment<?, ?> a : scope.getElements(new TypeFilter<>(CtAssignment.class))) {
          if (a.getAssigned() instanceof CtVariableWrite<?> vw && refersTo(vw.getVariable(), lv)) {
            collect(a.getAssignment(), seen, depth + 1);
          }
        }
        for (CtInvocation<?> inv : scope.getElements(new TypeFilter<>(CtInvocation.class))) {
          if (APPEND_METHODS.contains(inv.getExecutable().getSimpleName()) && rootIs(inv, lv)) {
            for (CtExpression<?> arg : inv.getArguments()) {
              collect(arg, seen, depth + 1);
            }
          }
        }
      }
    }
    for (CtInvocation<?> inv : e.getElements(new TypeFilter<>(CtInvocation.class))) {
      if (inv.getExecutable().getExecutableDeclaration() instanceof CtMethod<?> m && m.getBody() != null) {
        for (CtReturn<?> r : m.getBody().getElements(new TypeFilter<>(CtReturn.class))) {
          collect(r.getReturnedExpression(), seen, depth + 1);
        }
      }
    }
  }

  private static boolean refersTo(CtVariableReference<?> ref, CtLocalVariable<?> lv) {
    return ref.getDeclaration() == lv;
  }

  /** Vrai si la chaîne d'appels {@code sb.append(..).append(..)} part de la variable {@code lv}. */
  private static boolean rootIs(CtInvocation<?> inv, CtLocalVariable<?> lv) {
    CtExpression<?> t = inv.getTarget();
    while (t instanceof CtInvocation<?> i) {
      t = i.getTarget();
    }
    return t instanceof CtVariableRead<?> vr && refersTo(vr.getVariable(), lv);
  }

  // ---------------------------------------------------------------- production

  private void add(String origin, PartialString sql, boolean mybatis, String caller, CtElement at,
      DatasourceHint hint) {
    emit(origin, sql, mybatis, caller, ctx.source(at), hint);
  }

  /**
   * Normalise, résout la configuration ({@code ${cle}}, hors MyBatis), analyse et produit l'accès.
   * SQL dynamique ou refusé par le parseur : {@code parsed: false}, tables trouvées par le repli,
   * diagnostic SQL_UNPARSED.
   */
  private void emit(String origin, PartialString sql, boolean mybatis, String caller, Source source,
      DatasourceHint hint) {
    boolean dynamic = sql.dynamic() || !sql.isComplete();
    String text = SqlAnalyzer.normalize(sql.render());
    if (!mybatis && text.contains("${")) {
      Config.Resolution r = ctx.config().resolve(text);
      text = SqlAnalyzer.normalize(r.value());
      dynamic |= !r.complete();
    }
    if (mybatis && text.contains("${")) {
      dynamic = true;
    }
    if (text.isEmpty()) {
      return;
    }
    String parseable = SqlAnalyzer.normalize(dynamic ? sql.withUnknownsAs("?") : sql.render());
    if (!mybatis && parseable.contains("${")) {
      parseable = ctx.config().resolve(parseable).value();
    }
    if (mybatis) {
      parseable = SqlAnalyzer.withoutMyBatisParams(parseable);
    }
    if (JDBI.equals(origin)) {
      parseable = SqlAnalyzer.withoutJdbiParams(parseable);
    }
    SqlAnalyzer.Analysis analysis = SqlAnalyzer.analyze(parseable);
    boolean parsed = analysis.parsed() && !dynamic;
    String where = caller != null ? caller : hint.owner();
    if (!parsed) {
      String reason = dynamic
          ? "SQL construit dynamiquement" + unknowns(sql)
          : "refusé par JSqlParser : " + analysis.error();
      ctx.diagnostics().warning("SQL_UNPARSED", reason + " (" + origin + ", " + where + ")", source);
    }
    String id = ctx.appId() + ":sql:" + Hashes.sha1(text) + "@" + (where == null ? "" : where);
    SqlAccess access = new SqlAccess(id, origin, null, text, analysis.tables(), parsed, caller, source);
    out.add(new SqlDraft(access, hint));
  }

  private static String unknowns(PartialString sql) {
    String names = sql.parts().stream()
        .filter(p -> p instanceof PartialString.Unknown)
        .map(p -> ((PartialString.Unknown) p).name())
        .distinct()
        .collect(Collectors.joining(", "));
    return names.isEmpty() ? "" : " (valeurs inconnues : " + names + ")";
  }

  private static String literalText(PartialString sql) {
    return String.join("", sql.literals());
  }
}
