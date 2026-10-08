package fr.cafat.meta.spoon;

import java.util.ArrayList;
import java.util.List;

/**
 * Chaîne évaluée statiquement : suite de morceaux littéraux et de morceaux inconnus. Les morceaux
 * inconnus sont rendus {@code {nom}} (nom de variable, de champ ou de méthode). Les espaces réservés
 * de configuration ({@code ${cle}}) restent dans le texte littéral et sont résolus plus tard.
 *
 * <p>{@code dynamic} signale une chaîne assemblée par branches (concaténations conditionnelles,
 * StringBuilder dans une boucle…), même si tous ses morceaux sont connus.
 */
public record PartialString(List<Part> parts, boolean dynamic) {

  public sealed interface Part permits Lit, Unknown {
  }

  public record Lit(String text) implements Part {
  }

  public record Unknown(String name) implements Part {
  }

  public static final PartialString EMPTY = new PartialString(List.of(), false);

  public PartialString {
    parts = List.copyOf(parts);
  }

  public static PartialString lit(String text) {
    return text == null || text.isEmpty() ? EMPTY : new PartialString(List.of(new Lit(text)), false);
  }

  public static PartialString unknown(String name) {
    return new PartialString(List.of(new Unknown(sanitize(name))), false);
  }

  public static PartialString concat(PartialString... items) {
    return concat(List.of(items));
  }

  public static PartialString concat(List<PartialString> items) {
    List<Part> out = new ArrayList<>();
    boolean dyn = false;
    for (PartialString item : items) {
      dyn |= item.dynamic;
      for (Part p : item.parts) {
        if (p instanceof Lit l && !out.isEmpty() && out.get(out.size() - 1) instanceof Lit prev) {
          out.set(out.size() - 1, new Lit(prev.text() + l.text()));
        } else if (!(p instanceof Lit l2 && l2.text().isEmpty())) {
          out.add(p);
        }
      }
    }
    return new PartialString(out, dyn);
  }

  public PartialString markDynamic() {
    return dynamic ? this : new PartialString(parts, true);
  }

  public PartialString append(PartialString other) {
    return concat(this, other);
  }

  /** Vrai si tous les morceaux sont littéraux. */
  public boolean isComplete() {
    return parts.stream().allMatch(p -> p instanceof Lit);
  }

  public boolean isEmpty() {
    return parts.isEmpty();
  }

  public boolean startsWithUnknown() {
    return !parts.isEmpty() && parts.get(0) instanceof Unknown;
  }

  /** Rendu lisible : morceaux inconnus en {@code {nom}}. */
  public String render() {
    StringBuilder sb = new StringBuilder();
    for (Part p : parts) {
      if (p instanceof Lit l) {
        sb.append(l.text());
      } else if (p instanceof Unknown u) {
        sb.append('{').append(u.name()).append('}');
      }
    }
    return sb.toString();
  }

  /** Texte avec les morceaux inconnus remplacés par {@code replacement}. */
  public String withUnknownsAs(String replacement) {
    StringBuilder sb = new StringBuilder();
    for (Part p : parts) {
      sb.append(p instanceof Lit l ? l.text() : replacement);
    }
    return sb.toString();
  }

  /** Liste des morceaux littéraux. */
  public List<String> literals() {
    List<String> out = new ArrayList<>();
    for (Part p : parts) {
      if (p instanceof Lit l) {
        out.add(l.text());
      }
    }
    return out;
  }

  /** Valeur littérale complète, ou null si un morceau est inconnu. */
  public String valueOrNull() {
    return isComplete() ? render() : null;
  }

  private static String sanitize(String name) {
    if (name == null || name.isBlank()) {
      return "?";
    }
    String s = name.replaceAll("[^A-Za-z0-9_.$-]", "");
    if (s.length() > 40) {
      s = s.substring(0, 40);
    }
    return s.isEmpty() ? "?" : s;
  }

  @Override
  public String toString() {
    return render();
  }
}
