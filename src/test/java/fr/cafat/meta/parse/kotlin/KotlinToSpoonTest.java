package fr.cafat.meta.parse.kotlin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import fr.cafat.meta.extract.Diagnostics;
import fr.cafat.meta.model.Diagnostic;
import fr.cafat.meta.spoon.Annotations;
import fr.cafat.meta.spoon.Provenance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.Launcher;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtInterface;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

class KotlinToSpoonTest {

  @TempDir
  Path dir;

  private final Diagnostics diags = new Diagnostics();

  /** Modèle Spoon construit à partir de sources Java et Kotlin (chemin relatif → contenu). */
  private Launcher build(Map<String, String> java, Map<String, String> kotlin) throws IOException {
    Launcher launcher = new Launcher();
    launcher.getEnvironment().setNoClasspath(true);
    launcher.getEnvironment().setComplianceLevel(21);
    launcher.getEnvironment().setCommentEnabled(true);
    launcher.getEnvironment().setAutoImports(false);
    for (Map.Entry<String, String> e : java.entrySet()) {
      launcher.addInputResource(write(e.getKey(), e.getValue()).toString());
    }
    launcher.buildModel();
    List<Path> kt = new ArrayList<>();
    for (Map.Entry<String, String> e : kotlin.entrySet()) {
      kt.add(write(e.getKey(), e.getValue()));
    }
    new KotlinToSpoon(launcher.getFactory(), dir, diags).translate(kt);
    return launcher;
  }

  private Path write(String rel, String content) throws IOException {
    Path p = dir.resolve(rel);
    Files.createDirectories(p.getParent());
    Files.writeString(p, content, StandardCharsets.UTF_8);
    return p;
  }

  private static CtType<?> type(Launcher l, String qn) {
    return l.getFactory().getModel().getAllTypes().stream()
        .filter(t -> t.getQualifiedName().equals(qn)).findFirst().orElse(null);
  }

  private static CtInvocation<?> invocation(CtType<?> t, String name) {
    return t.getElements(new TypeFilter<>(CtInvocation.class)).stream()
        .filter(i -> name.equals(i.getExecutable().getSimpleName())).findFirst().orElse(null);
  }

  private List<Diagnostic> errors() {
    return diags.all().stream().filter(d -> Diagnostics.ERROR.equals(d.level())).toList();
  }

  @Test
  void entiteKotlinAvecConstructeurPrimaireAnnote() throws IOException {
    Launcher l = build(Map.of("src/main/java/fr/x/domain/Base.java", """
        package fr.x.domain;
        public abstract class Base { protected Long id; }
        """), Map.of(
        "src/main/kotlin/fr/x/domain/MoyenContact.kt", """
            package fr.x.domain

            import javax.persistence.Entity

            @Entity
            class MoyenContact(val valeur: String)
            """,
        "src/main/kotlin/fr/x/domain/Personne.kt", """
            package fr.x.domain

            import javax.persistence.*

            @Entity
            @Table(name = "T_PERSONNE", schema = "MGENGPP")
            @Inheritance(strategy = InheritanceType.JOINED)
            class Personne(
                @Column(name = "NOM", nullable = false)
                val nom: String,
                @OneToMany(mappedBy = "personne")
                val contacts: List<MoyenContact> = emptyList(),
                var prenom: String? = null
            ) : Base()
            """));

    CtClass<?> p = (CtClass<?>) type(l, "fr.x.domain.Personne");
    assertThat(p).isNotNull();
    assertThat(p.getMetadata(Provenance.META_LANG)).isEqualTo("kotlin");
    assertThat(p.getMetadata(Provenance.META_FILE))
        .isEqualTo("src/main/kotlin/fr/x/domain/Personne.kt");
    assertThat(p.getMetadata(Provenance.META_LINE)).isEqualTo(8);
    assertThat(p.getSuperclass().getQualifiedName()).isEqualTo("fr.x.domain.Base");

    CtAnnotation<?> table = Annotations.find(p, Annotations.JPA, "Table");
    assertThat(table).isNotNull();
    assertThat(((CtLiteral<?>) Annotations.value(table, "name")).getValue())
        .isEqualTo("T_PERSONNE");
    CtAnnotation<?> inh = Annotations.find(p, Annotations.JPA, "Inheritance");
    assertThat(Annotations.enumConstant(Annotations.value(inh, "strategy"))).isEqualTo("JOINED");

    CtField<?> nom = p.getField("nom");
    assertThat(nom.isFinal()).isTrue();
    assertThat(nom.getType().getQualifiedName()).isEqualTo("java.lang.String");
    assertThat(nom.getMetadata(Provenance.META_LINE)).isEqualTo(10);
    CtAnnotation<?> col = Annotations.find(nom, Annotations.JPA, "Column");
    assertThat(((CtLiteral<?>) Annotations.value(col, "name")).getValue()).isEqualTo("NOM");

    CtField<?> contacts = p.getField("contacts");
    assertThat(contacts.getType().getQualifiedName()).isEqualTo("java.util.List");
    List<CtTypeReference<?>> args = contacts.getType().getActualTypeArguments();
    assertThat(args).extracting(CtTypeReference::getQualifiedName)
        .containsExactly("fr.x.domain.MoyenContact");
    assertThat(Annotations.has(contacts, Annotations.JPA, "OneToMany")).isTrue();

    CtField<?> prenom = p.getField("prenom");
    assertThat(prenom.isFinal()).isFalse();
    assertThat(prenom.getMetadata(KotlinToSpoon.META_NULLABLE)).isEqualTo(Boolean.TRUE);
    assertThat(errors()).isEmpty();
  }

