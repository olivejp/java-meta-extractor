package fr.cafat.meta.extract.rest;

import fr.cafat.meta.spoon.Annotations;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import spoon.reflect.code.CtExpression;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;

/** Lecture des annotations de mapping Spring Web, communes aux contrôleurs et aux clients Feign. */
public final class SpringMappings {

  public static final List<String> HTTP = List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS",
      "TRACE");
  private static final Map<String, String> VERBS = Map.of("GetMapping", "GET", "PostMapping", "POST",
      "PutMapping", "PUT", "DeleteMapping", "DELETE", "PatchMapping", "PATCH");
  private static final String[] NAMES = {"RequestMapping", "GetMapping", "PostMapping", "PutMapping",
      "DeleteMapping", "PatchMapping"};

  private SpringMappings() {
  }

  /** Annotation de mapping portée par l'élément, ou null. */
  public static CtAnnotation<?> find(CtElement element) {
    return Annotations.findAny(element, Annotations.SPRING_WEB, NAMES);
  }

  /**
   * Méthodes HTTP déclarées : le verbe d'un {@code @XxxMapping}, sinon l'attribut {@code method} d'un
   * {@code @RequestMapping} (liste vide s'il est absent).
   */
  public static List<String> verbs(CtAnnotation<?> mapping) {
    if (mapping == null) {
      return List.of();
    }
    String verb = VERBS.get(mapping.getAnnotationType().getSimpleName());
    if (verb != null) {
      return List.of(verb);
    }
    Set<String> out = new LinkedHashSet<>();
    for (CtExpression<?> e : Annotations.values(mapping, "method")) {
      String v = Annotations.enumConstant(e);
      if (v != null && HTTP.contains(v)) {
        out.add(v);
      }
    }
    return new ArrayList<>(out);
  }

  /** Expression des chemins ({@code path}, sinon {@code value}), ou null. */
  public static CtExpression<?> pathExpression(CtAnnotation<?> mapping) {
    return mapping == null ? null : Annotations.firstValue(mapping, "path", "value");
  }
}
