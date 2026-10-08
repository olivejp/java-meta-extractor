package fr.cafat.meta.extract.jms;

import fr.cafat.meta.config.Config;
import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.model.Messaging;
import fr.cafat.meta.model.Source;
import fr.cafat.meta.spoon.Annotations;
import fr.cafat.meta.spoon.Callers;
import fr.cafat.meta.spoon.PartialString;
import fr.cafat.meta.spoon.Provenance;
import fr.cafat.meta.spoon.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeMember;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * Producteurs et consommateurs JMS : {@code @JmsListener}, {@code JmsTemplate} et
 * {@code JmsMessagingTemplate}, EJB {@code @MessageDriven}, API JMS 1.1 ({@code Session},
 * {@code MessageProducer}) et 2.0 ({@code JMSContext}), conteneurs Spring déclarés en code.
 *
 * <p>Type de destination : déduit de l'objet destination ({@code ActiveMQQueue}, champ
 * {@code Queue}, {@code createTopic}…), du chemin JNDI ({@code /queue/}, {@code /topic/}), de la
 * propriété d'activation {@code destinationType}, ou, pour une destination nommée par chaîne avec
 * Spring, de {@code setPubSubDomain} sur le bean (fabrique ou template), sinon de
 * {@code spring.jms.pub-sub-domain}, sinon du défaut Spring (file). Ailleurs : null.
 *
 * <p>Template sans destination explicite : {@code setDefaultDestination(Name)} du {@code @Bean}, sinon
 * {@code spring.jms.template.default-destination} ; à défaut, entrée sans destination et
 * {@code DESTINATION_UNRESOLVED}.
 */
public final class JmsExtractor {

  public static final String PRODUCE = "produce";
  public static final String CONSUME = "consume";
  public static final String QUEUE = "queue";
  public static final String TOPIC = "topic";

  private static final Set<String> SPRING_JMS = Set.of("org.springframework.jms.annotation");
  private static final Set<String> SPRING_CONTEXT = Set.of("org.springframework.context.annotation");
  private static final Set<String> EJB = Set.of("javax.ejb", "jakarta.ejb");
  private static final Set<String> RESOURCE = Set.of("javax.annotation", "jakarta.annotation");
  private static final Set<String> QUALIFIERS = Set.of("org.springframework.beans.factory.annotation",
      "javax.inject", "jakarta.inject");

  private static final Set<String> TEMPLATES = Set.of("JmsTemplate", "JmsOperations", "JmsMessagingTemplate",
      "JmsMessageOperations");
  private static final Set<String> TEMPLATE_PRODUCE = Set.of("send", "convertAndSend", "sendAndReceive",
      "convertSendAndReceive");
  private static final Set<String> TEMPLATE_CONSUME = Set.of("receive", "receiveAndConvert", "receiveSelected",
      "receiveSelectedAndConvert", "browse", "browseSelected");
  private static final Set<String> SESSIONS = Set.of("Session", "QueueSession", "TopicSession", "JMSContext");
  private static final Set<String> SESSION_CONSUME = Set.of("createConsumer", "createSharedConsumer",
      "createDurableConsumer", "createSharedDurableConsumer", "createDurableSubscriber", "createReceiver",
      "createSubscriber", "createBrowser");
  private static final Set<String> SESSION_PRODUCE = Set.of("createProducer", "createSender", "createPublisher");
  private static final Set<String> PRODUCERS = Set.of("MessageProducer", "QueueSender", "TopicPublisher",
      "JMSProducer");
  private static final Set<String> CONTAINERS = Set.of("DefaultMessageListenerContainer",
      "SimpleMessageListenerContainer", "AbstractMessageListenerContainer");
  private static final String PUB_SUB_KEY = "spring.jms.pub-sub-domain";
  private static final String DEFAULT_DESTINATION_KEY = "spring.jms.template.default-destination";

  /** Destination lue dans le code : texte brut (peut contenir {@code ${cle}}) et type si connu. */
  private record Dest(PartialString raw, String type) {
    static final Dest UNKNOWN = new Dest(null, null);
  }

