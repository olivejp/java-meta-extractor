package fr.cafat.meta.scan;

import java.util.List;

/**
 * Module web d'une unité déployable : préfixes d'URL appliqués aux endpoints dont le fichier source
 * se trouve sous {@code dir}.
 *
 * @param dir répertoire relatif au dépôt ("" : tout le périmètre)
 * @param contextPath chemin de contexte ("" si racine)
 * @param mvcPath chemin du DispatcherServlet Spring ({@code spring.mvc.servlet.path}), "" sinon
 * @param jaxrsMappings préfixes JAX-RS déclarés hors code (web.xml, {@code spring.jersey.application-path})
 */
public record WebModule(String dir, String contextPath, String mvcPath, List<String> jaxrsMappings) {

  public static final WebModule ROOT = new WebModule("", "", "", List.of());
}
