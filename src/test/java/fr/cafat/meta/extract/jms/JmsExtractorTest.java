package fr.cafat.meta.extract.jms;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.TestContexts;
import fr.cafat.meta.config.Config;
import fr.cafat.meta.config.ConfigEntry;
import fr.cafat.meta.extract.Diagnostics;
import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.extract.entities.NamingStrategy;
import fr.cafat.meta.model.Diagnostic;
import fr.cafat.meta.model.Messaging;
import fr.cafat.meta.scan.WebModule;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JmsExtractorTest {

  /** Résumé : rôle, type, brut → résolu, appelant. */
  static String summary(Messaging m) {
    return m.role() + " " + m.destinationType() + " " + m.rawDestination() + " -> " + m.destination() + " "
        + m.caller();
  }

  static List<String> summaries(List<Messaging> list) {
    return list.stream().map(JmsExtractorTest::summary).sorted().toList();
  }

  static List<String> messages(ExtractionContext ctx, String code) {
    return ctx.diagnostics().all().stream().filter(d -> d.code().equals(code)).map(Diagnostic::message).toList();
  }

  @Test
  void fixtureSpring() {
    ExtractionContext ctx = TestContexts.ofRepoConfigured(TestContexts.fixture("fixture-spring"), "s-gen-fixture",
        NamingStrategy.springBoot(), List.of());
    List<Messaging> list = new JmsExtractor(ctx).extract();
    String c = "fr.cafat.gpp.jms.PersonneMessaging#";
    assertThat(summaries(list)).containsExactly(
        "consume queue ${jms.queue.maj} -> GPP.PERSONNE.MAJ " + c + "onMaj",
        "produce queue GPP.PERSONNE.NOTIF -> GPP.PERSONNE.NOTIF " + c + "onMaj",
        "produce topic GPP.EVENEMENTS -> GPP.EVENEMENTS " + c + "publierEvenement");
    Messaging maj = list.stream().filter(m -> m.role().equals("consume")).findFirst().orElseThrow();
    assertThat(maj.id()).isEqualTo("s-gen-fixture:consume:queue:GPP.PERSONNE.MAJ@" + c + "onMaj");
    assertThat(maj.source().file()).isEqualTo("src/main/java/fr/cafat/gpp/jms/PersonneMessaging.java");
    assertThat(maj.source().line()).isEqualTo(19);
    assertThat(messages(ctx, "DESTINATION_UNRESOLVED")).isEmpty();
  }

  @Test
  void fixtureJboss() {
    ExtractionContext ctx = TestContexts.ofRepoConfigured(TestContexts.fixture("fixture-jboss-multi"), "legacy",
        NamingStrategy.jpa(), List.of());
    List<Messaging> list = new JmsExtractor(ctx).extract();
    assertThat(summaries(list)).containsExactly(
        "consume queue java:/jms/queue/LegacyIn -> java:/jms/queue/LegacyIn "
            + "fr.cafat.legacy.jms.LegacyListener#onMessage",
        "produce queue java:/jms/queue/LegacyOut -> java:/jms/queue/LegacyOut "
            + "fr.cafat.legacy.jms.LegacyProducer#envoyer");
    assertThat(list).allSatisfy(m -> assertThat(m.source().file()).doesNotContain("src/test"));
    assertThat(messages(ctx, "DESTINATION_UNRESOLVED")).isEmpty();
  }

  @Test
  void variantes(@TempDir Path dir) {
    ExtractionContext base = TestContexts.ofSources(dir, NamingStrategy.springBoot(),
        """
        package fr.x;
        import org.springframework.beans.factory.annotation.Qualifier;
        import org.springframework.jms.annotation.JmsListener;
        import org.springframework.jms.core.JmsTemplate;
        public class Echanges {
          private static final String PREFIXE = "APP.";
          @Qualifier("topicTemplate")
          private JmsTemplate topics;
          private JmsTemplate jmsTemplate;
          private javax.jms.Session session;
          @JmsListener(destination = "APP.EVT", containerFactory = "topicFactory")
          @JmsListener(destination = "${absent.cle}")
          public void ecouter(String m) { }
          public void publier(String m, String code) {
            topics.convertAndSend(PREFIXE + "ANNONCE", m);
            jmsTemplate.convertAndSend(m);
            topics.convertAndSend(m);
            jmsTemplate.convertAndSend(m, msg -> msg);
            jmsTemplate.convertAndSend("APP." + code, m);
            Object r = jmsTemplate.receiveAndConvert("APP.REPONSE");
          }
          public void brut() throws Exception {
            javax.jms.Queue q = session.createQueue("APP.BRUT");
            javax.jms.MessageConsumer c = session.createConsumer(session.createTopic("APP.ABO"));
            javax.jms.MessageProducer p = session.createProducer(q);
            javax.jms.MessageProducer libre = session.createProducer(null);
            libre.send(session.createQueue("APP.LIBRE"), null);
            javax.jms.TextMessage tm = session.createTextMessage("x");
            p.send(tm, 1, 4, 0L);
          }
        }
        """,
        """
        package fr.x;
        import org.springframework.context.annotation.Bean;
        import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
        import org.springframework.jms.core.JmsTemplate;
        import org.springframework.jms.listener.DefaultMessageListenerContainer;
        public class JmsConfig {
          @Bean
          public DefaultJmsListenerContainerFactory topicFactory() {
            DefaultJmsListenerContainerFactory f = new DefaultJmsListenerContainerFactory();
            f.setPubSubDomain(true);
            return f;
          }
          @Bean("topicTemplate")
          public JmsTemplate template() {
            JmsTemplate t = new JmsTemplate();
            t.setPubSubDomain(true);
            t.setDefaultDestinationName("APP.DEFAUT");
            return t;
          }
          @Bean
          public DefaultMessageListenerContainer conteneur() {
            DefaultMessageListenerContainer c = new DefaultMessageListenerContainer();
            c.setDestinationName("APP.CONTENEUR");
            return c;
          }
        }
        """,
        """
        package fr.x;
        import javax.ejb.ActivationConfigProperty;
        import javax.ejb.MessageDriven;
        @MessageDriven(activationConfig = {
            @ActivationConfigProperty(propertyName = "destinationLookup", propertyValue = "jms/topic/Diffusion")
        })
        public class Diffusion implements javax.jms.MessageListener {
          public void onMessage(javax.jms.Message m) { }
        }
        """);
    Config config = Config.empty();
    config.put(new ConfigEntry("spring.jms.pub-sub-domain", "false", "application.yml", 1));
    ExtractionContext ctx = TestContexts.build(base.root(), javaFiles(base), "app", NamingStrategy.springBoot(),
        new Diagnostics(), List.of(WebModule.ROOT), config, List.of());
    List<Messaging> list = new JmsExtractor(ctx).extract();
    assertThat(summaries(list)).containsExactly(
        "consume queue ${absent.cle} -> null fr.x.Echanges#ecouter",
        "consume queue APP.CONTENEUR -> APP.CONTENEUR fr.x.JmsConfig#conteneur",
        "consume queue APP.REPONSE -> APP.REPONSE fr.x.Echanges#publier",
        "consume topic APP.ABO -> APP.ABO fr.x.Echanges#brut",
        "consume topic APP.EVT -> APP.EVT fr.x.Echanges#ecouter",
        "consume topic jms/topic/Diffusion -> jms/topic/Diffusion fr.x.Diffusion#onMessage",
        "produce null null -> null fr.x.Echanges#publier",
        "produce null null -> null fr.x.Echanges#publier",
        "produce queue APP.BRUT -> APP.BRUT fr.x.Echanges#brut",
        "produce queue APP.LIBRE -> APP.LIBRE fr.x.Echanges#brut",
        "produce queue APP.{code} -> null fr.x.Echanges#publier",
        "produce topic APP.ANNONCE -> APP.ANNONCE fr.x.Echanges#publier",
        "produce topic APP.DEFAUT -> APP.DEFAUT fr.x.Echanges#publier");
    assertThat(messages(ctx, "DESTINATION_UNRESOLVED")).hasSize(4)
        .anyMatch(m -> m.contains("absent.cle"))
        .anyMatch(m -> m.contains("défaut du template"))
        .anyMatch(m -> m.contains("APP.{code}"));
  }

  @Test
  void destinationParDefautConfiguree(@TempDir Path dir) {
    ExtractionContext base = TestContexts.ofSources(dir, NamingStrategy.springBoot(),
        """
        package fr.x;
        public class Envoi {
          private org.springframework.jms.core.JmsTemplate jmsTemplate;
          public void envoyer(Object o) { jmsTemplate.convertAndSend(o); }
        }
        """);
    Config config = Config.empty();
    config.put(new ConfigEntry("spring.jms.template.default-destination", "APP.CONF", "application.yml", 3));
    config.put(new ConfigEntry("spring.jms.pub-sub-domain", "true", "application.yml", 4));
    ExtractionContext ctx = TestContexts.build(base.root(), javaFiles(base), "app", NamingStrategy.springBoot(),
        new Diagnostics(), List.of(WebModule.ROOT), config, List.of());
    assertThat(summaries(new JmsExtractor(ctx).extract()))
        .containsExactly("produce topic APP.CONF -> APP.CONF fr.x.Envoi#envoyer");
    assertThat(ctx.diagnostics().all()).isEmpty();
  }

  private static List<Path> javaFiles(ExtractionContext ctx) {
    return ctx.types().all().stream()
        .filter(t -> t.getDeclaringType() == null && t.getPosition().isValidPosition())
        .map(t -> t.getPosition().getFile().toPath())
        .distinct().sorted().toList();
  }
}
