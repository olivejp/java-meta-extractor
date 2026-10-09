# Consolidation de la cartographie

Projet dbt (PostgreSQL) qui assemble les JSON de java-meta-extractor, les catalogues des bases et le référentiel urbanisme (SA, SSA) en une cartographie des liens entre applications : appels REST, tables partagées, échanges JMS.

## Couches

| Schéma | Contenu | Alimenté par |
|---|---|---|
| `landing` | JSON bruts (`extraction`, en `jsonb`) et catalogues bruts (`catalog_column`). | `scripts/load_*.sh` |
| `referentiel` | Référentiel urba et rattachement des sources aux bases physiques. | seeds dbt (`seeds/*.csv`) |
| `staging` | Vues qui aplatissent chaque tableau du contrat JSON et le catalogue. Version courante de chaque application uniquement. | dbt |
| `core` | Modèle normalisé : SA, SSA, applications, sources, tables, usages de tables et de colonnes, appels REST, files et échanges JMS, chacun avec son statut de résolution. | dbt |
| `carto` | Vues de cartographie : graphe (`mart_node`, `mart_edge`), flux entre SSA, matrice CRUD SSA × table, anomalies. | dbt |

Toute la base se reconstruit depuis les fichiers : aucune saisie directe en base.

## Chaîne complète

Connexion par les variables libpq habituelles : `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`.

```bash
# 1. JSON de l'extracteur. Le dernier chargé pour une application devient sa version courante.
scripts/load_extractions.sh ../out/*.json

# 2. Catalogue de chaque base physique, exporté à part (voir plus bas).
scripts/load_catalog.sh AS400 catalogues/as400.csv

# 3. Référentiel, transformations et tests.
dbt build --profiles-dir .
```

Sans dbt installé localement :

```bash
docker run --rm --network host -u "$(id -u):$(id -g)" -e HOME=/tmp \
  -e PGHOST -e PGPORT -e PGDATABASE -e PGUSER -e PGPASSWORD \
  -v "$PWD:/usr/app" -w /usr/app ghcr.io/dbt-labs/dbt-postgres:1.8.2 build --profiles-dir .
```

## Catalogues des bases

Les requêtes de `scripts/catalog/` ne lisent que des métadonnées : schémas, tables, colonnes, types. Elles produisent un CSV commun, avec en-tête, que `load_catalog.sh` charge sous un identifiant de base.

| Base | Requête | Lancement |
|---|---|---|
| PostgreSQL | `export_postgresql.sql` | `psql -d <base> -f scripts/catalog/export_postgresql.sql > catalogues/<base>.csv` |
| DB2 for i | `export_db2i.sql` | ACS ou DBeaver, export CSV avec en-tête, colonnes dans l'ordre de la requête. Adapter la liste des bibliothèques. |

Le répertoire `catalogues/` est ignoré par git.

L'identifiant de base (`AS400`, `PG_GEN`…) est libre. Il doit être le même dans `load_catalog.sh` et dans `seeds/datasource_binding.csv`.

Une table citée par le code est cherchée dans le catalogue de sa base par nom SQL ou nom système DB2 (10 caractères). Sans schéma connu, elle n'est rattachée que si un seul candidat porte ce nom dans la base.

## Référentiel (seeds)

| Fichier | Rôle | Défaut sans ligne |
|---|---|---|
| `datasource_binding.csv` | Rattache `(application, source)` à une base physique et fixe le schéma par défaut. | La source reçoit une base propre à l'application (`?app/source`) : ses tables ne sont comparées à aucune autre. |
| `urba_application.csv` | SA et SSA d'une application. | Convention `s-<sa>-<ssa>` sur le nom. |
| `urba_sa.csv`, `urba_ssa.csv` | Libellés des SA et SSA. | Code seul. |
| `urba_data_owner.csv` | SSA propriétaire de tables, par motifs `LIKE` en majuscules sur le schéma et le nom. Avec `database = JMS`, `table_pattern` s'applique aux destinations JMS. La plus forte `priority` l'emporte. | Bibliothèque `M<SA><SSA>`, sinon préfixe `<SSA>_` pour les tables. `Q<SA><SSA>` pour les files. |

