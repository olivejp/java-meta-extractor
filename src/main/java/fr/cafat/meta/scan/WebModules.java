package fr.cafat.meta.scan;

import fr.cafat.meta.config.Config;
import fr.cafat.meta.extract.Diagnostics;
import fr.cafat.meta.model.Source;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Modules web d'une unité déployable et leurs préfixes d'URL. */
public final class WebModules {

  private static final Set<String> JAXRS_SERVLETS = Set.of("javax.ws.rs.core.Application",
      "jakarta.ws.rs.core.Application", "org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher",
      "org.glassfish.jersey.servlet.ServletContainer", "com.sun.jersey.spi.container.servlet.ServletContainer");

  private WebModules() {
  }

  public static List<WebModule> detect(RepoScanner.DeployableUnit unit, Config config, Diagnostics diagnostics,
      java.util.function.Function<Path, String> relative) {
    if ("boot".equals(unit.kind()) || "repo".equals(unit.kind())) {
      String context = normalize(config.first("server.servlet.context-path", "server.context-path"));
      String mvc = normalize(config.get("spring.mvc.servlet.path"));
      String jersey = config.get("spring.jersey.application-path");
      return List.of(new WebModule("", context, mvc, jersey == null ? List.of() : List.of(normalize(jersey))));
    }
    Element application = "ear".equals(unit.kind()) ? applicationXml(unit.main(), diagnostics, relative) : null;
    List<WebModule> out = new ArrayList<>();
    for (Module m : unit.modules()) {
      if (!"war".equals(m.packaging())) {
        continue;
      }
      String context = null;
      if (application != null) {
        context = earContextRoot(application, m);
      }
      if (context == null) {
        context = jbossContextRoot(m, diagnostics, relative);
      }
      if (context == null) {
        context = "ear".equals(unit.kind()) ? m.artifactId()
            : m.finalName() != null ? m.finalName() : m.artifactId() + (m.version() == null ? "" : "-" + m.version());
      }
      out.add(new WebModule(m.relativeDir(), normalize(context), "", webXmlJaxrs(m, diagnostics, relative)));
    }
    return out;
  }

  /** Préfixe normalisé : "" pour la racine, sinon commence par "/" sans "/" final. */
  public static String normalize(String path) {
    if (path == null) {
      return "";
    }
    String p = path.strip();
    if (p.endsWith("/*")) {
      p = p.substring(0, p.length() - 2);
    }
    p = p.replaceAll("/{2,}", "/");
    while (p.endsWith("/")) {
      p = p.substring(0, p.length() - 1);
    }
    if (p.isEmpty()) {
      return "";
    }
    return p.startsWith("/") ? p : "/" + p;
  }

  private static Element applicationXml(Module ear, Diagnostics diagnostics,
      java.util.function.Function<Path, String> relative) {
    Path f = ear.dir().resolve("src/main/application/META-INF/application.xml");
    Document d = read(f, diagnostics, relative);
    return d == null ? null : d.getDocumentElement();
  }

  private static String earContextRoot(Element application, Module war) {
    for (Element web : Xml.descendants(application, "web")) {
      String uri = Xml.childText(web, "web-uri");
      if (uri != null && uri.startsWith(war.artifactId())) {
        return Xml.childText(web, "context-root");
      }
    }
    return null;
  }

  private static String jbossContextRoot(Module war, Diagnostics diagnostics,
      java.util.function.Function<Path, String> relative) {
    Document d = read(war.dir().resolve("src/main/webapp/WEB-INF/jboss-web.xml"), diagnostics, relative);
    return d == null ? null : Xml.childText(d.getDocumentElement(), "context-root");
  }

  /** url-pattern des servlets JAX-RS déclarées dans web.xml. */
  private static List<String> webXmlJaxrs(Module war, Diagnostics diagnostics,
      java.util.function.Function<Path, String> relative) {
    Document d = read(war.dir().resolve("src/main/webapp/WEB-INF/web.xml"), diagnostics, relative);
    List<String> out = new ArrayList<>();
    if (d == null) {
      return out;
    }
    Element root = d.getDocumentElement();
    List<String> names = new ArrayList<>();
    for (Element s : Xml.children(root, "servlet")) {
      String name = Xml.childText(s, "servlet-name");
      String cls = Xml.childText(s, "servlet-class");
      boolean jaxrs = JAXRS_SERVLETS.contains(name) || JAXRS_SERVLETS.contains(cls);
      for (Element p : Xml.children(s, "init-param")) {
        String pn = Xml.childText(p, "param-name");
        if ("javax.ws.rs.Application".equals(pn) || "jakarta.ws.rs.Application".equals(pn)) {
          jaxrs = true;
        }
      }
      if (jaxrs && name != null) {
        names.add(name);
      }
    }
    for (Element m : Xml.children(root, "servlet-mapping")) {
      String name = Xml.childText(m, "servlet-name");
      if (names.contains(name) || JAXRS_SERVLETS.contains(name)) {
        for (Element u : Xml.children(m, "url-pattern")) {
          out.add(normalize(u.getTextContent()));
        }
      }
    }
    return out;
  }

  private static Document read(Path f, Diagnostics diagnostics, java.util.function.Function<Path, String> relative) {
    if (!Files.isRegularFile(f)) {
      return null;
    }
    try {
      return Xml.parse(f);
    } catch (IOException e) {
      diagnostics.error("CONFIG_PARSE_ERROR", "lecture impossible : " + e.getMessage(),
          Source.file(relative.apply(f), null));
      return null;
    }
  }
}
