package fr.cafat.meta.parse.kotlin;

import org.jetbrains.kotlin.cli.FrontendConfigurationKeysKt;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar;
import org.jetbrains.kotlin.config.CommonConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtPsiFactory;

/**
 * Accès au parseur PSI du compilateur Kotlin embarqué. L'environnement (coûteux) est créé une seule
 * fois, à la première demande, puis partagé. Aucune résolution sémantique n'est faite : seul l'arbre
 * syntaxique est utilisé.
 */
public final class KotlinPsi {

  private static volatile KotlinPsi instance;

  private final KtPsiFactory factory;

  private KotlinPsi() {
    // Évite les accès natifs du VFS IntelliJ (inutiles pour des fichiers en mémoire)
    if (System.getProperty("idea.io.use.nio2") == null) {
      System.setProperty("idea.io.use.nio2", "true");
    }
    if (System.getProperty("idea.home.path") == null) {
      System.setProperty("idea.home.path", System.getProperty("java.io.tmpdir"));
    }
    CompilerConfiguration configuration = new CompilerConfiguration();
    configuration.put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY,
        MessageCollector.Companion.getNONE());
    configuration.put(CommonConfigurationKeys.MODULE_NAME, "java-meta-extractor");
    // Kotlin 2.x exige un stockage d'extensions (vide : aucun plugin de compilation)
    FrontendConfigurationKeysKt.setExtensionsStorage(configuration,
        new CompilerPluginRegistrar.ExtensionStorage());
    Disposable disposable = Disposer.newDisposable("java-meta-extractor");
    KotlinCoreEnvironment environment = KotlinCoreEnvironment.createForProduction(disposable,
        configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES);
    Project project = environment.getProject();
    this.factory = new KtPsiFactory(project, false);
  }

  /**
   * Instance partagée, créée paresseusement et de façon sûre entre threads.
   *
   * @return instance unique ; le premier appel démarre l'environnement du compilateur Kotlin (coûteux)
   */
  public static KotlinPsi get() {
    KotlinPsi local = instance;
    if (local == null) {
      synchronized (KotlinPsi.class) {
        local = instance;
        if (local == null) {
          local = new KotlinPsi();
          instance = local;
        }
      }
    }
    return local;
  }

  /**
   * Parse un texte Kotlin. Les erreurs de syntaxe ne lèvent pas d'exception : elles apparaissent
   * comme des {@code PsiErrorElement} dans l'arbre.
   *
   * @param fileName nom du fichier, extension {@code .kt} ou {@code .kts}
   * @param text contenu brut du fichier, normalisé par {@link #normalize(String)}
   * @return arbre PSI du fichier ; offsets relatifs au texte normalisé
   */
  public KtFile parse(String fileName, String text) {
    String normalized = normalize(text);
    synchronized (this) {
      return factory.createFile(fileName, normalized);
    }
  }

  /**
   * Texte tel que vu par le PSI : fins de ligne LF et pas de BOM. Les offsets du PSI se rapportent à
   * ce texte, pas au contenu brut du fichier.
   *
   * @param text contenu brut, obligatoire
   * @return texte aux fins de ligne LF, sans BOM initial
   */
  public static String normalize(String text) {
    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
    if (normalized.startsWith("\uFEFF")) {
      normalized = normalized.substring(1);
    }
    return normalized;
  }
}
