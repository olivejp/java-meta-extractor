package fr.cafat.legacy.jms;

import javax.annotation.Resource;
import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.jms.JMSContext;
import javax.jms.Queue;

@Stateless
public class LegacyProducer {

  @Inject
  private JMSContext context;

  @Resource(mappedName = "java:/jms/queue/LegacyOut")
  private Queue sortie;

  public void envoyer(String texte) {
    context.createProducer().send(sortie, texte);
  }
}
