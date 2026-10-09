package fr.cafat.meta.scan;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.config.Config;
import fr.cafat.meta.config.ConfigEntry;
import fr.cafat.meta.extract.Diagnostics;
import fr.cafat.meta.model.Diagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebModulesTest {

  private static void write(Path root, String file, String text) throws IOException {
    Path f = root.resolve(file);
    Files.createDirectories(f.getParent());
    Files.writeString(f, text);
  }

  private static String pom(String artifactId, String packaging, String extra) {
    return "<project><groupId>nc.cafat</groupId><artifactId>" + artifactId + "</artifactId>"
        + "<version>2.0</version><packaging>" + packaging + "</packaging>" + extra + "</project>";
  }

  private static Config config(String... keyValues) {
    Config c = Config.empty();
    for (int i = 0; i < keyValues.length; i += 2) {
      c.put(new ConfigEntry(keyValues[i], keyValues[i + 1], "application.yml", 1));
    }
    return c;
  }

  private static List<WebModule> detect(Path repo, Config config, Diagnostics diags) {
    RepoScanner scanner = new RepoScanner(repo, diags);
    return WebModules.detect(scanner.deployableUnits().get(0), config, diags, scanner::relative);
  }

  @Test
  void normalisationDesPrefixes() {
    assertThat(WebModules.normalize(null)).isEmpty();
    assertThat(WebModules.normalize(" / ")).isEmpty();
    assertThat(WebModules.normalize("api")).isEqualTo("/api");
    assertThat(WebModules.normalize("/api/")).isEqualTo("/api");
    assertThat(WebModules.normalize("/rest/*")).isEqualTo("/rest");
    assertThat(WebModules.normalize("//a//b/")).isEqualTo("/a/b");
  }

  @Test
  void springBootContexteServletEtJersey(@TempDir Path repo) throws IOException {
    write(repo, "pom.xml", pom("app", "jar", ""));
    write(repo, "src/main/java/nc/App.java", "@SpringBootApplication class App {}");

    List<WebModule> web = detect(repo, config("server.servlet.context-path", "/gpp/",
        "spring.mvc.servlet.path", "mvc", "spring.jersey.application-path", "/jersey/*"), new Diagnostics());

    assertThat(web).containsExactly(new WebModule("", "/gpp", "/mvc", List.of("/jersey")));
  }

  @Test
  void springBootContexteHistoriqueSansJersey(@TempDir Path repo) throws IOException {
    write(repo, "pom.xml", pom("app", "jar", ""));

    List<WebModule> web = detect(repo, config("server.context-path", "/ancien"), new Diagnostics());

    assertThat(web).containsExactly(new WebModule("", "/ancien", "", List.of()));
  }

  @Test
  void warHorsEarFinalNameOuArtifactIdVersion(@TempDir Path repo) throws IOException {
    write(repo, "pom.xml", pom("app", "war", "<build><finalName>gpp-web</finalName></build>"));
    assertThat(detect(repo, Config.empty(), new Diagnostics())).extracting(WebModule::contextPath)
        .containsExactly("/gpp-web");

    write(repo, "pom.xml", pom("app", "war", ""));
    assertThat(detect(repo, Config.empty(), new Diagnostics())).extracting(WebModule::contextPath)
        .containsExactly("/app-2.0");
  }

  @Test
  void warAvecJbossWebEtServletJaxrs(@TempDir Path repo) throws IOException {
    write(repo, "pom.xml", pom("app", "war", ""));
    write(repo, "src/main/webapp/WEB-INF/jboss-web.xml", "<jboss-web><context-root>/jb</context-root></jboss-web>");
    write(repo, "src/main/webapp/WEB-INF/web.xml", """
        <web-app>
          <servlet><servlet-name>rest</servlet-name>
            <servlet-class>org.glassfish.jersey.servlet.ServletContainer</servlet-class></servlet>
          <servlet><servlet-name>appli</servlet-name><servlet-class>nc.MaServlet</servlet-class>
            <init-param><param-name>javax.ws.rs.Application</param-name><param-value>nc.App</param-value></init-param>
          </servlet>
          <servlet><servlet-name>autre</servlet-name><servlet-class>nc.Autre</servlet-class></servlet>
          <servlet-mapping><servlet-name>rest</servlet-name><url-pattern>/rest/*</url-pattern></servlet-mapping>
          <servlet-mapping><servlet-name>appli</servlet-name><url-pattern>/api/*</url-pattern></servlet-mapping>
          <servlet-mapping><servlet-name>autre</servlet-name><url-pattern>/autre/*</url-pattern></servlet-mapping>
          <servlet-mapping><servlet-name>javax.ws.rs.core.Application</servlet-name>
            <url-pattern>/std/*</url-pattern></servlet-mapping>
        </web-app>""");

    List<WebModule> web = detect(repo, Config.empty(), new Diagnostics());

    assertThat(web).containsExactly(new WebModule("", "/jb", "", List.of("/rest", "/api", "/std")));
  }

  @Test
  void earContexteParApplicationXmlPuisJbossPuisArtifactId(@TempDir Path repo) throws IOException {
    write(repo, "pom.xml", "<project><groupId>nc.cafat</groupId><artifactId>racine</artifactId><version>2.0</version>"
        + "<packaging>pom</packaging><modules><module>ear</module><module>web1</module><module>web2</module>"
        + "<module>web3</module></modules></project>");
    String deps = "<dependencies><dependency><groupId>nc.cafat</groupId><artifactId>web1</artifactId></dependency>"
        + "<dependency><groupId>nc.cafat</groupId><artifactId>web2</artifactId></dependency>"
        + "<dependency><groupId>nc.cafat</groupId><artifactId>web3</artifactId></dependency></dependencies>";
    write(repo, "ear/pom.xml", pom("ear", "ear", deps));
    write(repo, "ear/src/main/application/META-INF/application.xml", """
        <application>
          <module><web><web-uri>web1-2.0.war</web-uri><context-root>/premier</context-root></web></module>
        </application>""");
    write(repo, "web1/pom.xml", pom("web1", "war", ""));
    write(repo, "web1/src/main/webapp/WEB-INF/jboss-web.xml", "<jboss-web><context-root>/ignore</context-root></jboss-web>");
    write(repo, "web2/pom.xml", pom("web2", "war", ""));
    write(repo, "web2/src/main/webapp/WEB-INF/jboss-web.xml", "<jboss-web><context-root>deuxieme</context-root></jboss-web>");
    write(repo, "web3/pom.xml", pom("web3", "war", ""));

    List<WebModule> web = detect(repo, Config.empty(), new Diagnostics());

    assertThat(web).extracting(WebModule::dir, WebModule::contextPath).containsExactly(
        org.assertj.core.groups.Tuple.tuple("web1", "/premier"),
        org.assertj.core.groups.Tuple.tuple("web2", "/deuxieme"),
        org.assertj.core.groups.Tuple.tuple("web3", "/web3"));
  }

  @Test
  void xmlIllisibleSignale(@TempDir Path repo) throws IOException {
    write(repo, "pom.xml", pom("app", "war", ""));
    write(repo, "src/main/webapp/WEB-INF/web.xml", "<web-app><servlet>");
    Diagnostics diags = new Diagnostics();

    List<WebModule> web = detect(repo, Config.empty(), diags);

    assertThat(web).containsExactly(new WebModule("", "/app-2.0", "", List.of()));
    assertThat(diags.all()).extracting(Diagnostic::code).containsExactly("CONFIG_PARSE_ERROR");
    assertThat(diags.all().get(0).source().file()).isEqualTo("src/main/webapp/WEB-INF/web.xml");
  }
}