  @Test
  void enumerationOrdonnee() throws IOException {
    Launcher l = build(Map.of(), Map.of("Civilite.kt", """
        package fr.x.domain

        enum class Civilite { M, MME }
        """));
    CtType<?> t = type(l, "fr.x.domain.Civilite");
    assertThat(t).isInstanceOf(CtEnum.class);
    assertThat(((CtEnum<?>) t).getEnumValues()).extracting(CtEnumValue::getSimpleName)
        .containsExactly("M", "MME");
  }

  @Test
  void interfaceFeign() throws IOException {
    Launcher l = build(Map.of(), Map.of("GppClient.kt", """
        package fr.x.client

        import org.springframework.cloud.openfeign.FeignClient
        import org.springframework.web.bind.annotation.GetMapping
        import org.springframework.web.bind.annotation.PathVariable

        @FeignClient(name = "gpp", url = "\\${gpp.url}")
        interface GppClient {
            @GetMapping("/api/personnes/{id}")
            fun personne(@PathVariable("id") id: Long): String
        }
        """));
    CtType<?> t = type(l, "fr.x.client.GppClient");
    assertThat(t).isInstanceOf(CtInterface.class);
    CtAnnotation<?> feign = Annotations.find(t, Annotations.ANY, "FeignClient");
    assertThat(feign.getAnnotationType().getQualifiedName())
        .isEqualTo("org.springframework.cloud.openfeign.FeignClient");
    assertThat(((CtLiteral<?>) Annotations.value(feign, "url")).getValue())
        .isEqualTo("${gpp.url}");
    CtMethod<?> m = t.getMethodsByName("personne").get(0);
    assertThat(m.isAbstract()).isTrue();
    assertThat(m.getBody()).isNull();
    CtAnnotation<?> get = Annotations.find(m, Annotations.SPRING_WEB, "GetMapping");
    assertThat(((CtLiteral<?>) Annotations.value(get, "value")).getValue())
        .isEqualTo("/api/personnes/{id}");
    assertThat(m.getParameters().get(0).getType().getQualifiedName()).isEqualTo("java.lang.Long");
    assertThat(Annotations.has(m.getParameters().get(0), Annotations.SPRING_WEB, "PathVariable"))
        .isTrue();
  }

  @Test
  void champValueEtAppelRest() throws IOException {
    Launcher l = build(Map.of(), Map.of("GppService.kt", """
        package fr.x.service

        import org.springframework.beans.factory.annotation.Value
        import org.springframework.stereotype.Service
        import org.springframework.web.client.RestTemplate

        @Service
        class GppService(private val restTemplate: RestTemplate) {
            @Value("\\${gpp.url}")
            private val baseUrl: String = ""

            fun personne(id: Long): String? {
                return restTemplate.getForObject("$baseUrl/api/personnes/$id", String::class.java)
            }
        }
        """));
    CtClass<?> t = (CtClass<?>) type(l, "fr.x.service.GppService");
    CtField<?> baseUrl = t.getField("baseUrl");
    CtAnnotation<?> value = Annotations.find(baseUrl, Annotations.ANY, "Value");
    assertThat(((CtLiteral<?>) Annotations.value(value, "value")).getValue())
        .isEqualTo("${gpp.url}");

    CtInvocation<?> inv = invocation(t, "getForObject");
    assertThat(inv).isNotNull();
    assertThat(inv.getTarget().getType().getQualifiedName())
        .isEqualTo("org.springframework.web.client.RestTemplate");
    assertThat(inv.getTarget()).isInstanceOf(CtFieldRead.class);
    assertThat(((CtFieldRead<?>) inv.getTarget()).getVariable().getDeclaration())
        .isSameAs(t.getField("restTemplate"));
    CtExpression<?> url = inv.getArguments().get(0);
    assertThat(url).isInstanceOf(CtBinaryOperator.class);
    assertThat(((CtBinaryOperator<?>) url).getKind()).isEqualTo(BinaryOperatorKind.PLUS);
    assertThat(Annotations.classLiteral(inv.getArguments().get(1)).getQualifiedName())
        .isEqualTo("java.lang.String");
    assertThat(inv.getMetadata(Provenance.META_LINE)).isEqualTo(13);
  }

