# java-meta-extractor

Extracteur déterministe de métadonnées pour les dépôts Java et Kotlin de la CAFAT. Il lit le code sans le compiler (Spoon en `noClasspath`, PSI Kotlin, JSqlParser) et écrit un JSON par application déployable : `out/<application>.json`.

L'outil n'effectue aucun appel réseau, n'utilise aucun LLM, ne touche à aucune base et n'écrit ni date ni valeur aléatoire. Deux exécutions sur le même commit produisent des fichiers identiques octet pour octet.

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
| `--fail-on-warning` | Un diagnostic `warning` donne aussi le code 1. |
| `--view-schemas S[,S…]` | Schémas dont toutes les tables sont des vues. Défaut : `MGENGPP`. |

Codes de sortie (le plus élevé l'emporte) :

| Code | Signification |
|---|---|
| 0 | Succès. |
| 1 | Au moins un diagnostic `error`, ou `warning` avec `--fail-on-warning`. |
| 2 | Sortie non conforme au schéma. Le fichier est alors écrit sous `<application>.json.invalid` et les erreurs sur stderr. |
| 3 | Erreur d'usage (options), dépôt introuvable ou exception interne. |

Le résumé (nombre d'entités, de relations, d'accès SQL, d'endpoints, d'appels, d'échanges JMS et de diagnostics par niveau) s'écrit sur stderr, jamais dans le JSON.

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

Deux objets distincts qui ont le même `id` reçoivent les suffixes `~2`, `~3`… Ces suffixes suivent l'ordre fichier, ligne, puis contenu. Aucun compteur global n'est utilisé.

Les fichiers de référence des fixtures sont dans `src/test/resources/golden/`.

### Diagnostics

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
| `SUBRESOURCE_LOCATOR_IGNORED` | info | Localisateur de sous-ressource JAX-RS non suivi. |

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
3. Étendre le modèle et `schema/meta-extract.schema.json`, puis brancher l'extracteur dans `cli/Pipeline` et `output/Assembler`.
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
- la performance : 121 classes en moins de 60 s, soit environ 1 s en pratique.

## Limites connues

- **SQL**
  - Les providers MyBatis (`@SelectProvider`…) ne sont pas lus.
  - Une table écrite n'est pas aussi marquée en lecture.
  - Pour `batchUpdate`, seul le premier argument est lu.
  - Un SQL reçu en paramètre donne `SQL_UNRESOLVED`.
  - Pour un `StringBuilder` construit hors de la méthode, seul le premier morceau est lu.
  - Les sous-classes maison de `JdbcTemplate` ne sont pas reconnues.
  - `${schema}.table` perd son schéma.
- **Sources de données**
  - En DB2 avec `naming=system`, la première bibliothèque de `libraries` est prise comme schéma par défaut.
  - `is_view` ne vaut jamais `false` : rien dans le code ne prouve qu'une table n'est pas une vue.
- **JPA** : la clé étrangère d'un embeddable n'est pas relevée.
- **Endpoints et appels** :
  - le `contextRoot` d'un EAR n'est lu que dans `application.xml` et `jboss-web.xml` ;
  - les appels JAX-RS en `.target()` dynamique restent partiels.
- **JMS** : les destinations déclarées dans un XML Spring et les annotations JMS au niveau de la classe ne sont pas lues.
- **Kotlin** : les sources sont traduites dans le modèle Spoon sans résolution sémantique.
  - Un membre hérité d'un type absent du dépôt reste de type `Object`.
  - Les surcharges sont choisies selon le nombre d'arguments seulement.
  - Les annotations d'une propriété du constructeur vont toutes sur le champ, quelle que soit la cible (`@get:`, `@param:`…).
  - Les objets anonymes, les références `::f`, les classes locales et les typealias ne sont pas traduits.
