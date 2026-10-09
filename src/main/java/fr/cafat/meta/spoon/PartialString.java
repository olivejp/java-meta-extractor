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

  /** Morceau d'une chaîne partielle. */
  public sealed interface Part permits Lit, Unknown {
  }

  /** Morceau connu. */
  public record Lit(String text) implements Part {
  }

  /** Morceau inconnu, rendu {@code {nom}}. */
  public record Unknown(String name) implements Part {
  }

  public static final PartialString EMPTY = new PartialString(List.of(), false);

  public PartialString {
    parts = List.copyOf(parts);
  }

  /**
   * Chaîne littérale.
   *
   * @param text texte connu ; null accepté
   * @return chaîne d'un morceau littéral ; {@link #EMPTY} si {@code text} est null ou vide
   */
  public static PartialString lit(String text) {
    return text == null || text.isEmpty() ? EMPTY : new PartialString(List.of(new Lit(text)), false);
  }

  /**
   * Chaîne d'un seul morceau inconnu.
   *
   * @param name nom affiché en {@code {nom}} ; caractères hors {@code [A-Za-z0-9_.$-]} retirés,
   *     40 caractères max, {@code ?} si null ou vide
   * @return chaîne non dynamique d'un morceau inconnu
   */
  public static PartialString unknown(String name) {
    return new PartialString(List.of(new Unknown(sanitize(name))), false);
  }

  /**
   * Concaténation : littéraux adjacents fusionnés, dynamique si l'un des morceaux l'est.
   *
   * @param items chaînes à concaténer, dans l'ordre ; éléments obligatoires
   * @return nouvelle chaîne, littéraux vides retirés
   */
  public static PartialString concat(PartialString... items) {
    return concat(List.of(items));
  }

  /**
   * Concaténation : littéraux adjacents fusionnés, dynamique si l'un des morceaux l'est.
   *
   * @param items chaînes à concaténer, dans l'ordre ; éléments obligatoires
   * @return nouvelle chaîne, littéraux vides retirés
   */
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

  /**
   * Copie marquée dynamique.
   *
   * @return cette instance si déjà dynamique, sinon copie dynamique
   */
  public PartialString markDynamic() {
    return dynamic ? this : new PartialString(parts, true);
  }

  /**
   * Concaténation de cette chaîne et de {@code other}.
   *
   * @param other chaîne ajoutée à la fin ; obligatoire
   * @return nouvelle chaîne, voir {@link #concat(PartialString...)}
   */
  public PartialString append(PartialString other) {
    return concat(this, other);
  }

  /**
   * Vrai si tous les morceaux sont littéraux.
   *
   * @return true si aucun morceau inconnu, y compris pour une chaîne vide
   */
  public boolean isComplete() {
    return parts.stream().allMatch(p -> p instanceof Lit);
  }

  /**
   * Vrai si aucun morceau.
   *
   * @return true si la chaîne n'a aucun morceau
   */
  public boolean isEmpty() {
    return parts.isEmpty();
  }

  /**
   * Vrai si le premier morceau est inconnu (ex. hôte calculé).
   *
   * @return true si le premier morceau est inconnu ; false si chaîne vide
   */
  public boolean startsWithUnknown() {
    return !parts.isEmpty() && parts.get(0) instanceof Unknown;
  }

  /**
   * Rendu lisible : morceaux inconnus en {@code {nom}}.
   *
   * @return texte rendu ; chaîne vide si aucun morceau
   */
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

  /**
   * Texte avec les morceaux inconnus remplacés par {@code replacement}.
   *
   * @param replacement texte mis à la place de chaque morceau inconnu
   * @return texte obtenu ; chaîne vide si aucun morceau
   */
  public String withUnknownsAs(String replacement) {
    StringBuilder sb = new StringBuilder();
    for (Part p : parts) {
      sb.append(p instanceof Lit l ? l.text() : replacement);
    }
    return sb.toString();
  }

  /**
   * Liste des morceaux littéraux.
   *
   * @return textes littéraux dans l'ordre, sans les morceaux inconnus
   */
  public List<String> literals() {
    List<String> out = new ArrayList<>();
    for (Part p : parts) {
      if (p instanceof Lit l) {
        out.add(l.text());
      }
    }
    return out;
  }

  /**
   * Valeur littérale complète, ou null si un morceau est inconnu.
   *
   * @return texte complet, ou null si un morceau est inconnu
   */
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
