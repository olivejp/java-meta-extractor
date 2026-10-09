package fr.cafat.meta.output;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import org.junit.jupiter.api.Test;

class AssemblerTest {

  private static final Application APP = new Application("app", "app", "1.0", List.of("spring", "java", "jpa"),
      null, "abc1234", null, List.of(), null,
      List.of(new Datasource("pg", "postgresql", null, null, null, "spring.datasource.pg", Source.file("a.yml", 1)),
          new Datasource("db400", "db2", null, null, null, "spring.datasource.db400", Source.file("a.yml", 5))));

  private static ExtractionResult assemble(List<Entity> entities, List<Relation> relations, List<SqlAccess> sql,
      List<Endpoint> endpoints, List<Call> calls, List<Messaging> messaging, List<Diagnostic> diagnostics) {
    return Assembler.assemble(APP, entities, relations, sql, endpoints, calls, messaging, diagnostics);
  }

  private static ExtractionResult withDiagnostics(Diagnostic... diagnostics) {
    return assemble(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(diagnostics));
  }

  private static Column column(String table, String name) {
    return new Column(name, name, "String", table, "basic", false, null, null, null, null, null, null, null, null,
        null, null);
  }

  private static Entity entity(String id, Source source, List<Column> columns, List<SecondaryTable> secondary) {
    return new Entity(id, id.substring(4), "E", "entity", null, null, "t", null, null, null, secondary, source,
        columns);
  }

  private static SqlAccess sql(String id, String text, Source source, SqlTable... tables) {
    return new SqlAccess(id, "jdbc_template", null, text, List.of(tables), true, "C#m", source);
  }

  @Test
  void idDesDiagnosticsCalculeDuContenu() {
    Source src = Source.of("nc.C", "C.java", 12);
    ExtractionResult r = withDiagnostics(new Diagnostic(null, "warning", "URL_UNRESOLVED", "msg", src));

    String expected = "app:URL_UNRESOLVED:"
        + Hashes.sha1("warning|URL_UNRESOLVED|msg|nc.C|C.java|12").substring(0, 12);
    assertThat(r.diagnostics()).singleElement().extracting(Diagnostic::id).isEqualTo(expected);
    assertThat(withDiagnostics(new Diagnostic(null, "info", "X", "m", null)).diagnostics().get(0).id())
        .isEqualTo("app:X:" + Hashes.sha1("info|X|m|").substring(0, 12));
  }

  @Test
  void doublonsExactsRetires() {
    Diagnostic d = new Diagnostic(null, "info", "X", "m", Source.file("f", 1));
    SqlAccess s = sql("app:sql:1@C#m", "SELECT 1 FROM t", Source.of("C", "C.java", 3));

    ExtractionResult r = assemble(List.of(), List.of(), List.of(s, s), List.of(), List.of(), List.of(),
        List.of(d, d));

    assertThat(r.sqlAccesses()).hasSize(1);
    assertThat(r.diagnostics()).hasSize(1);
  }

  @Test
  void collisionsSuffixeesDansLOrdreFichierLigneContenu() {
    String id = "app:sql:abc@C#m";
    SqlAccess ligne20 = sql(id, "SELECT a FROM t", Source.of("C", "C.java", 20));
    SqlAccess ligne3 = sql(id, "SELECT b FROM t", Source.of("C", "C.java", 3));
    SqlAccess ligne3Bis = sql(id, "SELECT a FROM t", Source.of("C", "C.java", 3));
    SqlAccess autreFichier = sql(id, "SELECT z FROM t", Source.of("A", "A.java", 99));

    ExtractionResult r = assemble(List.of(), List.of(), List.of(ligne20, ligne3, ligne3Bis, autreFichier),
        List.of(), List.of(), List.of(), List.of());

    assertThat(r.sqlAccesses()).extracting(SqlAccess::id).containsExactly(id, id + "~2", id + "~3", id + "~4");
    assertThat(r.sqlAccesses()).extracting(SqlAccess::sql)
        .containsExactly("SELECT z FROM t", "SELECT a FROM t", "SELECT b FROM t", "SELECT a FROM t");
    assertThat(r.sqlAccesses().get(3).source().line()).isEqualTo(20);
  }

  @Test
  void collisionsSuffixeesPourEndpointsAppelsEtJms() {
    Source s1 = Source.of("C", "C.java", 1);
    Source s2 = Source.of("C", "C.java", 2);
    ExtractionResult r = assemble(List.of(), List.of(), List.of(),
        List.of(new Endpoint("app:GET:/x", "spring_mvc", "GET", "/x", "C#a", null, null, s2),
            new Endpoint("app:GET:/x", "spring_mvc", "GET", "/x", "C#b", null, null, s1)),
        List.of(new Call("app:GET:?:/y@C#m", "rest_template", "GET", "/y", null, "/y", null, "C#m", s1),
            new Call("app:GET:?:/y@C#m", "rest_template", "GET", "/y", null, "/y", null, "C#m", s2)),
        List.of(new Messaging("app:produce:queue:Q@C#m", "produce", "queue", "Q", "Q", "C#m", s1),
            new Messaging("app:produce:queue:Q@C#m", "produce", "queue", "Q", "Q", "C#m", s2)),
        List.of());

    assertThat(r.endpoints()).extracting(Endpoint::id).containsExactly("app:GET:/x", "app:GET:/x~2");
    assertThat(r.endpoints()).extracting(Endpoint::handler).containsExactly("C#b", "C#a");
    assertThat(r.calls()).extracting(Call::id).containsExactly("app:GET:?:/y@C#m", "app:GET:?:/y@C#m~2");
    assertThat(r.messaging()).extracting(Messaging::id)
        .containsExactly("app:produce:queue:Q@C#m", "app:produce:queue:Q@C#m~2");
  }

