# java-meta-extractor

Extracteur déterministe de métadonnées pour les dépôts Java et Kotlin de la CAFAT. Il lit le code sans le compiler (Spoon en `noClasspath`, PSI Kotlin, JSqlParser) et écrit un JSON par application déployable : `out/<application>.json`.

L'outil n'effectue aucun appel réseau, n'utilise aucun LLM, ne touche à aucune base et n'écrit ni date ni valeur aléatoire. Deux exécutions sur le même commit produisent des fichiers identiques octet pour octet.

## Fonctionnement

`cli/Main` lit les options puis appelle `cli/Pipeline.run` pour chaque dépôt. Le pipeline produit un résultat par unité déployable, que `Main` valide et écrit.

```text
dépôt ─► 1 inventaire ─► 2 configuration ─► 3 lecture du code ─► 4 extraction ─► 5 sources de données ─► 6 assemblage ─► out/<app>.json
```

| # | Étape | Classes | Résultat |
|---|---|---|---|
| 1 | Inventaire (`scan/`) | `RepoScanner`, `MavenReader`, `GradleReader`, `GitInfo` | Modules, fichiers par module (tests et répertoires de build exclus), unités déployables, origine et commit git. |
| 2 | Configuration (`config/`) | `ConfigLoader`, `CloudConfigRepo`, `PersistenceXmlReader`, `Secrets` | Par unité : `bootstrap*` et `application*` fusionnés avec les profils demandés, filtrage Maven appliqué, puis configuration Spring Cloud Config par-dessus ; secrets masqués ; unités de persistance. |
| 3 | Lecture du code (`parse/`, `spoon/`) | `SpoonLoader`, `KotlinToSpoon`, `TypeIndex`, `ValueEval` | Modèle Spoon (`noClasspath`) du Java et du Kotlin traduit ; index des types ; évaluation statique des chaînes (constantes, concaténations, `${…}`). |
| 4 | Extraction (`extract/`) | `PersistenceExtractor` (`EntityExtractor`, `InheritanceResolver`, `RelationExtractor`), `SqlExtractor` + `SqlAnalyzer`, `EndpointExtractor`, `CallExtractor`, `JmsExtractor` | Entités et colonnes, héritage, relations, accès SQL et tables, endpoints, appels HTTP sortants, échanges JMS. |
| 5 | Sources de données (`config/`, `resolve/`) | `DatasourceDetector`, `DatasourceResolver` | Sources lues dans la configuration ; entités, SQL et tables de jointure rattachés à leur source ; schéma par défaut ; `is_view`. |
| 6 | Assemblage (`output/`, `cli/`) | `Assembler`, `CanonicalJson`, `SchemaValidator`, `Report` | Ids calculés, doublons retirés, tableaux triés ; JSON canonique validé par le schéma ; rapport sur stderr. |

Tous les extracteurs reçoivent un `ExtractionContext` : modèle, index des types, évaluateur, configuration, stratégie de nommage, ressources XML, modules web et collecteur de `Diagnostics`. Ce qui n'est pas déterminable devient `null` et un diagnostic, jamais une valeur devinée.

Les étapes 1 à 3 sont bloquantes : un échec arrête le dépôt (code 3). Les étapes 4 et 5 sont isolées : un échec donne `EXTRACTION_STEP_FAILED` et les autres étapes continuent (voir « Lire les erreurs »).

## Construire

Java 21 et Maven 3.9 sont nécessaires. Le réseau ne sert qu'au build, pour télécharger les dépendances.

```bash
mvn -B package
```

Le build produit `target/java-meta-extractor.jar`, un JAR exécutable unique.

Pour l'image Docker :

```bash
docker build -t java-meta-extractor:dev .
```

## Utiliser

