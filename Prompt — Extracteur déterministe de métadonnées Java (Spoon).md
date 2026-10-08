# Prompt — Extracteur déterministe de métadonnées Java (Spoon)

Oct 8, 2026 · @OLIVE Jean-Paul

Prompt à rejouer dans Claude Code (ou un autre agent) pour faire développer un programme Java/Spoon qui extrait, sans IA ni exécution du code analysé, les métadonnées de données et d'appels REST de chaque application.

## 1. Rôle, objectif et contexte

Tu es un développeur Java senior, spécialiste de l'analyse statique de code. Tu vas construire **java-meta-extractor**, un outil en ligne de commande entièrement déterministe, sans LLM, qui lit le code source d'applications Java et produit leurs métadonnées au format JSON décrit plus bas.

**Contexte.** La CAFAT (Nouvelle-Calédonie) possède un SI hybride : des micro-services Java (Spring Boot, parfois Kotlin, et des applications JBoss/EJB plus anciennes) sur PostgreSQL, et un legacy Adélia/RPG sur DB2 for i (AS400). Certaines applications Java lisent DB2 directement, via des vues (schéma `MGENGPP`) ou des tables (`GEO_*`). Le GIE SINAPSE va refondre les référentiels (personnes, adresses, contacts, géographie). Il faut une cartographie fiable des données manipulées par chaque application et des appels REST entre applications.

**Place de l'outil dans la chaîne.** L'extracteur est l'étape 2 d'un pipeline rejouable :

1. un script clone ou met à jour tous les dépôts depuis Bitbucket et note le commit de chacun ;
2. **java-meta-extractor** analyse chaque dépôt et écrit un fichier JSON par application, sans jamais toucher à une base ;
3. un chargeur recharge entièrement les JSON dans des tables brutes DuckDB ou PostgreSQL, horodatées par exécution ;
4. dbt construit le modèle pivot, les MCD par application, le graphe d'appels REST et les vues d'impact.

**Ce que je te demande ici** : uniquement l'étape 2, c'est-à-dire l'extracteur, son contrat JSON, son schéma de validation et ses tests. Le chargeur et dbt viendront ensuite et s'appuieront sur ce contrat.

**Règle absolue : le déterminisme.** Deux exécutions sur le même commit doivent produire des fichiers JSON identiques octet pour octet. Aucun appel réseau, aucun LLM, aucune date ni valeur aléatoire dans la sortie, en dehors des champs prévus pour cela.

## 2. Ce que l'extracteur doit sortir du code

Pour chaque dépôt, l'outil relève sept familles d'éléments. Chaque élément garde sa **provenance** : fichier, ligne, et nom pleinement qualifié de la classe.

**Application**

- Nom (`spring.application.name`, sinon `artifactId` du `pom.xml` ou nom du projet Gradle), version, techno détectée (Spring Boot, JBoss/EJB, Kotlin), dépôt et commit.
- Sources de données déclarées dans `application*.yml`, `application*.properties` et `persistence.xml` : nom, URL JDBC, type de base déduit (`postgresql`, `db2`, autre). Les mots de passe et identifiants ne sont **jamais** recopiés.

**Entités et tables**

- Classes `@Entity`, `@Embeddable`, `@MappedSuperclass`, en Java comme en Kotlin.
- Table physique : `@Table(name, schema)`, `@SecondaryTable`, ou nom par défaut selon la stratégie de nommage configurée.
- Héritage : `@Inheritance(strategy)`, `@DiscriminatorColumn`, `@DiscriminatorValue`, et la classe parente.
- Base cible : rattacher chaque entité à sa source de données (unité de persistance, `@EnableJpaRepositories` par paquet, ou schéma).

**Colonnes**