  @Test
  void entitesEtRelationsDeMemeIdNonSuffixees() {
    Entity a = entity("app:nc.E", Source.of("nc.E", "a/E.java", 1), List.of(), List.of());
    Entity b = entity("app:nc.E", Source.of("nc.E", "b/E.java", 1), List.of(), List.of());
    Relation r1 = new Relation("app:nc.E.f", "app:nc.E", "app:nc.F", "nc.F", "f", "MANY_TO_ONE", true, null,
        List.of("f_id"), List.of(), null, null, null, Source.of("nc.E", "a/E.java", 2));
    Relation r2 = new Relation("app:nc.E.f", "app:nc.E", "app:nc.F", "nc.F", "f", "MANY_TO_ONE", true, null,
        List.of("f_id"), List.of(), null, null, null, Source.of("nc.E", "b/E.java", 2));

    ExtractionResult r = assemble(List.of(a, b), List.of(r1, r2), List.of(), List.of(), List.of(), List.of(),
        List.of());

    assertThat(r.entities()).extracting(Entity::id).containsExactly("app:nc.E", "app:nc.E");
    assertThat(r.relations()).extracting(Relation::id).containsExactly("app:nc.E.f", "app:nc.E.f");
  }

  @Test
  void tableauxTriesParId() {
    ExtractionResult r = assemble(
        List.of(entity("app:nc.Z", null, List.of(), List.of()), entity("app:nc.A", null, List.of(), List.of())),
        List.of(), List.of(sql("app:sql:2@C#m", "SELECT 1 FROM b", null), sql("app:sql:1@C#m", "SELECT 1 FROM a", null)),
        List.of(), List.of(), List.of(),
        List.of(new Diagnostic(null, "info", "Z", "m", null), new Diagnostic(null, "info", "A", "m", null)));

    assertThat(r.entities()).extracting(Entity::id).containsExactly("app:nc.A", "app:nc.Z");
    assertThat(r.sqlAccesses()).extracting(SqlAccess::id).containsExactly("app:sql:1@C#m", "app:sql:2@C#m");
    assertThat(r.diagnostics()).extracting(Diagnostic::code).containsExactly("A", "Z");
    assertThat(r.contractVersion()).isEqualTo(ExtractionResult.CONTRACT_VERSION);
  }

  @Test
  void applicationEtContenusTries() {
    Entity e = entity("app:nc.E", null, List.of(column("t2", "b"), column(null, "z"), column("t2", "a")),
        List.of(new SecondaryTable("s", "t2", List.of()), new SecondaryTable(null, "t1", List.of())));
    SqlAccess s = sql("app:sql:1@C#m", "x", null, new SqlTable(null, "u", "read"), new SqlTable("s", "t", "write"),
        new SqlTable(null, "u", "read"));

    ExtractionResult r = assemble(List.of(e), List.of(), List.of(s), List.of(), List.of(), List.of(), List.of());

    assertThat(r.application().tech()).containsExactly("java", "jpa", "spring");
    assertThat(r.application().datasources()).extracting(Datasource::id).containsExactly("db400", "pg");
    assertThat(r.entities().get(0).columns()).extracting(Column::name).containsExactly("z", "a", "b");
    assertThat(r.entities().get(0).secondaryTables()).extracting(SecondaryTable::name).containsExactly("t1", "t2");
    assertThat(r.sqlAccesses().get(0).tables())
        .containsExactly(new SqlTable(null, "u", "read"), new SqlTable("s", "t", "write"));
  }

  @Test
  void resultatAssembleConformeAuSchema() {
    Source src = Source.of("nc.C", "C.java", 4);
    ExtractionResult r = assemble(List.of(), List.of(),
        List.of(sql("app:sql:1@C#m", "SELECT a FROM t", src, new SqlTable(null, "t", "read"))),
        List.of(new Endpoint("app:GET:/x", "spring_mvc", "GET", "/x", "C#a", null, null, src)),
        List.of(), List.of(), List.of(new Diagnostic(null, "info", "X", "m", src)));

    assertThat(SchemaValidator.validate(CanonicalJson.toTree(r))).isEmpty();
    assertThat(SchemaValidator.validate(CanonicalJson.toTree(withDiagnostics(
        new Diagnostic(null, "fatal", "X", "m", null))))).isNotEmpty();
  }
}