```bash
java -jar target/java-meta-extractor.jar --repo /chemin/vers/s-gen-gpp --commit 9f2c1e4 --out out/
java -jar target/java-meta-extractor.jar --repo /chemin/vers/s-gen-gpp --out out/ --app-name s-gen-gpp --profile prod --fail-on-warning
java -jar target/java-meta-extractor.jar --repos-dir /chemin/vers/depots --out out/
```

Avec Docker, sans réseau, le dépôt monté en lecture seule :

```bash
docker run --rm --network none -v /chemin/vers/s-gen-gpp:/repo:ro -v "$PWD/out:/out" java-meta-extractor:dev --repo /repo --out /out
```

| Option | Rôle |
|---|---|
| `--repo DIR` | Dépôt à analyser. |
| `--repos-dir DIR` | Analyse chaque sous-répertoire qui contient un `pom.xml`, un build Gradle ou un `.git`, dans l'ordre alphabétique. Exclusif avec `--repo`. |
| `--commit SHA` | Commit inscrit dans la sortie. Par défaut, HEAD est lu dans `.git`, sans commande git. Uniquement avec `--repo`. |
| `--out DIR` | Répertoire de sortie, créé au besoin. |
| `--app-name NOM` | Nom imposé, si le dépôt n'a qu'une seule unité déployable. Uniquement avec `--repo`. |
| `--profile P[,P…]` | Profils Spring actifs, dans l'ordre. |
| `--config-repo DIR` | Clone local du dépôt Spring Cloud Config, appliqué aux applications clientes (voir « Spring Cloud Config »). |
| `--config-search-paths C[,C…]` | Répertoires de recherche de ce dépôt, comme `search-paths` du serveur : `{application}`, `{profile}` et `*` acceptés. Défaut : racine seule. |
| `--fail-on-warning` | Un diagnostic `warning` donne aussi le code 1. |
| `--view-schemas S[,S…]` | Schémas dont toutes les tables sont des vues. Défaut : `MGENGPP`. |
| `--max-warnings N` | Occurrences affichées par code d'avertissement dans le rapport. Défaut : 5. |
| `--stacktrace` | Affiche la pile Java complète des erreurs internes. |
| `--list-diagnostics` | Liste les codes de diagnostic avec leur origine, ce qui manque et ce qu'il faut faire. |

Codes de sortie (le plus élevé l'emporte) :

| Code | Signification |
|---|---|
| 0 | Succès. |
| 1 | Au moins un diagnostic `error` (dont une étape en échec, `EXTRACTION_STEP_FAILED`), ou `warning` avec `--fail-on-warning`. |
| 2 | Sortie non conforme au schéma. Le fichier est alors écrit sous `<application>.json.invalid` et les erreurs sur stderr. |
| 3 | Erreur d'usage (options), dépôt introuvable ou exception interne qui empêche toute sortie. |

## Dépôts en entrée

L'extracteur ne récupère aucun dépôt : il lit des copies locales déjà extraites. Le clonage, la mise à jour, le choix de la branche et la sélection des dépôts relèvent d'un outil placé en amont, hors de ce projet. Ce qu'il doit fournir :

- **Un sous-répertoire par dépôt** dans le répertoire passé à `--repos-dir`. Ne sont retenus que ceux qui contiennent à la racine un `pom.xml`, un build Gradle ou un `.git` ; fichiers et autres répertoires sont ignorés.
- **Le code au commit à analyser**, sans modification locale : l'extracteur lit les fichiers tels qu'ils sont sur disque.
- **Le `.git` du clone** (un clone superficiel suffit) : l'URL du remote `origin` et le commit de HEAD y sont lus sans commande git. Sans `.git`, `repository` et `commit` valent `null` dans la sortie, et la consolidation ne peut plus déduire le projet Bitbucket. Avec `--repo`, `--commit` remplace le commit lu.
- **Uniquement des dépôts Java ou Kotlin** : un sous-répertoire avec un `.git` mais sans `pom.xml` ni build Gradle est analysé en entier comme une seule application (avertissement `NO_DEPLOYABLE_MODULE`).
- **Le dépôt Spring Cloud Config** éventuel, extrait sur le label servi, à passer par `--config-repo` (voir « Spring Cloud Config »).

```bash
java -jar target/java-meta-extractor.jar --repos-dir ~/carto/depots --out out/
```

## Lire les erreurs

Tout s'écrit sur stderr, jamais dans le JSON. Pour chaque application, une ligne de résumé (nombre d'entités, de relations, d'accès SQL, d'endpoints, d'appels, d'échanges JMS et de diagnostics par niveau), puis un rapport groupé par niveau et par code :