  private final ExtractionContext ctx;
  private final List<Messaging> out = new ArrayList<>();
  /** Beans Spring (fabriques, templates, conteneurs) : nom → setPubSubDomain littéral. */
  private final Map<String, Boolean> pubSubBeans = new HashMap<>();
  /** Templates déclarés en {@code @Bean} : nom → destination par défaut. */
  private final Map<String, Dest> defaultDestinations = new HashMap<>();
  /** Destinations déclarées en {@code @Bean} (Queue, Topic, Destination) : nom → destination. */
  private final Map<String, Dest> destinationBeans = new TreeMap<>();

  public JmsExtractor(ExtractionContext ctx) {
    this.ctx = ctx;
  }

  public List<Messaging> extract() {
    collectPubSubBeans();
    collectDestinationBeans();
    for (CtType<?> t : ctx.types().all()) {
      CtAnnotation<?> mdb = Annotations.find(t, EJB, "MessageDriven");
      if (mdb != null) {
        messageDriven(t, mdb);
      }
      for (CtTypeMember m : sortedMembers(t)) {
        if (m instanceof CtMethod<?> method) {
          for (CtAnnotation<?> l : listeners(method)) {
            jmsListener(method, l);
          }
        }
      }
    }
    for (CtType<?> t : ctx.types().all()) {
      if (t.getDeclaringType() != null) {
        continue;
      }
      List<CtInvocation<?>> invocations = new ArrayList<>(t.getElements(new TypeFilter<>(CtInvocation.class)));
      invocations.sort(Comparator.comparingInt(Provenance::offset));
      for (CtInvocation<?> inv : invocations) {
        invocation(inv);
      }
    }
    return out;
  }

  // ---------------------------------------------------------------- consommateurs déclarés

  private static List<CtTypeMember> sortedMembers(CtType<?> t) {
    List<CtTypeMember> members = new ArrayList<>(t.getTypeMembers());
    members.sort(Comparator.comparingInt(Provenance::offset));
    return members;
  }

  private static List<CtAnnotation<?>> listeners(CtMethod<?> m) {
    List<CtAnnotation<?>> out = new ArrayList<>(Annotations.findAll(m, SPRING_JMS, "JmsListener"));
    for (CtAnnotation<?> container : Annotations.findAll(m, SPRING_JMS, "JmsListeners")) {
      out.addAll(Annotations.nested(container, "value"));
    }
    return out;
  }

  private void jmsListener(CtMethod<?> m, CtAnnotation<?> listener) {
    CtExpression<?> destination = Annotations.value(listener, "destination");
    PartialString raw = destination == null ? null : ctx.eval().eval(destination);
    String factory = ctx.str(listener, "containerFactory");
    String type = typeFromName(raw);
    if (type == null) {
      type = springType(factory == null ? "jmsListenerContainerFactory" : factory);
    }
    add(CONSUME, new Dest(raw, type), Callers.of(m), ctx.source(listener));
  }

  /** EJB : propriétés d'activation {@code destination}/{@code destinationLookup}, sinon {@code mappedName}. */
  private void messageDriven(CtType<?> t, CtAnnotation<?> mdb) {
    PartialString raw = null;
    String type = null;
    for (CtAnnotation<?> p : Annotations.nested(mdb, "activationConfig")) {
      String name = ctx.str(p, "propertyName");
      CtExpression<?> value = Annotations.value(p, "propertyValue");
      if (name == null || value == null) {
        continue;
      }
      switch (name) {
        case "destination", "destinationLookup", "destinationJndiName" -> {
          if (raw == null || name.equals("destinationLookup")) {
            raw = ctx.eval().eval(value);
          }
        }
        case "destinationType" -> {
          String v = ctx.eval().constant(value);
          type = v == null ? null : v.endsWith("Topic") ? TOPIC : v.endsWith("Queue") ? QUEUE : null;
        }
        default -> {
          // autres propriétés (acknowledgeMode, maxSession…) sans effet sur la destination
        }
      }
    }
    if (raw == null && Annotations.value(mdb, "mappedName") != null) {
      raw = ctx.eval().eval(Annotations.value(mdb, "mappedName"));
    }
    if (type == null) {
      type = typeFromName(raw);
    }
    String caller = null;
    for (CtMethod<?> m : t.getMethodsByName("onMessage")) {
      caller = Callers.of(m);
    }
    add(CONSUME, new Dest(raw, type), caller, ctx.source(mdb));
  }

  // ---------------------------------------------------------------- invocations