  @Test
  void chaineBruteTrimIndent() throws IOException {
    Launcher l = build(Map.of(), Map.of("Repo.kt", """
        package fr.x.repo

        class Repo {
            fun sql(): String = \"\"\"SELECT * FROM MGENGPP.V_X WHERE A = ?\"\"\".trimIndent()
        }
        """));
    CtType<?> t = type(l, "fr.x.repo.Repo");
    CtInvocation<?> inv = invocation(t, "trimIndent");
    assertThat(inv.getTarget()).isInstanceOf(CtLiteral.class);
    assertThat(((CtLiteral<?>) inv.getTarget()).getValue())
        .isEqualTo("SELECT * FROM MGENGPP.V_X WHERE A = ?");
    assertThat(inv.getType().getQualifiedName()).isEqualTo("java.lang.String");
  }

  @Test
  void constanteDeCompagnonDansAnnotation() throws IOException {
    Launcher l = build(Map.of(), Map.of("Ecouteur.kt", """
        package fr.x.jms

        import org.springframework.jms.annotation.JmsListener

        class Ecouteur {
            companion object {
                const val QUEUE = "Q.PERSONNE"
            }

            @JmsListener(destination = QUEUE)
            fun recevoir(message: String) {
            }
        }
        """));
    CtClass<?> t = (CtClass<?>) type(l, "fr.x.jms.Ecouteur");
    CtField<?> queue = t.getField("QUEUE");
    assertThat(queue.isStatic()).isTrue();
    assertThat(queue.isFinal()).isTrue();
    CtMethod<?> m = t.getMethodsByName("recevoir").get(0);
    CtAnnotation<?> a = Annotations.find(m, Annotations.ANY, "JmsListener");
    CtExpression<?> dest = Annotations.value(a, "destination");
    assertThat(dest).isInstanceOf(CtFieldRead.class);
    assertThat(((CtFieldRead<?>) dest).getVariable().getDeclaration()).isSameAs(queue);
    assertThat(((CtLiteral<?>) queue.getDefaultExpression()).getValue()).isEqualTo("Q.PERSONNE");
  }

  @Test
  void constanteJavaDansAnnotation() throws IOException {
    Map<String, String> java = new LinkedHashMap<>();
    java.put("src/main/java/fr/x/Consts.java", """
        package fr.x;
        public final class Consts { public static final String PREFIX = "/api"; }
        """);
    Launcher l = build(java, Map.of("Ctrl.kt", """
        package fr.x.web

        import fr.x.Consts
        import org.springframework.web.bind.annotation.GetMapping
        import org.springframework.web.bind.annotation.RestController

        @RestController
        class Ctrl {
            @GetMapping(Consts.PREFIX + "/x")
            fun x(): String = "ok"
        }
        """));
    CtType<?> t = type(l, "fr.x.web.Ctrl");
    CtMethod<?> m = t.getMethodsByName("x").get(0);
    CtExpression<?> v = Annotations.value(Annotations.find(m, Annotations.SPRING_WEB,
        "GetMapping"), "value");
    assertThat(v).isInstanceOf(CtBinaryOperator.class);
    CtExpression<?> left = ((CtBinaryOperator<?>) v).getLeftHandOperand();
    assertThat(left).isInstanceOf(CtFieldRead.class);
    CtField<?> prefix = type(l, "fr.x.Consts").getField("PREFIX");
    assertThat(((CtFieldRead<?>) left).getVariable().getDeclaration()).isSameAs(prefix);
    assertThat(m.getType().getQualifiedName()).isEqualTo("java.lang.String");
  }