```text
  AVERTISSEMENT · URL_UNRESOLVED · URL d'appel REST non résolue · 1 occurrence
    origine : valeur connue seulement à l'exécution ou au déploiement
    manque  : l'appel est gardé sans URL complète ni application cible
    à faire : vérifier que les propriétés citées « inconnu : … » sont dans application*.yml du dépôt ; …
    - src/main/java/nc/cafat/gen/ridet/service/EntrepriseService.java:189 — URL non résolue : ${sgenedt.editer.url} (inconnu : api.host)
```

- **origine** dit où chercher la correction : code du dépôt analysé, configuration du dépôt, options de lancement, valeur connue seulement à l'exécution, bibliothèque hors du dépôt, ou limite de l'extracteur ;
- **manque** dit ce qui est absent ou incomplet dans le JSON ;
- **à faire** dit quoi corriger, ou pourquoi il n'y a rien à faire ;
- chaque occurrence donne `fichier:ligne` (relatif au dépôt) et le message.

Toutes les erreurs sont listées, 5 occurrences par code d'avertissement (`--max-warnings`) et une par code d'info ; le JSON garde la liste complète. `--list-diagnostics` affiche le catalogue complet (`extract/DiagnosticCatalog`).

**Erreurs internes.** Chaque étape de l'extraction (entités JPA, SQL, sources de données, endpoints, appels, JMS) est isolée : si elle lève une exception, elle devient un diagnostic `error` `EXTRACTION_STEP_FAILED` qui nomme l'étape, l'exception et la ligne de l'extracteur en cause (`à CallExtractor.java:86, CallExtractor.extract`), et les autres étapes produisent leur résultat. Une exception dans une étape sans laquelle rien ne peut suivre (structure du dépôt, configuration, lecture du code) arrête le dépôt avec le code 3 et un bloc `ÉCHEC DE L'EXTRACTION` (étape, erreur, à faire). `--stacktrace` ajoute la pile complète.

## Spring Cloud Config

Les URL, sources de données et files définies dans un serveur Spring Cloud Config sont lues dans un clone local de son dépôt git, extrait sur la branche (label) servie :

```bash
git clone -b master ssh://…/config-repo ~/carto/config-repo
java -jar target/java-meta-extractor.jar --repos-dir ~/carto/depots --out out/ --profile prod \
  --config-repo ~/carto/config-repo --config-search-paths '{application}'
```

- **Applications concernées** : celles dont un build déclare `spring-cloud-starter-config` ou `spring-cloud-config-client`, ou dont la configuration déclare `spring.cloud.config.uri` ou `spring.config.import=configserver:…`. `spring.cloud.config.enabled=false` les exclut.
- **Nom demandé au serveur** : `spring.cloud.config.name` (liste possible), sinon `spring.application.name`, sinon l'`artifactId`.
- **Fichiers lus**, du moins au plus prioritaire : `application.*`, `{nom}.*`, puis pour chaque profil `application-{profil}.*`, `{nom}-{profil}.*`. Sans `--profile` : profil `default`. Dans chaque niveau : racine, puis `--config-search-paths` dans l'ordre ; `.properties` l'emporte sur `.yml`. Les documents YAML conditionnés par profil sont pris en compte.
- **Priorité** : la configuration distante remplace la configuration locale, clé par clé, comme le client Spring.
- **Valeurs `{cipher}…`** : déchiffrées par le serveur seulement, donc masquées (`***`).
- **Provenance** : `cloud-config:<chemin dans le dépôt de configuration>`.
- Les `search-paths` se lisent dans la configuration du serveur Config (`spring.cloud.config.server.git.search-paths`).