  private void invocation(CtInvocation<?> inv) {
    String name = inv.getExecutable().getSimpleName();
    CtExpression<?> target = inv.getTarget();
    List<CtExpression<?>> args = inv.getArguments();
    String receiver = receiverType(target);
    if (receiver != null && TEMPLATES.contains(receiver)
        && (TEMPLATE_PRODUCE.contains(name) || TEMPLATE_CONSUME.contains(name))) {
      String role = TEMPLATE_PRODUCE.contains(name) ? PRODUCE : CONSUME;
      template(inv, role, target, args);
    } else if (receiver != null && SESSIONS.contains(receiver)
        && (SESSION_CONSUME.contains(name) || SESSION_PRODUCE.contains(name)) && !args.isEmpty()) {
      if (args.get(0) instanceof CtLiteral<?> lit && lit.getValue() == null) {
        return; // producteur anonyme : la destination est donnée à chaque envoi
      }
      add(SESSION_PRODUCE.contains(name) ? PRODUCE : CONSUME, destination(args.get(0)), Callers.of(inv),
          ctx.source(inv));
    } else if ((name.equals("send") || name.equals("publish")) && args.size() >= 2
        && (receiver != null && PRODUCERS.contains(receiver) || isAnonymousProducer(target))) {
      Dest d = destination(args.get(0));
      if (d.type() != null || isDestinationTyped(args.get(0))) {
        add(PRODUCE, d, Callers.of(inv), ctx.source(inv));
      }
    } else if (receiver != null && CONTAINERS.contains(receiver)
        && (name.equals("setDestinationName") || name.equals("setDestination")) && args.size() == 1) {
      Dest d = destination(args.get(0));
      if (d.type() == null && name.equals("setDestinationName")) {
        d = new Dest(d.raw(), springType(beanOf(inv)));
      }
      add(CONSUME, d, Callers.of(inv), ctx.source(inv));
    }
  }

  /** JmsTemplate : destination en premier argument, sinon destination par défaut du template. */
  private void template(CtInvocation<?> inv, String role, CtExpression<?> target, List<CtExpression<?>> args) {
    String name = inv.getExecutable().getSimpleName();
    String bean = templateBean(target);
    if (!templateHasDestination(name, args)) {
      Dest d = defaultDestination(bean);
      if (d == null) {
        ctx.diagnostics().warning("DESTINATION_UNRESOLVED", "Destination par défaut du template JMS introuvable dans "
            + Callers.of(inv), ctx.source(inv));
        add(role, Dest.UNKNOWN, Callers.of(inv), ctx.source(inv), false);
      } else {
        add(role, d, Callers.of(inv), ctx.source(inv));
      }
      return;
    }
    Dest d = destination(args.get(0));
    if (d.type() == null && !isDestinationTyped(args.get(0))) {
      d = new Dest(d.raw(), springType(bean));
    }
    add(role, d, Callers.of(inv), ctx.source(inv));
  }

  /**
   * Le premier argument est-il la destination ? Surcharges Spring : {@code receive()},
   * {@code receive(dest)}, {@code receiveSelected(sel)} / {@code (dest, sel)}, {@code browse(cb)} /
   * {@code (dest, cb)}, {@code convertAndSend(msg)} / {@code (msg, postProcessor|headers)} /
   * {@code (dest, msg[, …])}, {@code convertSendAndReceive(req, Class)} / {@code (dest, req, Class)}.
   */
  private static boolean templateHasDestination(String name, List<CtExpression<?>> args) {
    if (args.isEmpty() || args.get(0) instanceof CtLambda<?>) {
      return false;
    }
    if (isDestinationTyped(args.get(0))) {
      return true;
    }
    if (name.startsWith("browseSelected")) {
      return args.size() >= 3;
    }
    if (name.startsWith("browse") || name.startsWith("receiveSelected")) {
      return args.size() >= 2;
    }
    if (name.startsWith("receive")) {
      return true;
    }
    if (args.size() < 2) {
      return false;
    }
    CtExpression<?> second = args.get(1);
    if (args.size() == 2 && (isClassLiteral(second) || second instanceof CtLambda<?> || isOfType(second,
        "MessagePostProcessor", "Map", "HashMap", "LinkedHashMap", "MessageHeaders"))) {
      return false;
    }
    return true;
  }

  private static boolean isClassLiteral(CtExpression<?> e) {
    return e instanceof CtFieldRead<?> fr && fr.getVariable().getSimpleName().equals("class");
  }

