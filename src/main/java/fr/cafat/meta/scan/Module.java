package fr.cafat.meta.scan;

import java.nio.file.Path;
import java.util.List;

/**
 * Module de build (Maven ou Gradle).
 *
 * @param key identifiant interne (groupId:artifactId, ou chemin Gradle)
 * @param dir répertoire du module
 * @param relativeDir chemin relatif au dépôt ("" pour la racine)
 * @param packaging jar, war, ear, ejb, pom
 * @param dependencies clés des dépendances déclarées (internes ou externes)
 * @param children sous-modules déclarés
 * @param springBootPlugin plugin Spring Boot déclaré dans le build
 */
public record Module(
    String key,
    String artifactId,
    String version,
    Path dir,
    String relativeDir,
    String packaging,
    String finalName,
    List<String> dependencies,
    List<String> children,
    boolean springBootPlugin,
    String buildFile) {
}