## Unités déployables

Un dépôt multi-modules produit un fichier par module déployable :

- un EAR, avec ses WAR et ses EJB ;
- un WAR hors EAR ;
- un jar Spring Boot (plugin ou `@SpringBootApplication`) ;
- un EJB hors EAR.

Chaque unité est analysée sur son module et ses dépendances internes, avec sa propre configuration. Sans module déployable, le dépôt entier forme une unité et reçoit le diagnostic `NO_DEPLOYABLE_MODULE`.

Le nom de l'application est, dans l'ordre :

1. `--app-name` ;
2. `spring.application.name` ;
3. l'`artifactId` du module principal, ou le nom du projet Gradle.

Deux unités qui portent le même nom reçoivent le suffixe `-<artifactId>`.

Le code sous `src/test` (et `src/<autre que main>`) n'est jamais lu.

Les fichiers `application*`/`bootstrap*` sont lus tels que Maven les place dans le jar : les ressources filtrées (`<filtering>true</filtering>`, ou les `application*` sous `spring-boot-starter-parent`) voient leurs jetons remplacés par les propriétés du pom, `project.*` (aussi sans préfixe : `@artifactId@`) et `parsedVersion.*` si `build-helper:parse-version` est déclaré. Sous le parent Spring Boot, seul `@cle@` est filtré ; sinon `${cle}` l'est aussi. Un jeton inconnu reste tel quel, comme dans Maven. `basedir` et les chemins absolus ne sont jamais substitués.

## Contrat JSON

Le schéma JSON Schema 2020-12 est dans [`schema/meta-extract.schema.json`](schema/meta-extract.schema.json). Il est embarqué dans le JAR et valide chaque sortie avant écriture.

Le fichier est encodé en UTF-8, avec les clés triées, les tableaux triés par `id`, une indentation de deux espaces et des fins de ligne LF. Une valeur inconnue vaut `null`, jamais une valeur devinée.

| Tableau | Format de l'`id` |
|---|---|
| `entities` | `app:fr.cafat.Classe` |
| `relations` | `app:fr.cafat.Classe.champ` |
| `sql_accesses` | `app:sql:<sha1 du SQL>@Classe#méthode` |
| `endpoints` | `app:VERBE:/chemin/{var}` |
| `calls` | `app:VERBE:<app cible ou ?>:<chemin>@Classe#méthode` |
| `messaging` | `app:produce\|consume:queue\|topic\|?:<destination>@Classe#méthode` |
| `diagnostics` | `app:CODE:<12 premiers caractères du sha1 du contenu>` |

Dans `sql_accesses`, `endpoints`, `calls`, `messaging` et `diagnostics`, deux objets distincts qui ont le même `id` reçoivent les suffixes `~2`, `~3`… Ces suffixes suivent l'ordre fichier, ligne, puis contenu. Aucun compteur global n'est utilisé.

Les fichiers de référence des fixtures sont dans `src/test/resources/golden/`.

### Diagnostics