  private static boolean isOfType(CtExpression<?> e, String... simpleNames) {
    CtTypeReference<?> t = Types.typeOf(e);
    return t != null && List.of(simpleNames).contains(Types.simpleName(t));
  }

  /** Destination par défaut : {@code setDefaultDestination(Name)} dans le {@code @Bean}, sinon configuration. */
  private Dest defaultDestination(String bean) {
    Dest d = bean == null ? null : defaultDestinations.get(bean);
    if (d != null) {
      return d;
    }
    String raw = ctx.config().raw(DEFAULT_DESTINATION_KEY);
    if (raw == null) {
      return null;
    }
    PartialString p = PartialString.lit(raw);
    return new Dest(p, springType(bean));
  }

  /** Lecture d'une expression destination : constructeur ActiveMQ, createQueue/Topic, champ JNDI, chaîne. */
  private Dest destination(CtExpression<?> e) {
    return destination(e, 0);
  }

  private Dest destination(CtExpression<?> e, int depth) {
    if (e == null || depth > 8) {
      return Dest.UNKNOWN;
    }
    String declared = typeOfDestination(Types.typeOf(e));
    if (e instanceof CtConstructorCall<?> call) {
      String type = typeOfDestination(call.getType());
      PartialString raw = call.getArguments().isEmpty() ? null : ctx.eval().eval(call.getArguments().get(0));
      return new Dest(raw, type != null ? type : typeFromName(raw));
    }
    if (e instanceof CtInvocation<?> inv && !inv.getArguments().isEmpty()) {
      String n = inv.getExecutable().getSimpleName();
      if (n.equals("createQueue") || n.equals("createTopic")) {
        return new Dest(ctx.eval().eval(inv.getArguments().get(0)), n.equals("createQueue") ? QUEUE : TOPIC);
      }
      if (n.equals("lookup")) {
        PartialString raw = ctx.eval().eval(inv.getArguments().get(0));
        return new Dest(raw, declared != null ? declared : typeFromName(raw));
      }
    }
    if (e instanceof CtFieldRead<?> fr && !isStringTyped(e)) {
      CtField<?> f = ctx.eval().field(fr);
      if (f != null) {
        return fromVariable(f, depth);
      }
    }
    if (e instanceof CtVariableRead<?> vr && !(e instanceof CtFieldRead<?>) && !isStringTyped(e)
        && vr.getVariable().getDeclaration() instanceof CtLocalVariable<?> lv) {
      return fromVariable(lv, depth);
    }
    if (isDestinationTyped(e)) {
      return new Dest(null, declared);
    }
    PartialString raw = ctx.eval().eval(e);
    return new Dest(raw, typeFromName(raw));
  }

  /** Champ ou variable de type destination : {@code @Resource}, sinon initialiseur. */
  private Dest fromVariable(CtVariable<?> v, int depth) {
    String declared = typeOfDestination(v.getType());
    CtAnnotation<?> resource = Annotations.find(v, RESOURCE, "Resource");
    if (resource != null) {
      for (String key : List.of("lookup", "mappedName", "name")) {
        CtExpression<?> value = Annotations.value(resource, key);
        String constant = value == null ? null : ctx.eval().constant(value);
        if (constant != null && !constant.isBlank()) {
          PartialString raw = ctx.eval().eval(value);
          return new Dest(raw, declared != null ? declared : typeFromName(raw));
        }
      }
    }
    if (v.getDefaultExpression() != null) {
      Dest d = destination(v.getDefaultExpression(), depth + 1);
      return new Dest(d.raw(), d.type() != null ? d.type() : declared);
    }
    Dest injected = v instanceof CtField<?> f ? injectedBean(f) : null;
    if (injected != null) {
      return new Dest(injected.raw(), injected.type() != null ? injected.type() : declared);
    }
    return new Dest(null, declared);
  }

