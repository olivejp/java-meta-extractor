# java-meta-extractor

Extracteur déterministe de métadonnées (entités JPA, SQL, endpoints, appels REST, JMS) des applications Java/Kotlin de la CAFAT, pour la cartographie du SI. Un fichier JSON par application déployable, validé par `schema/meta-extract.schema.json`.

## Mode d'exécution : image Docker, appel ponctuel

L'outil tourne dans l'image construite par le `Dockerfile`. Il est lancé ponctuellement, à la demande, pour analyser un ou quelques dépôts, puis le conteneur s'arrête (`docker run --rm`). Ce n'est ni un service ni un démon.

Contraintes à respecter dans toute évolution :

- **Aucun accès réseau à l'exécution** : l'image doit fonctionner avec `--network none`. Le réseau ne sert qu'au build (dépendances Maven). Pas d'appel HTTP, pas de client git, pas de serveur Spring Cloud Config interrogé.
- **Entrées montées en lecture seule** : dépôts à analyser (`--repo` / `--repos-dir`) et clone du dépôt Spring Cloud Config (`--config-repo`). L'outil ne clone, ne met à jour et ne choisit aucune branche : c'est le rôle de l'appelant, en amont.
- **Sortie** dans le seul répertoire `--out`, monté en écriture. Rien d'autre n'est écrit sur disque.
- **Utilisateur non root** (`extracteur`, uid 10001) : les volumes montés doivent être lisibles par cet uid.
- **Résultat porté par le code de sortie** (0 à 3, voir README) et par le rapport sur stdout/stderr : l'appelant décide de la suite.
- **Pas d'état entre deux exécutions** : même entrée, même sortie, octet pour octet (testé).
- **Aucun secret dans l'image ni dans la sortie** : les valeurs sensibles et `{cipher}` sont masquées (`***`).
- Toute nouvelle option doit être documentée dans le tableau des options du README et utilisable telle quelle dans `docker run`.

Exemple d'appel :

```bash
docker run --rm --network none \
  -v /chemin/depots:/depots:ro -v /chemin/config-repo:/config:ro -v "$PWD/out:/out" \
  java-meta-extractor:dev --repos-dir /depots --out /out \
  --config-repo /config --environment dev
```

Le parc est mixte (applications classiques et applications sous Spring Cloud Config) et traité en une seule exécution. Le dépôt de configuration a une branche par environnement : l'appelant le clone sur la branche ciblée (`git clone -b dev`) et passe `--environment` ; les profils viennent de chaque application (`spring.profiles.active` et ses groupes), pas de `--profile`.

## Commandes

```bash
mvn -B package                                    # JAR exécutable target/java-meta-extractor.jar
mvn test                                          # suite complète
mvn test -Dtest=MainTest -Dgolden.update=true     # régénérer les fichiers de référence, puis relire le diff
docker build -t java-meta-extractor:dev .
```

## Règles du projet

- Le contrat JSON (`model/`, `schema/meta-extract.schema.json`, `ExtractionResult.CONTRACT_VERSION`) a la même forme que celui d'`angular-meta-extractor` : toute évolution du contrat doit être signalée pour garder les deux alignés.
- Un nouveau code de diagnostic s'ajoute à `extract/DiagnosticCatalog`, sinon `DiagnosticCatalogTest` échoue.
- Chaque étape d'extraction est isolée dans `cli/Pipeline` (`isolated(…)`) : une exception devient un diagnostic, les autres étapes continuent.
- Documentation et messages en français.
