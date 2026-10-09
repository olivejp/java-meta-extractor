package fr.cafat.meta.extract;

import fr.cafat.meta.extract.entities.EntityDraft;
import fr.cafat.meta.extract.entities.EntityExtractor;
import fr.cafat.meta.extract.entities.InheritanceResolver;
import fr.cafat.meta.extract.relations.RelationExtractor;
import fr.cafat.meta.model.Relation;
import java.util.List;
import java.util.Map;

/**
 * Modèle de persistance : entités, héritage puis relations. Les colonnes de clé étrangère des
 * relations sont ajoutées avant la propagation des colonnes héritées.
 */
public final class PersistenceExtractor {

  /** Brouillons d'entités par nom qualifié, et relations extraites. */
  public record Result(Map<String, EntityDraft> drafts, List<Relation> relations) {
  }

  private PersistenceExtractor() {
  }

  /**
   * Extrait entités, héritage et relations, dans cet ordre.
   *
   * @param ctx contexte de l'application
   * @return brouillons d'entités par nom qualifié (colonnes héritées propagées) et relations
   */
  public static Result run(ExtractionContext ctx) {
    Map<String, EntityDraft> drafts = new EntityExtractor(ctx).extract();
    InheritanceResolver inheritance = new InheritanceResolver(ctx, drafts);
    inheritance.link();
    List<Relation> relations = new RelationExtractor(ctx, drafts).extract();
    inheritance.propagate();
    return new Result(drafts, relations);
  }
}