  /**
   * Bean destination injecté dans un champ, comme Spring le choisit : qualificateur, sinon bean du
   * nom du champ, sinon seul bean de type compatible.
   */
  private Dest injectedBean(CtField<?> f) {
    CtAnnotation<?> q = Annotations.findAny(f, QUALIFIERS, "Qualifier", "Named");
    String qualifier = q == null ? null : ctx.str(q, "value");
    if (qualifier != null) {
      return destinationBeans.get(qualifier);
    }
    if (destinationBeans.containsKey(f.getSimpleName())) {
      return destinationBeans.get(f.getSimpleName());
    }
    String declared = typeOfDestination(f.getType());
    List<Dest> compatible = destinationBeans.values().stream()
        .filter(d -> declared == null || declared.equals(d.type())).toList();
    return compatible.size() == 1 ? compatible.get(0) : null;
  }

  private static String typeOfDestination(CtTypeReference<?> ref) {
    if (ref == null) {
      return null;
    }
    String simple = Types.simpleName(ref);
    if (simple.endsWith("Topic")) {
      return TOPIC;
    }
    if (simple.endsWith("Queue")) {
      return QUEUE;
    }
    return null;
  }

  private static boolean isDestinationTyped(CtExpression<?> e) {
    CtTypeReference<?> t = Types.typeOf(e);
    if (t == null) {
      return false;
    }
    String simple = Types.simpleName(t);
    return simple.endsWith("Destination") || simple.endsWith("Queue") || simple.endsWith("Topic");
  }

  private static boolean isStringTyped(CtExpression<?> e) {
    CtTypeReference<?> t = Types.typeOf(e);
    return e instanceof CtLiteral<?> lit && lit.getValue() instanceof String
        || t != null && Types.simpleName(t).equals("String");
  }

  /** {@code context.createProducer().send(dest, ...)} : le receveur est un JMSProducer non typé. */
  private static boolean isAnonymousProducer(CtExpression<?> target) {
    return target instanceof CtInvocation<?> inv && inv.getExecutable().getSimpleName().equals("createProducer")
        && inv.getArguments().isEmpty();
  }

  private static String receiverType(CtExpression<?> target) {
    CtTypeReference<?> t = target == null ? null : Types.typeOf(target);
    return t == null ? null : Types.simpleName(t);
  }

  /** {@code queue://X}, {@code java:/jms/topic/X}, {@code jms/queue/X} : type lu dans le nom. */
  private static String typeFromName(PartialString raw) {
    if (raw == null) {
      return null;
    }
    String s = raw.render().toLowerCase(Locale.ROOT);
    if (s.startsWith("topic://") || s.contains("/topic/") || s.startsWith("topic/")) {
      return TOPIC;
    }
    if (s.startsWith("queue://") || s.contains("/queue/") || s.startsWith("queue/")) {
      return QUEUE;
    }
    return null;
  }

  // ---------------------------------------------------------------- pub/sub Spring

  /** Beans {@code @Bean} qui appellent {@code setPubSubDomain(true|false)}. */
  private void collectPubSubBeans() {
    for (CtType<?> t : ctx.types().all()) {
      for (CtMethod<?> m : t.getMethods()) {
        CtAnnotation<?> bean = Annotations.find(m, SPRING_CONTEXT, "Bean");
        if (bean == null || m.getBody() == null) {
          continue;
        }
        for (CtInvocation<?> inv : m.getBody().getElements(new TypeFilter<>(CtInvocation.class))) {
          if (inv.getExecutable().getSimpleName().equals("setPubSubDomain") && inv.getArguments().size() == 1
              && inv.getArguments().get(0) instanceof CtLiteral<?> lit && lit.getValue() instanceof Boolean b) {
            for (String name : beanNames(m, bean)) {
              pubSubBeans.put(name, b);
            }
          }
        }
      }
    }
    for (CtType<?> t : ctx.types().all()) {
      for (CtMethod<?> m : t.getMethods()) {
        CtAnnotation<?> bean = Annotations.find(m, SPRING_CONTEXT, "Bean");
        if (bean == null || m.getBody() == null) {
          continue;
        }
        for (CtInvocation<?> inv : m.getBody().getElements(new TypeFilter<>(CtInvocation.class))) {
          String n = inv.getExecutable().getSimpleName();
          if ((n.equals("setDefaultDestinationName") || n.equals("setDefaultDestination"))
              && inv.getArguments().size() == 1) {
            List<String> names = beanNames(m, bean);
            Dest d = destination(inv.getArguments().get(0));
            if (d.type() == null && n.equals("setDefaultDestinationName")) {
              d = new Dest(d.raw(), springType(names.get(0)));
            }
            for (String name : names) {
              defaultDestinations.put(name, d);
            }
          }
        }
      }
    }
  }

