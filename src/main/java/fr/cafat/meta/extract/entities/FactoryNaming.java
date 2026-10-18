package fr.cafat.meta.extract.entities;

import fr.cafat.meta.config.Config;
import fr.cafat.meta.extract.Diagnostics;
import fr.cafat.meta.spoon.Annotations;
import fr.cafat.meta.spoon.PartialString;
import fr.cafat.meta.spoon.TypeIndex;
import fr.cafat.meta.spoon.Types;
import fr.cafat.meta.spoon.ValueEval;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * Stratégie de nommage des entités d'une fabrique d'EntityManager déclarée à la main dans une
 * application Spring Boot.
 *
 * <p>Spring Boot n'applique ses stratégies ({@code CamelCaseToUnderscoresNamingStrategy},
 * {@code SpringImplicitNamingStrategy}) qu'aux fabriques qu'il construit : auto-configuration ou
 * {@code EntityManagerFactoryBuilder}. Une méthode {@code @Bean} qui crée elle-même un
 * {@code LocalContainerEntityManagerFactoryBean} (sans {@code EntityManagerFactoryBuilder}) obtient les
 * défauts d'Hibernate : noms explicites tels qu'écrits, noms implicites JPA. Ses paquets scannés
 * ({@code setPackagesToScan}) prennent cette stratégie, ou celle passée à la fabrique
 * ({@code hibernate.physical_naming_strategy}, {@code hibernate.implicit_naming_strategy}) dans la
 * classe de la méthode.
 */
public final class FactoryNaming {

  private static final Set<String> SPRING_CONTEXT = Set.of("org.springframework.context.annotation");
  private static final String FACTORY = "LocalContainerEntityManagerFactoryBean";
  private static final String BUILDER = "EntityManagerFactoryBuilder";
  private static final String PHYSICAL = "hibernate.physical_naming_strategy";
  private static final String IMPLICIT = "hibernate.implicit_naming_strategy";

  private FactoryNaming() {
  }

  /**
   * Stratégie globale complétée des stratégies des fabriques déclarées à la main.
   *
   * @param base stratégie de l'application ({@link NamingStrategy#detect})
   * @param index types du dépôt
   * @param eval évaluateur des paquets et des propriétés
   * @param config configuration effective, pour les propriétés lues dans l'Environment
   * @param springBoot true si l'application est Spring Boot ; sinon {@code base} est rendue telle quelle
   * @param diagnostics collecteur du {@code NAMING_STRATEGY_UNKNOWN}
   * @return {@code base}, avec une stratégie par paquet scanné par une fabrique déclarée à la main
   */
  public static NamingStrategy apply(NamingStrategy base, TypeIndex index, ValueEval eval, Config config,
      boolean springBoot, Diagnostics diagnostics) {
    if (!springBoot) {
      return base;
    }
    Map<String, NamingStrategy> byPackage = new TreeMap<>();
    for (CtType<?> t : index.all()) {
      for (CtMethod<?> m : t.getMethods()) {
        if (m.getBody() == null || Annotations.find(m, SPRING_CONTEXT, "Bean") == null || !createsFactory(m)
            || usesBuilder(m)) {
          continue;
        }
        NamingStrategy naming = declared(t, eval, config, diagnostics);
        for (CtInvocation<?> inv : m.getBody().getElements(new TypeFilter<>(CtInvocation.class))) {
          if (inv.getExecutable() != null && "setPackagesToScan".equals(inv.getExecutable().getSimpleName())) {
            for (CtExpression<?> arg : inv.getArguments()) {
              for (PartialString p : eval.evalAll(arg)) {
                String pkg = p.valueOrNull();
                if (pkg != null && !pkg.isBlank()) {
                  byPackage.putIfAbsent(pkg.strip(), naming);
                }
              }
            }
          }
        }
      }
    }
    return byPackage.isEmpty() ? base : base.withPackages(byPackage);
  }

  private static boolean createsFactory(CtMethod<?> m) {
    return m.getBody().getElements(new TypeFilter<>(CtConstructorCall.class)).stream()
        .anyMatch(c -> FACTORY.equals(Types.simpleName(c.getType())));
  }

  private static boolean usesBuilder(CtMethod<?> m) {
    if (m.getParameters().stream().anyMatch(p -> BUILDER.equals(Types.simpleName(p.getType())))) {
      return true;
    }
    return m.getBody().getElements(new TypeFilter<>(CtElement.class)).stream()
        .anyMatch(e -> e instanceof CtExpression<?> x && BUILDER.equals(Types.simpleName(Types.typeOf(x))));
  }

  /**
   * Stratégie passée à la fabrique dans sa classe ({@code put("hibernate.physical_naming_strategy", …)}),
   * sinon défauts d'Hibernate.
   */
  private static NamingStrategy declared(CtType<?> owner, ValueEval eval, Config config, Diagnostics diagnostics) {
    String physical = property(owner, PHYSICAL, eval, config);
    String implicit = property(owner, IMPLICIT, eval, config);
    boolean springImplicit = implicit != null && implicit.endsWith("SpringImplicitNamingStrategy");
    return physical == null ? new NamingStrategy(NamingStrategy.Physical.IDENTITY, springImplicit)
        : NamingStrategy.ofClass(physical, springImplicit, diagnostics);
  }

  /** Nom qualifié de {@code X.class.getName()} ; null sinon. */
  private static String classLiteralName(CtExpression<?> v) {
    if (v instanceof CtInvocation<?> call && call.getExecutable() != null
        && "getName".equals(call.getExecutable().getSimpleName())
        && call.getTarget() instanceof CtFieldRead<?> f && "class".equals(f.getVariable().getSimpleName())
        && f.getTarget() instanceof CtTypeAccess<?> ta && ta.getAccessedType() != null) {
      return ta.getAccessedType().getQualifiedName();
    }
    return null;
  }

  /** Valeur constante (ou résolue par la configuration) d'un {@code put(cle, valeur)} de la classe ; null sinon. */
  private static String property(CtType<?> owner, String key, ValueEval eval, Config config) {
    for (CtInvocation<?> inv : owner.getElements(new TypeFilter<>(CtInvocation.class))) {
      List<CtExpression<?>> args = inv.getArguments();
      if (args.size() == 2 && inv.getExecutable() != null
          && List.of("put", "setProperty").contains(inv.getExecutable().getSimpleName())
          && key.equals(eval.constant(args.get(0)))) {
        CtExpression<?> v = args.get(1);
        String value = eval.constant(v);
        if (value == null) {
          value = classLiteralName(v);
        }
        if (value != null) {
          Config.Resolution r = config.resolve(value);
          return r.complete() && !r.value().isBlank() ? r.value().strip() : null;
        }
      }
    }
    return null;
  }
}
