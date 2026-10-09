package fr.cafat.meta.spoon;

import fr.cafat.meta.model.Source;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import spoon.reflect.cu.CompilationUnit;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.cu.position.CompoundSourcePosition;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;

/**
 * Calcule la provenance (fichier relatif, ligne, classe) d'un élément Spoon. Les éléments issus du
 * Kotlin portent leur position dans les métadonnées {@link #META_FILE} et {@link #META_LINE}.
 *
 * <p>Convention de ligne : ligne du nom de la déclaration (classe, champ, méthode), sinon première
 * ligne de l'élément.
 */
public final class Provenance {

  public static final String META_FILE = "meta.file";
  public static final String META_LINE = "meta.line";
  public static final String META_LANG = "meta.lang";
  public static final String LANG_KOTLIN = "kotlin";

  private final Path root;
  private final Map<File, String> relativeCache = new HashMap<>();

  /**
   * Calculateur de provenance relatif à un dépôt.
   *
   * @param root racine du dépôt ; liens symboliques résolus si le chemin existe
   */
  public Provenance(Path root) {
    this.root = realPath(root);
  }

  /**
   * Provenance complète de l'élément.
   *
   * @param element élément Spoon ; null accepté
   * @return classe, fichier et ligne ; chaque champ null si inconnu
   */
  public Source of(CtElement element) {
    return new Source(className(element), file(element), line(element));
  }

  /**
   * Nom qualifié du type (de plus haut niveau ou imbriqué) qui contient l'élément.
   *
   * @param element élément Spoon ; null accepté
   * @return nom qualifié du type englobant, ou l'élément lui-même s'il est un type ; null si aucun
   */
  public static String className(CtElement element) {
    if (element == null) {
      return null;
    }
    CtType<?> type = element instanceof CtType<?> t ? t : element.getParent(CtType.class);
    return type == null ? null : type.getQualifiedName();
  }

  /**
   * Fichier de l'élément ou de son premier parent positionné.
   *
   * @param element élément Spoon ; null accepté
   * @return chemin relatif au dépôt, séparateur {@code /} ; null si aucune position connue
   */
  public String file(CtElement element) {
    for (CtElement e = element; e != null; e = e.isParentInitialized() ? e.getParent() : null) {
      Object meta = e.getMetadata(META_FILE);
      if (meta instanceof String s) {
        return s;
      }
      SourcePosition pos = e.getPosition();
      if (pos != null && pos.isValidPosition() && pos.getFile() != null) {
        return relative(pos.getFile());
      }
    }
    return null;
  }

  /**
   * Ligne de l'élément ou de son premier parent positionné.
   *
   * @param element élément Spoon ; null accepté
   * @return ligne 1-based (ligne du nom pour une déclaration) ; null si aucune position connue
   */
  public Integer line(CtElement element) {
    for (CtElement e = element; e != null; e = e.isParentInitialized() ? e.getParent() : null) {
      Object meta = e.getMetadata(META_LINE);
      if (meta instanceof Integer i) {
        return i;
      }
      SourcePosition pos = e.getPosition();
      if (pos != null && pos.isValidPosition()) {
        if (pos instanceof CompoundSourcePosition cp && cp.getNameStart() >= 0) {
          CompilationUnit cu = pos.getCompilationUnit();
          int[] seps = cu == null ? null : cu.getLineSeparatorPositions();
          if (seps != null) {
            return lineOf(seps, cp.getNameStart());
          }
        }
        return pos.getLine();
      }
    }
    return null;
  }

  /**
   * Vrai si l'élément ou l'un de ses parents vient d'un fichier Kotlin.
   *
   * @param element élément Spoon ; null accepté
   * @return true si marqué {@link #LANG_KOTLIN} par la traduction Kotlin
   */
  public static boolean isKotlin(CtElement element) {
    for (CtElement e = element; e != null; e = e.isParentInitialized() ? e.getParent() : null) {
      if (LANG_KOTLIN.equals(e.getMetadata(META_LANG))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Position absolue servant à ordonner des éléments d'un même fichier.
   *
   * @param element élément Spoon ; obligatoire
   * @return décalage en caractères depuis le début du fichier ; -1 si position inconnue
   */
  public static int offset(CtElement element) {
    Object meta = element.getMetadata("meta.offset");
    if (meta instanceof Integer i) {
      return i;
    }
    SourcePosition pos = element.getPosition();
    return pos != null && pos.isValidPosition() ? pos.getSourceStart() : -1;
  }

  static int lineOf(int[] separators, int offset) {
    int lo = 0;
    int hi = separators.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (separators[mid] < offset) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo + 1;
  }

  /**
   * Chemin relatif à la racine (liens symboliques résolus). Résultat mis en cache par fichier.
   *
   * @param file fichier ; obligatoire
   * @return chemin relatif, séparateur {@code /} ; chemin absolu si hors du dépôt
   */
  public String relative(File file) {
    return relativeCache.computeIfAbsent(file, f -> {
      Path p = realPath(f.toPath());
      Path rel = p.startsWith(root) ? root.relativize(p) : p;
      return rel.toString().replace(File.separatorChar, '/');
    });
  }

  /**
   * Comme {@link #relative(File)}.
   *
   * @param path chemin ; obligatoire
   * @return chemin relatif, séparateur {@code /} ; chemin absolu si hors du dépôt
   */
  public String relative(Path path) {
    return relative(path.toFile());
  }

  private static Path realPath(Path p) {
    try {
      return p.toAbsolutePath().toRealPath();
    } catch (IOException e) {
      return p.toAbsolutePath().normalize();
    }
  }
}