  /** Beans {@code @Bean} de type destination qui renvoient une seule expression. */
  private void collectDestinationBeans() {
    for (CtType<?> t : ctx.types().all()) {
      for (CtMethod<?> m : t.getMethods()) {
        CtAnnotation<?> bean = Annotations.find(m, SPRING_CONTEXT, "Bean");
        String simple = m.getType() == null ? "" : Types.simpleName(m.getType());
        if (bean == null || m.getBody() == null
            || !(simple.endsWith("Destination") || simple.endsWith("Queue") || simple.endsWith("Topic"))) {
          continue;
        }
        List<CtReturn<?>> returns = m.getBody().getElements(new TypeFilter<>(CtReturn.class));
        if (returns.size() != 1 || returns.get(0).getReturnedExpression() == null) {
          continue;
        }
        Dest d = destination(returns.get(0).getReturnedExpression());
        Dest typed = new Dest(d.raw(), d.type() != null ? d.type() : typeOfDestination(m.getType()));
        for (String name : beanNames(m, bean)) {
          destinationBeans.put(name, typed);
        }
      }
    }
  }

  private List<String> beanNames(CtMethod<?> m, CtAnnotation<?> bean) {
    List<String> names = new ArrayList<>();
    for (String key : List.of("name", "value")) {
      for (CtExpression<?> e : Annotations.values(bean, key)) {
        String n = ctx.eval().constant(e);
        if (n != null) {
          names.add(n);
        }
      }
    }
    if (names.isEmpty()) {
      names.add(m.getSimpleName());
    }
    return names;
  }

  /** Nom du bean qui porte le template receveur : qualificateur, sinon nom du champ. */
  private String templateBean(CtExpression<?> target) {
    if (target instanceof CtFieldRead<?> fr) {
      CtField<?> f = ctx.eval().field(fr);
      CtAnnotation<?> q = f == null ? null : Annotations.findAny(f, QUALIFIERS, "Qualifier", "Named");
      String qualifier = q == null ? null : ctx.str(q, "value");
      return qualifier != null ? qualifier : fr.getVariable().getSimpleName();
    }
    return null;
  }

  /** Bean {@code @Bean} englobant l'élément (conteneur déclaré en code). */
  private String beanOf(CtElement e) {
    CtMethod<?> m = e.getParent(CtMethod.class);
    CtAnnotation<?> bean = m == null ? null : Annotations.find(m, SPRING_CONTEXT, "Bean");
    return bean == null ? null : beanNames(m, bean).get(0);
  }

  /** Type d'une destination nommée par chaîne avec Spring : bean, configuration, défaut (file). */
  private String springType(String beanName) {
    Boolean pubSub = beanName == null ? null : pubSubBeans.get(beanName);
    if (pubSub == null) {
      String configured = ctx.config().get(PUB_SUB_KEY);
      pubSub = configured != null && configured.strip().equalsIgnoreCase("true");
    }
    return pubSub ? TOPIC : QUEUE;
  }

  // ---------------------------------------------------------------- production

  private void add(String role, Dest d, String caller, Source source) {
    add(role, d, caller, source, true);
  }

  private void add(String role, Dest d, String caller, Source source, boolean warnIfUnresolved) {
    String raw = d.raw() == null ? null : d.raw().render();
    String destination = null;
    if (raw != null) {
      Config.Resolution r = ctx.config().resolve(raw);
      if (r.complete() && d.raw().isComplete()) {
        destination = r.value();
      } else if (warnIfUnresolved) {
        String missing = r.missing().isEmpty() ? "" : " (clés absentes : " + String.join(", ", r.missing()) + ")";
        ctx.diagnostics().warning("DESTINATION_UNRESOLVED", "Destination JMS non résolue : " + raw + missing
            + " dans " + caller, source);
      }
    } else if (warnIfUnresolved) {
      ctx.diagnostics().warning("DESTINATION_UNRESOLVED", "Destination JMS introuvable dans " + caller, source);
    }
    String id = ctx.appId() + ":" + role + ":" + (d.type() == null ? "?" : d.type()) + ":"
        + (destination != null ? destination : raw != null ? raw : "?") + "@" + (caller == null ? "" : caller);
    out.add(new Messaging(id, role, d.type(), raw, destination, caller, source));
  }
}
