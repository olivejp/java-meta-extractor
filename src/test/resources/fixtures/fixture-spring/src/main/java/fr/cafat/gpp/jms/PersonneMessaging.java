package fr.cafat.gpp.jms;

import org.apache.activemq.command.ActiveMQTopic;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

@Component
public class PersonneMessaging {

  private static final String QUEUE_NOTIF = "GPP.PERSONNE.NOTIF";

  private final JmsTemplate jmsTemplate;

  public PersonneMessaging(JmsTemplate jmsTemplate) {
    this.jmsTemplate = jmsTemplate;
  }

  @JmsListener(destination = "${jms.queue.maj}")
  public void onMaj(String message) {
    jmsTemplate.convertAndSend(QUEUE_NOTIF, message);
  }

  public void publierEvenement(String evenement) {
    jmsTemplate.convertAndSend(new ActiveMQTopic("GPP.EVENEMENTS"), evenement);
  }
}