  @Test
  void fichierCasseDonneUnDiagnostic() throws IOException {
    assertThatCode(() -> build(Map.of(), Map.of(
        "Casse.kt", """
            package fr.x

            class Casse {
                fun f( {
            """,
        "Sain.kt", """
            package fr.x

            class Sain
            """))).doesNotThrowAnyException();
    List<Diagnostic> errs = errors();
    assertThat(errs).hasSize(1);
    assertThat(errs.get(0).code()).isEqualTo("PARSE_ERROR");
    assertThat(errs.get(0).source().file()).isEqualTo("Casse.kt");
  }

  @Test
  void constructionsVariteesSansException() throws IOException {
    Launcher l = build(Map.of(), Map.of("src/Divers.kt", """
        @file:JvmName("Outils")
        package fr.x.divers

        import java.time.LocalDate

        const val VERSION = "1"

        fun String.crier(): String = uppercase() + "!"

        sealed class Forme {
            data class Cercle(val r: Double) : Forme()
            object Vide : Forme()
        }

        open class Service<T : Any>(private val nom: String) {
            private val cache = mutableMapOf<String, T>()
            var compteur: Int = 0
                get() = field + 1
                set(value) { field = value * 2 }

            init {
                require(nom.isNotBlank()) { "nom vide" }
            }

            constructor() : this("défaut")

            fun traiter(items: List<Pair<String, Int>>, f: (Int) -> Int = { it * 2 }): Int {
                var total = 0
                for ((k, v) in items) {
                    if (k in cache) continue
                    total += f(v)
                }
                val d = LocalDate.now()
                val label = when {
                    total > 10 -> "grand"
                    total < 0 -> { println(d); "négatif" }
                    else -> "petit"
                }
                val x = try { label.toInt() } catch (e: NumberFormatException) { -1 } finally { }
                val y = cache[label] ?: return x
                val obj = object : Runnable { override fun run() {} }
                fun local(a: Int) = a + 1
                compteur++
                cache["k"] = y
                return local(total) + (x ?: 0) + items.size + 0x1F + 1_000L.toInt()
            }
        }
        """));
    CtType<?> svc = type(l, "fr.x.divers.Service");
    assertThat(svc).isNotNull();
    assertThat(type(l, "fr.x.divers.Outils")).isNotNull();
    assertThat(type(l, "fr.x.divers.Outils").getField("VERSION")).isNotNull();
    assertThat(l.getFactory().Type().get("fr.x.divers.Forme$Cercle")).isNotNull();
    assertThat(errors()).isEmpty();
    assertThatCode(svc::toString).doesNotThrowAnyException();
  }

  @Test
  void traductionDeterministe() throws IOException {
    String src = """
        package fr.x.d

        import org.springframework.jdbc.core.JdbcTemplate

        class Dao(private val jdbcTemplate: JdbcTemplate) {
            fun lire(id: Long?): List<String> {
                val sql = if (id == null) "SELECT A FROM T" else "SELECT A FROM T WHERE ID = ?"
                val r = jdbcTemplate.queryForList(sql, String::class.java)
                when (r.size) {
                    0 -> return emptyList()
                    else -> println(r)
                }
                return r.map { it.trim() }
            }
        }
        """;
    Launcher a = build(Map.of(), Map.of("Dao.kt", src));
    String first = type(a, "fr.x.d.Dao").toString();
    assertThat(errors()).isEmpty();
    Launcher b = build(Map.of(), Map.of("Dao.kt", src));
    assertThat(type(b, "fr.x.d.Dao").toString()).isEqualTo(first);
    assertThat(invocation(type(b, "fr.x.d.Dao"), "queryForList").getTarget().getType()
        .getQualifiedName()).isEqualTo("org.springframework.jdbc.core.JdbcTemplate");
  }

  @Test
  void aliasDeTypeResolu() throws IOException {
    Launcher l = build(Map.of(), Map.of(
        "src/main/kotlin/fr/x/model/pg/Union.kt", """
            package fr.x.model.pg

            class Union(val id: Long)

            typealias PGUnion = Union
            typealias Liste<T> = List<T>
            """,
        "src/main/kotlin/fr/x/writer/UnionWriter.kt", """
            package fr.x.writer

            import fr.x.model.pg.PGUnion

            class UnionWriter(private val union: PGUnion)
            """));
    CtField<?> union = type(l, "fr.x.writer.UnionWriter").getField("union");
    assertThat(union.getType().getQualifiedName()).isEqualTo("fr.x.model.pg.Union");
    // alias générique : toujours signalé comme non traduit
    assertThat(diags.all()).extracting(Diagnostic::message)
        .containsExactly("Déclaration Kotlin de premier niveau non traduite : TypeAlias");
  }
}
