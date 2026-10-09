package fr.cafat.meta.extract;

import fr.cafat.meta.config.Config;
import fr.cafat.meta.config.PersistenceUnit;
import fr.cafat.meta.extract.entities.NamingStrategy;
import fr.cafat.meta.model.Source;
import fr.cafat.meta.scan.WebModule;
import fr.cafat.meta.spoon.Provenance;
import fr.cafat.meta.spoon.TypeIndex;
import fr.cafat.meta.spoon.ValueEval;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import spoon.reflect.CtModel;
import fr.cafat.meta.spoon.Annotations;
import spoon.reflect.code.CtExpression;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;

/**
 * Tout ce qu'un extracteur peut lire : modèle Spoon de l'application, configuration effective,
 * fichiers de ressources du périmètre et collecteur de diagnostics.
 *
 * @param resources fichiers non Java du périmètre (XML MyBatis, web.xml…), triés
 * @param webModules modules web et préfixes d'URL (un seul, de répertoire "", pour Spring Boot)
 * @param viewSchemas schémas dont toutes les tables sont des vues (comparaison sans casse)
 */
public record ExtractionContext(
    String appId,
    Path root,
    CtModel model,
    TypeIndex types,
    ValueEval eval,
    Provenance provenance,
    Diagnostics diagnostics,
    Config config,
    NamingStrategy naming,
    Set<String> viewSchemas,
    List<PersistenceUnit> persistenceUnits,
    List<Path> resources,
    List<WebModule> webModules) {

  /**
   * Provenance de l'élément : classe, fichier relatif, ligne.
   *
   * @param element élément Spoon
   * @return provenance ; champs à null quand ils sont inconnus
   */
  public Source source(CtElement element) {
    return provenance.of(element);
  }

  /**
   * Module web contenant l'élément (répertoire le plus long), sinon l'unique module, sinon racine.
   *
   * @param element élément Spoon
   * @return module web ; {@link WebModule#ROOT} si aucun module ne contient l'élément et s'il en existe
   *     plusieurs ou aucun
   */
  public WebModule webModule(CtElement element) {
    String file = provenance.file(element);
    WebModule best = null;
    for (WebModule m : webModules) {
      boolean contains = m.dir().isEmpty() || (file != null && file.startsWith(m.dir() + "/"));
      if (contains && (best == null || m.dir().length() > best.dir().length())) {
        best = m;
      }
    }
    if (best == null) {
      best = webModules.size() == 1 ? webModules.get(0) : WebModule.ROOT;
    }
    return best;
  }

  /**
   * Id d'entité.
   *
   * @param qualifiedName nom qualifié de la classe persistante
   * @return {@code <appId>:<nom qualifié>}
   */
  public String entityId(String qualifiedName) {
    return appId + ":" + qualifiedName;
  }

  /**
   * Chemin relatif à la racine du dépôt.
   *
   * @param p chemin d'un fichier
   * @return chemin relatif, séparateur {@code /} ; chemin absolu si hors du dépôt
   */
  public String relative(Path p) {
    return provenance.relative(p);
  }

  /**
   * Vrai si le schéma figure dans {@code --view-schemas}.
   *
   * @param schema nom de schéma ; null accepté
   * @return true si le schéma est un schéma de vues, insensible à la casse ; false si null
   */
  public boolean isViewSchema(String schema) {
    if (schema == null) {
      return false;
    }
    for (String s : viewSchemas) {
      if (s.toUpperCase(Locale.ROOT).equals(schema.toUpperCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Valeur constante non vide d'un attribut d'annotation, sinon null.
   *
   * @param a annotation ; null accepté
   * @param key nom de l'attribut (ex. {@code name})
   * @return valeur évaluée (littéral ou constante) ; null si annotation, attribut ou valeur absent,
   *     vide ou non constant
   */
  public String str(CtAnnotation<?> a, String key) {
    CtExpression<?> e = Annotations.value(a, key);
    if (e == null) {
      return null;
    }
    String v = eval.constant(e);
    return v == null || v.isBlank() ? null : v;
  }

  /**
   * Entier d'un attribut d'annotation (littéral ou constante), sinon null.
   *
   * @param a annotation ; null accepté
   * @param key nom de l'attribut (ex. {@code length})
   * @return entier ; null si annotation ou attribut absent, ou valeur non entière
   */
  public Integer integer(CtAnnotation<?> a, String key) {
    if (a == null) {
      return null;
    }
    Integer i = Annotations.integer(a, key);
    if (i != null) {
      return i;
    }
    String s = str(a, key);
    try {
      return s == null ? null : Integer.valueOf(s.strip());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