On ne saisit que les exceptions à la convention. La colonne `classification_source` ou `owner_source` dit d'où vient chaque rattachement.

Les trois lignes AS400 livrées dans `datasource_binding.csv` sont des hypothèses à confirmer. Elles se fondent sur la propriété `${as400.server-name}` commune aux trois applications et sur le paramètre `librairies=` des URL JDBC. Ce paramètre est mal orthographié (le pilote attend `libraries=`) : l'extracteur ne le lit pas, et le pilote ne l'applique probablement pas non plus.

## Statuts de résolution

`core_rest_call.status` :

| Statut | Sens |
|---|---|
| `resolved` | Un seul endpoint de même verbe (ou `ANY`) et même gabarit de chemin. |
| `ambiguous` | Plusieurs endpoints candidats. |
| `partial` | Chemin connu en partie : fragment inconnu collé au texte (`/s-gen-geo-5.0{property}`) ou valeur masquée (`***`). |
| `unresolved` | Chemin inconnu de l'extracteur. |
| `no_endpoint` | L'application visée est extraite, mais aucun endpoint ne correspond. |
| `version_mismatch` | L'application visée est extraite, mais sous un autre context path versionné. |
| `target_not_extracted` | Aucune application extraite ne porte ce context path. `to_app_hint` donne `s-<sa>-<ssa>` d'après le chemin. |

`core_table_usage.catalog_status` : `present`, `absent`, `ambiguous`, `no_catalog` (aucun catalogue chargé pour cette base).

`core_jms_exchange.status` : `linked`, `no_producer`, `no_consumer`, `unresolved_destination`.

## Anomalies (`carto.mart_anomaly`)

| Code | Niveau | Cause |
|---|---|---|
| `TABLE_ABSENTE_CATALOGUE`, `COLONNE_ABSENTE_CATALOGUE` | error | Mapping ou SQL qui vise un objet absent de la base. |
| `APPEL_SANS_ENDPOINT` | error | Appel vers une application extraite qui n'expose pas ce chemin. |
| `TABLE_AMBIGUE_CATALOGUE` | warning | Plusieurs tables candidates dans le catalogue. |
| `ECRITURE_AUTRE_SSA` | warning | Mapping JPA ou écriture SQL sur une table d'un autre SSA. |
| `APP_NON_CLASSEE`, `SOURCE_NON_RATTACHEE`, `TABLE_SANS_PROPRIETAIRE` | warning | Référentiel à compléter. |
| `APPEL_URL_INCONNUE`, `APPEL_URL_PARTIELLE`, `APPEL_AMBIGU`, `APPEL_VERSION_DIFFERENTE` | warning | Appel REST non rapproché. |
| `JMS_SANS_CONSOMMATEUR`, `JMS_SANS_PRODUCTEUR`, `JMS_DESTINATION_INCONNUE` | warning | Échange JMS incomplet. |
| `LECTURE_AUTRE_SSA` | info | Lecture SQL d'une table d'un autre SSA. |
| `APP_SA_HORS_PROJET` | info | SA déduit du nom différent du projet Bitbucket. |
| `APPEL_CIBLE_NON_EXTRAITE` | info | Application cible pas encore extraite. |

## Limites

- Seul le code Java et Kotlin est vu. Les programmes RPG et CL, les requêtes AS400, les ETL et les batchs non Java n'apparaissent pas. Une table sans usage Java (`core_table.used_by_java = false`) n'est donc pas forcément inutilisée, et une file sans producteur peut être alimentée par l'AS400.
- Un mapping JPA donne l'accès `mapped` : lecture et écriture possibles, sans distinction.
- Toutes les destinations JMS sont supposées sur un même broker.
- Les rattachements par convention sont des heuristiques, à corriger par le référentiel.
