package fr.cafat.meta.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Unité de persistance déclarée dans un persistence.xml.
 *
 * @param moduleDir module qui porte le fichier (périmètre des entités en l'absence de liste)
 */
public record PersistenceUnit(
    String name,
    String jtaDataSource,
    String nonJtaDataSource,
    List<String> classes,
    boolean excludeUnlistedClasses,
    Map<String, String> properties,
    String file,
    Integer line,
    Path moduleDir) {

  public String property(String... keys) {
    for (String k : keys) {
      String v = properties.get(k);
      if (v != null && !v.isBlank()) {
        return v.strip();
      }
    }
    return null;
  }
}