- Champs et propriétés persistants : `@Column`, `@Id`, `@GeneratedValue`, `@Enumerated` (avec les valeurs de l'enum), `@Embedded`, `@AttributeOverride`, `@Transient` exclu.
- Nom de colonne, type Java, longueur, nullabilité, unicité, clé primaire.

**Relations**

- `@OneToOne`, `@OneToMany`, `@ManyToOne`, `@ManyToMany`, `@ElementCollection`.
- Entité cible, cardinalité, côté propriétaire (`mappedBy`), colonne de jointure (`@JoinColumn`, `@JoinTable`), optionalité.

**Accès SQL hors entités**

- `@Query(nativeQuery = true)`, `@NamedNativeQuery`, `JdbcTemplate`, `NamedParameterJdbcTemplate`, fichiers MyBatis XML, et SQL en littéral de chaîne.
- Pour chacun : texte SQL, tables référencées et type d'accès (lecture ou écriture), extraits par un vrai parseur SQL.

**Endpoints exposés**

- `@RestController`, `@Controller` avec `@ResponseBody`, et JAX-RS (`@Path`, `@GET`, `@POST`…) pour JBoss.
- Verbe HTTP, chemin complet (préfixe de classe + méthode + `server.servlet.context-path`), classe et méthode Java, types d'entrée et de sortie.

**Appels sortants**

- `@FeignClient` (nom, `url`, `path`) et ses méthodes annotées.
- `RestTemplate`, `WebClient`, `RestClient`, et client JAX-RS : verbe et URL, quand ils sont résolubles statiquement.
- Les URL sont résolues avec les propriétés de configuration (`${...}`) et le nom de l'application cible est déduit quand c'est possible. Sinon, l'appel est gardé avec `target_app: null` et l'URL brute.
- Producteurs et consommateurs JMS/ActiveMQ (`@JmsListener`, `JmsTemplate`), avec le nom de la destination.

## 3. Le contrat JSON de sortie

Un fichier par application : `out/<application>.json`, encodé en UTF-8, clés triées, tableaux triés par identifiant, indentation de 2 espaces, fin de ligne LF. Chaque objet porte un `id` stable, calculé à partir de son contenu, jamais d'un compteur.

```json
{
  "contract_version": "1.0",
  "application": {
    "id": "s-gen-gpp",
    "name": "s-gen-gpp",
    "version": "3.2.0",
    "tech": ["spring-boot", "kotlin"],
    "repository": "bitbucket/cafat/s-gen-gpp",
    "commit": "9f2c1e4",
    "datasources": [
      { "id": "pg", "kind": "postgresql", "jdbc_url": "jdbc:postgresql://<host>/gpp" },
      { "id": "db400", "kind": "db2", "jdbc_url": "jdbc:as400://<host>" }
    ]
  },
  "entities": [
    {
      "id": "s-gen-gpp:fr.cafat.gpp.PersonnePhysique",
      "class": "fr.cafat.gpp.PersonnePhysique",
      "kind": "entity",
      "datasource": "pg",
      "schema": "sgengpp",
      "table": "gpp_personne_physique",
      "is_view": false,
      "parent": null,
      "inheritance": { "strategy": "JOINED", "discriminator": null },
      "source": { "file": "src/main/kotlin/fr/cafat/gpp/PersonnePhysique.kt", "line": 18 },
      "columns": [
        {
          "name": "numero_interne", "field": "numeroInterne", "java_type": "Long",
          "pk": true, "nullable": false, "unique": true, "length": null,
          "enum_values": null
        }
      ]
    }
  ],
  "relations": [
    {
      "id": "s-gen-gpp:PersonnePhysique.moyensContact",
      "from_entity": "s-gen-gpp:fr.cafat.gpp.PersonnePhysique",
      "to_entity": "s-gen-gpp:fr.cafat.gpp.MoyenContact",
      "field": "moyensContact",
      "type": "ONE_TO_MANY",
      "owning_side": false,
      "mapped_by": "personnePhysique",
      "join_columns": ["fk_personne_physique"],
      "join_table": null,
      "optional": true,
      "source": { "file": "…", "line": 42 }
    }
  ],
  "sql_accesses": [
    {
      "id": "s-gen-gpp:sha1(sql)",
      "origin": "native_query",
      "datasource": "db400",
      "sql": "SELECT … FROM MGENGPP.GPP_PERSONNE_PHYSIQUE WHERE …",
      "tables": [ { "schema": "MGENGPP", "name": "GPP_PERSONNE_PHYSIQUE", "access": "read" } ],
      "parsed": true,
      "source": { "file": "…", "line": 77 }
    }
  ],
  "endpoints": [
    {
      "id": "s-gen-gpp:GET:/api/personnes/{id}",
      "method": "GET",
      "path": "/api/personnes/{id}",
      "handler": "fr.cafat.gpp.api.PersonneController#getPersonne",
      "request_type": null,
      "response_type": "fr.cafat.gpp.api.dto.PersonneDto",
      "source": { "file": "…", "line": 31 }
    }
  ],
  "calls": [
    {
      "id": "s-gen-cli:GET:s-gen-gpp:/api/personnes/{id}",
      "client": "feign",
      "method": "GET",
      "raw_url": "${gpp.url}/api/personnes/{id}",
      "resolved_url": "http://s-gen-gpp/api/personnes/{id}",
      "target_app": "s-gen-gpp",
      "caller": "fr.cafat.cli.client.GppClient#getPersonne",
      "source": { "file": "…", "line": 12 }
    }
  ],
  "messaging": [
    {
      "id": "s-gen-gpp:produce:queue:GPP.PERSONNE.MAJ",
      "role": "produce",
      "destination_type": "queue",
      "destination": "GPP.PERSONNE.MAJ",
      "source": { "file": "…", "line": 55 }
    }
  ],
  "diagnostics": [
    { "level": "warning", "code": "URL_UNRESOLVED", "message": "…", "source": { "file": "…", "line": 90 } }
  ]
}
```

*Les valeurs ci-dessus sont des exemples de forme, pas des données réelles.*

**Règles du contrat**

- Livrer aussi `schema/meta-extract.schema.json` (JSON Schema draft 2020-12). L'outil valide chaque sortie contre ce schéma et échoue si elle n'est pas conforme.
- Une valeur inconnue vaut `null`, jamais une chaîne vide ni une valeur devinée.
- Tout ce que l'outil n'a pas su traiter va dans `diagnostics`, avec un code stable (`URL_UNRESOLVED`, `SQL_UNPARSED`, `DATASOURCE_AMBIGUOUS`, `PARENT_NOT_FOUND`…).
- Les références entre objets (`from_entity`, `to_entity`, `target_app`) utilisent les `id`, pour que le chargeur et dbt fassent des jointures simples.
- Toute évolution incompatible du contrat incrémente `contract_version`.

## 4. Exigences techniques

**Stack**

- Java 21, build Maven, livré en JAR exécutable unique (fat JAR) et en image Docker.
- **Spoon** (INRIA) comme moteur d'analyse, en mode sans classpath complet (`noClasspath`), pour lire les dépôts sans les compiler.
- Pour le Kotlin, utiliser un parseur Kotlin déterministe (par exemple l'API PSI du compilateur Kotlin), branché sur le même modèle interne que Spoon.
- **JSqlParser** pour extraire les tables du SQL natif. Jackson pour écrire le JSON, avec tri des clés activé.
- Aucune dépendance réseau à l'exécution.

**Structure du programme**

```
java-meta-extractor/
  cli/          point d'entrée, options, codes de sortie
  scan/         découverte des modules, des sources et de la configuration
  config/       lecture des yml, properties, persistence.xml, résolution des ${...}
  model/        objets du contrat (records Java immuables)
  extract/
    entities/   entités, colonnes, héritage
    relations/  associations JPA
    sql/        requêtes natives, JdbcTemplate, MyBatis, littéraux SQL
    rest/       endpoints Spring MVC et JAX-RS
    calls/      Feign, RestTemplate, WebClient, RestClient
    jms/        producteurs et consommateurs
  resolve/      liens entre entités, rattachement aux sources de données
  output/       sérialisation triée et validation JSON Schema
  schema/       meta-extract.schema.json
```

Chaque extracteur est une classe indépendante, testée seule, qui prend le modèle Spoon et renvoie des objets du contrat.

**Interface en ligne de commande**

```
java -jar java-meta-extractor.jar \
  --repo /chemin/vers/s-gen-gpp \
  --commit 9f2c1e4 \
  --out out/ \
  [--app-name s-gen-gpp] [--profile prod] [--fail-on-warning]

java -jar java-meta-extractor.jar --repos-dir /chemin/vers/depots --out out/
```

- Code de sortie 0 si tout va bien, 1 si au moins un diagnostic de niveau `error`, 2 si la sortie ne respecte pas le schéma.
- Le résumé d'exécution (nombre d'entités, de relations, d'endpoints, d'appels, de diagnostics) s'écrit sur la sortie d'erreur, jamais dans le JSON.
- Un dépôt multi-modules produit un fichier par module déployable.

**Héritage JPA, le point dur**

- Construire d'abord le graphe complet des classes du dépôt, puis résoudre l'héritage : une entité hérite des colonnes de ses `@MappedSuperclass` et de son parent `@Entity`.
- Pour `JOINED`, chaque sous-classe a sa table et sa colonne de jointure vers le parent (`@PrimaryKeyJoinColumn`, sinon la clé du parent).
- Pour `SINGLE_TABLE`, toutes les sous-classes pointent vers la table du parent avec leur `@DiscriminatorValue`.
- Pour `TABLE_PER_CLASS`, chaque sous-classe porte toutes les colonnes héritées.
- Un parent hors du dépôt, par exemple dans une bibliothèque CAFAT partagée, donne `parent: "<nom qualifié>"` et un diagnostic `PARENT_NOT_FOUND`, sans bloquer le reste.

## 5. Cas limites et critères d'acceptation

**Cas limites à traiter explicitement**

- **Double mapping Postgres / DB2** : la même notion peut être mappée deux fois, avec une classe pour chaque base (par exemple `Union` et `PGUnion`, ou des classes suffixées `DB2`). Produire deux entités distinctes, chacune rattachée à sa source de données, sans tenter de les fusionner.
- **Vues DB2** : une entité mappée sur une vue (`MGENGPP.*`) a `is_view: true` quand le nom ou un commentaire le permet, sinon `null`.
- **Colonne même nom, champ différent** : par exemple la colonne `id` mappée sur `histoNumero` dans une classe et sur `id` dans une autre. Garder les deux mappings.
- **Liens logiques sans clé étrangère** (jointure par `matricule`, par exemple) : ne pas les inventer. Ils sortiront plus tard des requêtes SQL ou d'une saisie manuelle.
- **SQL dynamique** construit par concaténation : garder les morceaux littéraux, extraire les tables reconnaissables, mettre `parsed: false` et un diagnostic `SQL_UNPARSED`.
- **URL d'appel non résolue** (variable d'environnement, découverte de service) : `target_app: null`, l'URL brute conservée, et un diagnostic `URL_UNRESOLVED`.
- **Code mort ou de test** : ignorer `src/test`. Signaler les classes `@Entity` non référencées par un diagnostic `info`, sans les exclure.

