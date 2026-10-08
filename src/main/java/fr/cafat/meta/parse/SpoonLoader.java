package fr.cafat.meta.parse;

import fr.cafat.meta.config.ConfigLoader;
import fr.cafat.meta.extract.Diagnostics;
import fr.cafat.meta.model.Source;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import spoon.Launcher;
import spoon.compiler.Environment;
import spoon.reflect.CtModel;
import spoon.reflect.cu.CompilationUnit;
import spoon.reflect.factory.Factory;
import spoon.support.compiler.FileSystemFile;

/**
 * Construction du modèle Spoon d'une application, en noClasspath : sources Java ajoutées une par
 * une dans l'ordre trié, puis traduction des sources Kotlin dans le même modèle.
 */
public final class SpoonLoader {

  /** Étape de traduction Kotlin, branchée par l'appelant. */
  @FunctionalInterface
  public interface KotlinStep {
    void translate(Factory factory, List<Path> ktFiles);
  }

  /**
   * Niveau de langage le plus récent connu de JDT : une syntaxe récente (variables anonymes
   * {@code _} de Java 22…) reste lisible, et l'ancien code s'analyse aussi bien.
   */
  static final int JAVA_LEVEL = latestJavaLevel();

  private static final int MIN_JAVA_LEVEL = 21;

  private SpoonLoader() {
  }

  private static int latestJavaLevel() {
    try {
      String v = org.eclipse.jdt.internal.compiler.impl.CompilerOptions.getLatestVersion();
      return Math.max(MIN_JAVA_LEVEL, Integer.parseInt(v.startsWith("1.") ? v.substring(2) : v));
    } catch (RuntimeException | LinkageError e) {
      return MIN_JAVA_LEVEL;
    }
  }

  public static CtModel load(List<Path> javaFiles, List<Path> ktFiles, KotlinStep kotlin,
      Diagnostics diagnostics, Function<Path, String> relative) {
    Launcher launcher = new Launcher();
    Environment env = launcher.getEnvironment();
    env.setNoClasspath(true);
    env.setAutoImports(false);
    env.setCommentEnabled(true);
    env.setComplianceLevel(JAVA_LEVEL);
    env.setIgnoreDuplicateDeclarations(true);
    env.setIgnoreSyntaxErrors(true);
    env.setShouldCompile(false);
    env.setLevel("OFF");
    env.setEncodingProvider((file, bytes) -> isUtf8(bytes) ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
    for (Path p : javaFiles) {
      launcher.addInputResource(new FileSystemFile(p.toFile()));
    }
    CtModel model;
    try {
      model = launcher.buildModel();
    } catch (RuntimeException e) {
      diagnostics.error("PARSE_ERROR", "échec de construction du modèle Java : " + firstLine(e.getMessage()), null);
      model = launcher.getModel();
    }
    reportMissing(launcher.getFactory(), javaFiles, diagnostics, relative);
    if (!ktFiles.isEmpty() && kotlin != null) {
      kotlin.translate(launcher.getFactory(), ktFiles);
    }
    return model;
  }

  /** Fichiers écartés par Spoon (erreur de syntaxe) : diagnostic PARSE_ERROR. */
  private static void reportMissing(Factory factory, List<Path> javaFiles, Diagnostics diagnostics,
      Function<Path, String> relative) {
    Set<String> parsed = new TreeSet<>();
    for (CompilationUnit cu : factory.CompilationUnit().getMap().values()) {
      File f = cu.getFile();
      if (f != null) {
        parsed.add(canonical(f));
      }
    }
    for (Path p : javaFiles) {
      if (!parsed.contains(canonical(p.toFile()))) {
        diagnostics.error("PARSE_ERROR", "fichier Java non analysable (erreur de syntaxe)",
            Source.file(relative.apply(p), null));
      }
    }
  }

  private static String canonical(File f) {
    try {
      return f.getCanonicalPath();
    } catch (IOException e) {
      return f.getAbsolutePath();
    }
  }

  static boolean isUtf8(byte[] bytes) {
    try {
      StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
      return true;
    } catch (CharacterCodingException e) {
      return false;
    }
  }

  private static String firstLine(String s) {
    if (s == null) {
      return "erreur inconnue";
    }
    int nl = s.indexOf('\n');
    return nl < 0 ? s : s.substring(0, nl);
  }

  /** Décodage d'un fichier texte du dépôt (UTF-8 strict, sinon ISO-8859-1). */
  public static String decode(byte[] bytes) {
    return ConfigLoader.decode(bytes);
  }
}
