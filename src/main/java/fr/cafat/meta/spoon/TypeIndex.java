package fr.cafat.meta.spoon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

/**
 * Index déterministe des types du dépôt (y compris imbriqués et types issus du Kotlin), par nom
 * qualifié et par nom simple, avec le graphe des sous-types.
 */
public final class TypeIndex {

  private final Map<String, CtType<?>> byQualifiedName = new TreeMap<>();
  private final Map<String, List<CtType<?>>> bySimpleName = new TreeMap<>();
  private final List<CtType<?>> all = new ArrayList<>();

  /**
   * Indexe tous les types du modèle, types imbriqués compris. Premier type gardé en cas de doublon.
   *
   * @param model modèle Spoon ; obligatoire
   */
  public TypeIndex(CtModel model) {
    List<CtType<?>> roots = new ArrayList<>(model.getAllTypes());
    roots.sort(Comparator.comparing(CtType::getQualifiedName));
    for (CtType<?> t : roots) {
      register(t);
    }
    all.sort(Comparator.comparing(CtType::getQualifiedName));
  }

  private void register(CtType<?> type) {
    if (byQualifiedName.putIfAbsent(type.getQualifiedName(), type) == null) {
      all.add(type);
      bySimpleName.computeIfAbsent(type.getSimpleName(), k -> new ArrayList<>()).add(type);
    }
    List<CtType<?>> nested = new ArrayList<>(type.getNestedTypes());
    nested.sort(Comparator.comparing(CtType::getQualifiedName));
    for (CtType<?> n : nested) {
      register(n);
    }
  }

  /**
   * Tous les types triés par nom qualifié.
   *
   * @return types du dépôt, imbriqués compris ; liste interne, à ne pas modifier
   */
  public List<CtType<?>> all() {
    return all;
  }

  /**
   * Type du dépôt de ce nom qualifié.
   *
   * @param qualifiedName nom qualifié ; null accepté
   * @return type, ou null si absent du dépôt ou nom null
   */
  public CtType<?> get(String qualifiedName) {
    return qualifiedName == null ? null : byQualifiedName.get(qualifiedName);
  }

  /**
   * Résout une référence vers un type du dépôt : par nom qualifié, sinon (référence non résolue en
   * noClasspath) par nom simple s'il est unique.
   *
   * @param ref référence de type ; null accepté
   * @return type du dépôt, ou null si absent, ambigu ou référence null
   */
  public CtType<?> resolve(CtTypeReference<?> ref) {
    if (ref == null) {
      return null;
    }
    CtType<?> t = byQualifiedName.get(ref.getQualifiedName());
    if (t != null) {
      return t;
    }
    List<CtType<?>> candidates = bySimpleName.getOrDefault(ref.getSimpleName(), List.of());
    return candidates.size() == 1 ? candidates.get(0) : null;
  }

  /**
   * Types du dépôt de ce nom simple.
   *
   * @param simpleName nom simple
   * @return types dans l'ordre d'indexation (parent avant ses types imbriqués) ; liste vide si aucun
   */
  public List<CtType<?>> bySimpleName(String simpleName) {
    return bySimpleName.getOrDefault(simpleName, List.of());
  }

  /**
   * Sous-types directs (classe parente ou interface implémentée) d'un type du dépôt.
   *
   * @param parent type du dépôt
   * @return sous-types triés par nom qualifié ; liste vide si aucun
   */
  public List<CtType<?>> directSubtypes(CtType<?> parent) {
    List<CtType<?>> out = new ArrayList<>();
    for (CtType<?> t : all) {
      CtTypeReference<?> sup = t.getSuperclass();
      if (sup != null && resolve(sup) == parent) {
        out.add(t);
        continue;
      }
      for (CtTypeReference<?> itf : t.getSuperInterfaces()) {
        if (resolve(itf) == parent) {
          out.add(t);
          break;
        }
      }
    }
    return out;
  }
}