**Critères d'acceptation**

- [ ] Deux exécutions sur le même commit produisent des fichiers identiques (test automatisé par comparaison de hash).
- [ ] Chaque sortie est valide contre `meta-extract.schema.json`.
- [ ] Un dépôt de test (fixtures) couvre : héritage `JOINED`, `SINGLE_TABLE` et `@MappedSuperclass`, relations des quatre types, `@Embedded`, enum, double mapping Postgres/DB2, requête native, `JdbcTemplate`, contrôleur Spring MVC, ressource JAX-RS, client Feign, `RestTemplate` avec URL en propriété, `@JmsListener`, et une classe Kotlin.
- [ ] Sur ce dépôt de test, la sortie est comparée à un JSON attendu (test « golden file »).
- [ ] Validation réelle sur un dépôt pilote, **s-gen-gpp** : toutes ses tables PostgreSQL (`sgengpp.gpp_*`) et ses vues DB2 (`MGENGPP.*`) sont retrouvées, avec la hiérarchie `MoyenContact` correctement résolue.
- [ ] Aucune valeur sensible (mot de passe, jeton) n'apparaît dans la sortie.
- [ ] Un dépôt d'une centaine de classes est analysé en moins d'une minute.
- [ ] Un `README` explique l'installation, la CLI, le contrat et la façon d'ajouter un extracteur.

**Méthode de travail attendue**

1. Commencer par le modèle du contrat, le JSON Schema et le dépôt de test, avant tout extracteur.
2. Implémenter les extracteurs un par un, chacun avec ses tests, dans l'ordre : entités et colonnes, héritage, relations, endpoints, appels, SQL, JMS.
3. Lancer l'outil sur s-gen-gpp et me montrer le résumé et la liste des diagnostics avant d'aller plus loin.
4. Me poser une question dès qu'une règle de ce document est ambiguë, plutôt que de deviner.
