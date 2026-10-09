package fr.cafat.meta.extract;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Explication de chaque code de diagnostic, pour le rapport lisible : ce qui s'est passé, où se
 * trouve la cause, ce qui manque dans le résultat et ce qu'il faut faire. Le JSON ne porte que le
 * code et le message ; ce catalogue n'en change pas le contenu.
 */
public final class DiagnosticCatalog {

  /** Où chercher la correction. */
  public enum Origin {
    CODE("code du dépôt analysé"),
    CONFIGURATION("configuration du dépôt (pom, application*.yml, persistence.xml, web.xml)"),
    LAUNCH("options de lancement de l'extracteur"),
    RUNTIME("valeur connue seulement à l'exécution ou au déploiement"),
    OUTSIDE("code hors du dépôt (bibliothèque non analysée)"),
    EXTRACTOR("limite ou erreur de l'extracteur");

    public final String label;

    Origin(String label) {
      this.label = label;
    }
  }

  /**
   * @param title libellé court
   * @param origin où se trouve la cause
   * @param impact ce qui manque ou est incomplet dans le résultat
   * @param action ce qu'il faut faire pour corriger, ou pourquoi il n'y a rien à faire
   */
  public record Entry(String title, Origin origin, String impact, String action) {
  }

  /** Code inconnu du catalogue : le rapport l'affiche quand même. */
  public static final Entry UNKNOWN = new Entry("diagnostic non documenté", Origin.EXTRACTOR,
      "voir le message", "documenter ce code dans DiagnosticCatalog");

  /** Échec interne d'une étape de l'extraction, isolé pour que les autres étapes produisent leur résultat. */
  public static final String STEP_FAILED = "EXTRACTION_STEP_FAILED";

  private static final Map<String, Entry> ENTRIES = new TreeMap<>();