Le détail de chaque code (origine, ce qui manque, ce qu'il faut faire) est dans `java -jar target/java-meta-extractor.jar --list-diagnostics`.

| Code | Niveau | Cause |
|---|---|---|
| `PARSE_ERROR` | error | Fichier Java non analysable. |
| `CONFIG_PARSE_ERROR` | error/warning | YAML, properties ou build illisible. |
| `NO_DEPLOYABLE_MODULE` | warning | Aucun module déployable : le dépôt entier est analysé. |
| `KOTLIN_UNSUPPORTED` | info | Déclaration Kotlin non traduite (classe locale, typealias…). |
| `PARENT_NOT_FOUND` | warning | Classe parente d'entité hors du dépôt. |
| `EMBEDDABLE_NOT_FOUND` | warning | `@Embedded` dont la classe est hors du dépôt. |
| `RELATION_TARGET_NOT_FOUND` | warning | Cible d'association hors du dépôt. |
| `NAMING_STRATEGY_UNKNOWN` | warning | Stratégie de nommage Hibernate non reconnue. |
| `DATASOURCE_AMBIGUOUS` | warning | Entité ou SQL non rattachable à une seule source. |
| `SQL_UNPARSED` | warning | SQL dynamique ou rejeté par JSqlParser. Les tables sont lues par repli lexical. |
| `SQL_UNRESOLVED` | warning | Texte SQL introuvable (paramètre, valeur calculée). |
| `ENDPOINT_PATH_UNRESOLVED`, `HTTP_METHOD_UNRESOLVED` | warning | Chemin ou verbe non constant. |
| `URL_UNRESOLVED` | warning | URL d'appel avec une propriété absente de la configuration. |
| `DESTINATION_UNRESOLVED` | warning | Destination JMS non constante ou absente. |
| `XML_UNREADABLE` | warning | Fichier XML (MyBatis, web.xml…) illisible. |
| `ENTITY_UNREFERENCED` | info | Entité jamais nommée ailleurs dans le code (classe gardée dans la sortie). |
| `TARGET_APP_UNKNOWN` | info | Application cible d'un appel non déduite de l'hôte. |
| `PROFILE_NOT_APPLIED` | info | Profils disponibles mais aucun demandé. |
| `CLOUD_CONFIG_NOT_PROVIDED` | info | Application cliente de Spring Cloud Config, lancée sans `--config-repo`. |
| `CLOUD_CONFIG_NOT_FOUND` | info | Aucun fichier `{nom}*` dans le dépôt de configuration : seuls les fichiers communs sont appliqués. |
| `SUBRESOURCE_LOCATOR_IGNORED` | info | Localisateur de sous-ressource JAX-RS non suivi. |
| `KOTLIN_SKIPPED` | warning | Fichiers Kotlin présents mais traduction désactivée. |
| `EXTRACTION_STEP_FAILED` | error | Exception interne dans une étape de l'extraction, isolée (voir « Lire les erreurs »). |

## Règles par défaut

- **Valeurs JPA** : seules les valeurs déclarées sont reprises (`length`, `nullable`…), sinon `null`. Les défauts de la spécification ne sont jamais inventés.
- **Stratégie de nommage** : en Spring Boot, `snake_case` en minuscules, sauf stratégie déclarée. En JPA ou JBoss, nom tel quel.
- **Rattachement d'une entité à sa source de données**, dans l'ordre :
  1. classe listée dans une unité de persistance ;
  2. paquet scanné par une fabrique d'EntityManager (`packages(..)`, `setPackagesToScan`) ;
  3. seule unité du module qui n'exclut pas les classes non listées ;
  4. seule source de données.
- **Rattachement d'un accès SQL**, dans l'ordre :
  1. seule source de données du module ;
  2. unité de persistance ou bean qualifié (`@Qualifier`, `@PersistenceContext(unitName)`, JNDI) ;
  3. entité porteuse, pour une `@NamedNativeQuery` ;
  4. paquet couvert par `@EnableJpaRepositories` ou `@MapperScan` ;
  5. bean de même nom que le champ ;
  6. bean `@Primary` ou unique de la famille (JdbcTemplate, NamedParameterJdbcTemplate, EntityManagerFactory, MyBatis) ;
  7. DataSource `@Primary` ou unique si la famille n'a aucun bean déclaré (auto-configuration Spring Boot).

  Pour le SQL JDBI, l'interface est reliée au bean `Jdbi` qui la crée : méthode `@Bean` du dépôt avec un seul paramètre `Jdbi` (son `@Qualifier`, sinon son nom), qui rend l'interface ou appelle `jdbi.onDemand(X.class)` / `attach`. Ce bean, s'il est déclaré dans le dépôt, est remonté jusqu'à sa DataSource ; s'il est défini ailleurs (bibliothèque), la source reste `null` avec `DATASOURCE_AMBIGUOUS` qui nomme le bean. Sans fabrique visible : l'unique bean `Jdbi` du dépôt. Jamais la DataSource principale, Jdbi n'ayant pas d'auto-configuration.
- **Type de base** : lu dans l'URL JDBC, sinon dans le pilote (`driver-class-name`) ou le dialecte (`database-platform`, `hibernate.dialect`) de la source ou de son préfixe JPA voisin (`spring.x.jpa` pour `spring.x.datasource`).
- **SQL natif** : `{h-schema}`, `{h-catalog}` et `{alias.*}` sont retirés avant l'analyse ; la table reçoit ensuite le schéma par défaut.
- **SQL JDBI** (origine `jdbi`, JDBI 3 et JDBI 2) : valeur de `@SqlQuery`, `@SqlUpdate`, `@SqlBatch`, `@SqlCall`, `@SqlScript` (répétée ou dans `@SqlScripts`, un accès par script), en Java comme en Kotlin. Les paramètres `:x`, `:bean.champ` et les attributs `<x>` sont remplacés par `?` pour l'analyse, le texte gardé est l'original. Sans valeur, le SQL est lu par JDBI dans un fichier (localisateur) : `SQL_UNRESOLVED`.
- **Accès en écriture** : `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `TRUNCATE`, `UPSERT`, et les ordres de structure `CREATE TABLE`, `ALTER TABLE`, `DROP TABLE` / `DROP VIEW`.
- **Destination JMS injectée** (`Queue`, `Topic`, `Destination`) : bean `@Bean` désigné par `@Qualifier`, sinon du nom du champ, sinon seul bean de type compatible.
- **URL d'appel** :
  - les méthodes du dépôt sont dépliées avec leurs arguments, y compris les clés calculées de `Environment.getProperty` (`PREFIXE + service + ".path." + nom`) ;
  - un format lu dans la configuration puis passé à `String.format` garde ses `%s`/`%d`, qui deviennent `{arg1}`, `{arg2}`… après résolution ;
  - une URL reçue en paramètre est évaluée à chaque site d'appel de la méthode : un appel par site, attribué à l'appelant.
- **Schéma par défaut** : celui de la source de données (`currentSchema` PostgreSQL, première bibliothèque DB2 `libraries=`, `hibernate.default_schema`). Il s'applique aux tables sans schéma : entités, tables secondaires, tables de jointure et SQL.
- **`is_view`** vaut `true` dans trois cas :
  - schéma de vues (`--view-schemas`) ;
  - nom préfixé ou suffixé par `V`, `VW`, `VUE` ou `VIEW` (`V_PERSONNE`, `personne_vw`…), sans tenir compte de la casse ;
  - commentaire de la classe qui parle de vue.
  Sinon, il vaut `null`.
- **Double mapping PG/DB2** : une entité par classe, chacune rattachée à sa propre source.
- **Secrets** :
  - les clés sensibles (`password`, `secret`, `token`, `username`, `user`, `credentials`…) sont masquées (`***`) dès la lecture de la configuration ;
  - l'userinfo et les paramètres sensibles des URL JDBC sont retirés ou masqués ;
  - l'URL d'origine git est nettoyée de ses identifiants.

## Ajouter un extracteur

1. Créer une classe dans `extract/<famille>/` qui prend un `ExtractionContext` et renvoie des records de `model/`. Le contexte donne le modèle Spoon, la configuration, l'évaluateur de constantes, les ressources et les diagnostics.
2. Respecter trois règles :
   - parcourir les types dans un ordre stable (`Provenance::offset`, nom qualifié) ;
   - ne jamais dépendre de l'égalité structurelle de Spoon : utiliser des collections par identité ;
   - calculer l'`id` à partir du contenu.
3. Étendre le modèle et `schema/meta-extract.schema.json`, puis brancher l'extracteur dans `cli/Pipeline` (dans un `isolated(…)`, avec un nom d'étape lisible et une valeur de repli) et `output/Assembler`. Tout nouveau code de diagnostic s'ajoute à `extract/DiagnosticCatalog`, sinon `DiagnosticCatalogTest` échoue.
4. Tester l'extracteur seul (`TestContexts.ofSources` ou une fixture), puis régénérer les fichiers de référence et relire le diff :

```bash
mvn test -Dtest=MainTest -Dgolden.update=true
```

## Tests

```bash
mvn test
```

La suite comprend :

- les tests unitaires de chaque extracteur, du traducteur Kotlin, du masquage des secrets et du résolveur de sources de données ;
- la comparaison avec les fichiers de référence ;
- le déterminisme : deux exécutions, mêmes empreintes SHA-1 ;
- l'absence de secrets dans la sortie ;
- la validation par le schéma ;
- les codes de sortie ;
- le rapport lisible, et un catalogue qui explique chaque code émis ;
- la performance : 121 classes en moins de 60 s, soit environ 1 s en pratique.

## Limites connues

- **SQL**
  - Les providers MyBatis (`@SelectProvider`…) ne sont pas lus.
  - Une table écrite n'est pas aussi marquée en lecture.
  - Pour `batchUpdate`, seul le premier argument est lu.
  - Un SQL reçu en paramètre donne `SQL_UNRESOLVED`.
  - Pour un `StringBuilder` construit hors de la méthode, seul le premier morceau est lu.
  - Les sous-classes maison de `JdbcTemplate` ne sont pas reconnues.
  - JDBI : le SQL des fichiers `.sql` ou des gabarits (`@UseClasspathSqlLocator`, `@UseStringTemplateSqlLocator`) et l'API fluide (`handle.createQuery(...)`) ne sont pas lus ; une interface enregistrée par un post-processeur maison (sans méthode `@Bean`) n'est reliée à aucun bean Jdbi.
  - `${schema}.table` perd son schéma.
- **Sources de données**
  - En DB2 avec `naming=system`, la première bibliothèque de `libraries` est prise comme schéma par défaut.
  - `is_view` ne vaut jamais `false` : rien dans le code ne prouve qu'une table n'est pas une vue.
- **JPA** : la clé étrangère d'un embeddable n'est pas relevée.
- **Endpoints et appels** :
  - le `contextRoot` d'un EAR n'est lu que dans `application.xml` et `jboss-web.xml` ;
  - les appels JAX-RS en `.target()` dynamique restent partiels ;
  - l'application cible n'est déduite que de l'hôte : un appel via une passerelle d'API garde `?` ;
  - une valeur sensible par son nom de clé reste masquée même dans une URL (`path.check-password` → `***`).
- **JMS** : les destinations déclarées dans un XML Spring et les annotations JMS au niveau de la classe ne sont pas lues.
- **Spring Cloud Config** : seul le backend git est lu, depuis un clone local ; pas de backend Vault, JDBC ou natif, ni d'appel au serveur. Un `{label}` dans les `search-paths` n'est pas remplacé.
- **Kotlin** : les sources sont traduites dans le modèle Spoon sans résolution sémantique.
  - Un membre hérité d'un type absent du dépôt reste de type `Object`.
  - Les surcharges sont choisies selon le nombre d'arguments seulement.
  - Les annotations d'une propriété du constructeur vont toutes sur le champ, quelle que soit la cible (`@get:`, `@param:`…).
  - Les objets anonymes, les références `::f`, les classes locales et les typealias génériques ne sont pas traduits. Un typealias sans paramètre de type est remplacé par sa cible.