  static {
    // Erreurs de lecture
    add("PARSE_ERROR", "code source non analysable", Origin.CODE,
        "les classes du fichier (ou du lot de fichiers) sont absentes de tout le résultat",
        "ouvrir le fichier cité au commit analysé : erreur de syntaxe à corriger dans le dépôt ; "
            + "s'il compile normalement, c'est une limite de l'extracteur à signaler avec le fichier");
    add("CONFIG_PARSE_ERROR", "fichier de configuration illisible", Origin.CONFIGURATION,
        "ce fichier est ignoré : datasources, URL, files JMS ou modules qu'il déclare peuvent manquer",
        "ouvrir le fichier cité ; le message reprend l'erreur du lecteur (ligne, balise ou clé fautive)");
    add("XML_UNREADABLE", "mapper MyBatis illisible", Origin.CONFIGURATION,
        "les requêtes SQL de ce mapper sont absentes",
        "ouvrir le XML cité : il est mal formé ou n'est pas un mapper MyBatis");
    add(STEP_FAILED, "étape de l'extraction en échec (erreur interne)", Origin.EXTRACTOR,
        "la partie du résultat produite par cette étape est absente ; les autres étapes ont tourné",
        "bug de l'extracteur : relancer avec --stacktrace pour la pile complète, corriger la classe "
            + "et la ligne citées (« à … ») puis relancer");
    // Structure et lancement
    add("NO_DEPLOYABLE_MODULE", "aucun module déployable détecté", Origin.CONFIGURATION,
        "le dépôt entier est analysé comme une seule application, nommée d'après le module racine",
        "vérifier le packaging des modules (ear, war, ejb, plugin Spring Boot) ; rien à faire pour une bibliothèque");
    add("PROFILE_NOT_APPLIED", "aucun profil Spring demandé", Origin.LAUNCH,
        "seule la configuration de base est lue : les valeurs propres aux profils sont ignorées",
        "relancer avec --profile <profil> si les URL, datasources ou files de l'environnement visé "
            + "sont définies dans un profil");
    add("KOTLIN_SKIPPED", "fichiers Kotlin ignorés", Origin.LAUNCH,
        "le code Kotlin cité n'est pas analysé",
        "lancer le jar de l'extracteur complet (traduction Kotlin activée)");
    add("KOTLIN_UNSUPPORTED", "construction Kotlin non traduite", Origin.EXTRACTOR,
        "l'élément Kotlin cité est ignoré",
        "limite de l'extracteur : à compléter dans KotlinToSpoon si l'élément porte des données utiles");
    // Persistance
    add("NAMING_STRATEGY_UNKNOWN", "stratégie de nommage Hibernate personnalisée", Origin.EXTRACTOR,
        "les noms de tables et de colonnes implicites (sans @Table/@Column) sont inconnus",
        "limite de l'extracteur : implémenter la règle de la stratégie citée dans NamingStrategy");
    add("PARENT_NOT_FOUND", "classe parente hors du dépôt", Origin.OUTSIDE,
        "les colonnes héritées de cette classe parente sont inconnues",
        "rien à corriger dans le dépôt : la classe vient d'une bibliothèque ; l'analyser aussi si "
            + "ces colonnes sont nécessaires");
    add("EMBEDDABLE_NOT_FOUND", "embeddable hors du dépôt", Origin.OUTSIDE,
        "les colonnes de l'objet embarqué sont inconnues",
        "rien à corriger dans le dépôt : l'embeddable vient d'une bibliothèque");
    add("RELATION_TARGET_NOT_FOUND", "cible de relation JPA introuvable", Origin.OUTSIDE,
        "la relation est gardée sans entité cible",
        "vérifier que la classe cible est bien une @Entity ; sinon elle vient d'une bibliothèque");
    add("ENTITY_UNREFERENCED", "entité jamais utilisée dans le code", Origin.CODE,
        "rien ne manque ; la source de données de l'entité est déduite sans appelant",
        "rien à faire, ou code mort à signaler à l'équipe du dépôt");
    add("DATASOURCE_AMBIGUOUS", "source de données indéterminée", Origin.CODE,
        "l'entité ou le SQL cité n'est rattaché à aucune base",
        "trouver quelle datasource sert ce code (package scanné, @Qualifier, unité de persistance) ; "
            + "si la règle est systématique à la CAFAT, l'ajouter à DatasourceResolver");
    // SQL
    add("SQL_UNPARSED", "requête SQL non analysable", Origin.CODE,
        "les tables de cette requête sont inconnues ou incomplètes",
        "lire la requête à l'emplacement cité : SQL construit dynamiquement (valeurs citées) ou syntaxe "
            + "propre au SGBD que JSqlParser ne lit pas");
    add("SQL_UNRESOLVED", "texte SQL non calculable", Origin.RUNTIME,
        "cet accès SQL est ignoré",
        "le SQL est passé par une variable dont la valeur n'est pas calculable dans le code : "
            + "relever la requête à la main à l'emplacement cité");
    // REST
    add("URL_UNRESOLVED", "URL d'appel REST non résolue", Origin.RUNTIME,
        "l'appel est gardé sans URL complète ni application cible",
        "vérifier que les propriétés citées « inconnu : … » sont dans application*.yml du dépôt ; "
            + "si elles viennent d'un profil, relancer avec --profile ; si elles sont fournies au "
            + "déploiement, l'appel restera partiel");
    add("TARGET_APP_UNKNOWN", "application appelée non identifiée", Origin.EXTRACTOR,
        "l'URL est connue mais pas l'application CAFAT qu'elle désigne",
        "rien à faire pour un service externe ; sinon compléter la règle hôte → application de CallExtractor");
    add("HTTP_METHOD_UNRESOLVED", "verbe HTTP non déterminé", Origin.RUNTIME,
        "l'appel est gardé sans verbe",
        "lire l'appel cité : le verbe est passé par une variable");
    add("ENDPOINT_PATH_UNRESOLVED", "chemin d'endpoint incomplet", Origin.RUNTIME,
        "l'endpoint est gardé avec un chemin partiel",
        "vérifier les propriétés ou constantes citées utilisées dans les annotations de mapping");
    add("SUBRESOURCE_LOCATOR_IGNORED", "sous-ressource JAX-RS non suivie", Origin.EXTRACTOR,
        "les endpoints de la sous-ressource citée sont absents",
        "limite de l'extracteur : à suivre dans EndpointExtractor si ces endpoints sont nécessaires");
    // JMS
    add("DESTINATION_UNRESOLVED", "destination JMS non résolue", Origin.RUNTIME,
        "l'échange JMS est gardé sans file ni topic",
        "vérifier que la propriété citée est dans application*.yml ou relancer avec --profile ; "
            + "une destination calculée à l'exécution restera inconnue");
  }

  private DiagnosticCatalog() {
  }

  private static void add(String code, String title, Origin origin, String impact, String action) {
    ENTRIES.put(code, new Entry(title, origin, impact, action));
  }

  /**
   * Explication d'un code de diagnostic.
   *
   * @param code code de diagnostic (ex. {@code URL_UNRESOLVED})
   * @return entrée du catalogue ; {@link #UNKNOWN} si le code est absent du catalogue
   */
  public static Entry of(String code) {
    return ENTRIES.getOrDefault(code, UNKNOWN);
  }

  /**
   * Vrai si le code est documenté dans le catalogue.
   *
   * @param code code de diagnostic
   * @return true si le catalogue contient le code
   */
  public static boolean has(String code) {
    return ENTRIES.containsKey(code);
  }

  /**
   * Tous les codes documentés, triés.
   *
   * @return vue non modifiable du catalogue, par code
   */
  public static Map<String, Entry> all() {
    return Collections.unmodifiableMap(ENTRIES);
  }
}
