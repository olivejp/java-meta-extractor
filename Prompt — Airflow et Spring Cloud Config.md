# Contexte

Nous construisons une cartographie du SI de la CAFAT (liens REST, bases de données, JMS entre applications). L'outil `java-meta-extractor` analyse le code source des applications Java/Kotlin et produit un JSON par application (contrat 1.2, même forme que `angular-meta-extractor`). Le fichier porte le nom de l'application sans suffixe de version (`s-gen-geo.json`, même si `spring.application.name` vaut `s-gen-geo-5.0`).

Il est livré sous forme d'image Docker et doit être lancé ponctuellement par Airflow, pour analyser certains dépôts à la demande, **pour un environnement donné** (`dev`, `int`, `val`…).

Contraintes de l'image, non négociables :
- Elle tourne **sans réseau** (`--network none`) : elle ne clone rien, n'appelle aucun serveur et ne contient ni git ni identifiants.
- Ses **entrées sont des répertoires montés en lecture seule**, préparés en amont par l'appelant.
- Elle écrit **uniquement dans `--out`**.
- Elle tourne avec l'**utilisateur non root uid 10001** : les fichiers montés doivent être lisibles par cet uid (`chmod -R a+rX`).
- **Codes de sortie** :
  - 0 : succès ;
  - 1 : au moins un diagnostic `error`, ou un `warning` avec `--fail-on-warning` ;
  - 2 : sortie non conforme au schéma JSON ;
  - 3 : erreur d'usage, dépôt introuvable ou exception interne.

# Le problème

Le parc est **mixte** et le restera un moment :
- **Applications classiques** : toute leur configuration est dans leur dépôt.
- **Applications passées sous Spring Cloud Config** : une partie de leur configuration est dans le dépôt git du serveur Config (`spring-cloud-config-repo`). Il s'agit des URL des dépendances REST (`cafat.config.rest-dependency.*`), des sources de données et des files JMS.

Sans ce dépôt, l'extracteur ne peut pas résoudre les URL ni les sources de données des applications migrées.

Organisation du dépôt `spring-cloud-config-repo` :
- **Une branche par environnement** : `dev`, `int`, `val`, `gesenv`, `master`. Il n'existait pas encore de branche `prd` le 13/10/2026.
- **Fichiers à la racine** : `application[-profil].yml` (communs) et `{application}-{version}[-profil].yml`, par exemple `s-gen-dif-2.0-rest-dependency.yml`.
- **Profils fonctionnels**, et non d'environnement (`rest-dependency`, `datasource-as400`, `activemq`, `secure`…), activés par chaque application dans son propre `application.yml` (`spring.profiles.active` et `spring.profiles.group`).
- **Aucune valeur `{cipher}`** : les secrets sont dans Vault, que l'extracteur ne lit pas.

# Ce que fait l'extracteur

- `--config-repo DIR` : clone du dépôt de configuration. Il n'est appliqué qu'aux applications clientes du serveur Config, détectées dans leur code. Les autres gardent leur configuration locale. **Une seule exécution traite tout le parc.**
- `--environment ENV` : environnement ciblé (valeur libre), inscrit dans la sortie (`application.environment`). L'extracteur lit la branche du clone dans `.git/HEAD` ; si elle diffère de `ENV`, il émet un diagnostic `warning` `CLOUD_CONFIG_LABEL_MISMATCH`.
- **Profils** : sans `--profile`, chaque application reçoit ceux qu'elle déclare, comme Spring au démarrage. **Il ne faut donc pas passer `--profile`.**
- **`--config-search-paths` est inutile**, puisque le dépôt range tout à la racine.
- **Dans la sortie**, `application.cloud_config` indique :
  - `client` : l'application est-elle passée sous Spring Cloud Config ?
  - `status` : `not_client`, `not_provided`, `not_found` ou `applied` ;
  - `names` : noms demandés au serveur ;
  - `label` : branche lue ;
  - `files` : fichiers appliqués.

# Solution retenue

**C'est Airflow qui met la configuration à disposition, pas le conteneur.** Le DAG reçoit l'environnement ciblé en paramètre, puis :
1. **Clone ou met à jour** `spring-cloud-config-repo` sur la **branche de cet environnement** (`git clone --depth 1 -b <env>`), avec une **clé de déploiement Bitbucket en lecture seule** gardée côté Airflow (connexion, variable ou secret). La clé n'entre jamais dans l'image de l'extracteur. Le `.git` du clone doit rester présent : l'extracteur y lit la branche.
2. **Clone les dépôts à analyser**, avec leur `.git` (un clone superficiel suffit : l'extracteur y lit l'URL du remote et le commit), sans modification locale. Idéalement, chaque dépôt est pris dans la version déployée sur cet environnement, sinon on croise le code de `master` avec la configuration d'un autre environnement.
3. **Lance l'extracteur** avec les deux montages en lecture seule :

```
docker run --rm --network none \
  -v <depots>:/depots:ro -v <config-repo>:/config:ro -v <out>:/out \
  java-meta-extractor:<tag> --repos-dir /depots --out /out \
  --config-repo /config --environment <env>
```

Avec `--repos-dir`, chaque sous-répertoire contenant un `pom.xml`, un build Gradle ou un `.git` est analysé.

Solution écartée : interroger le serveur Config par HTTP (`/{label}/{app}-{profil}.yml`) pour exporter la configuration fusionnée. Il faudrait ouvrir le réseau et transporter des identifiants, et le serveur peut renvoyer des valeurs déchiffrées. Ce n'est qu'un dernier recours.

# Ce que je te demande

1. **Analyser ce dépôt et me dire** :
   - quel opérateur lance les conteneurs (DockerOperator, KubernetesPodOperator, autre) ;
   - comment les dépôts sont déjà clonés aujourd'hui, s'ils le sont ;
   - comment sont gérés les identifiants Bitbucket ;
   - comment les tâches partagent des fichiers (volume de l'hôte, PVC, stockage objet) ;
   - comment un DAG reçoit ses paramètres (`params`, configuration de déclenchement) ;
   - les conventions des DAG existants (nommage, paramètres, gestion des erreurs).
2. **Me proposer un plan avant d'écrire le moindre code** : un DAG, ou une évolution d'un DAG existant, avec un paramètre `environment`. Il doit cloner le dépôt de configuration sur la branche correspondante, cloner les dépôts à analyser, puis lancer `java-meta-extractor` avec les montages et les options ci-dessus, en respectant les conventions du dépôt.
   - Avec DockerOperator : attention, les chemins montés sont ceux de l'hôte du démon Docker.
   - Avec KubernetesPodOperator : je préfère un conteneur d'initialisation git qui clone dans un volume temporaire (`emptyDir`) monté en lecture seule dans le conteneur de l'extracteur, et une sortie sur un volume persistant.
   - Prévois de séparer les sorties par environnement (par exemple `<out>/<env>/`), pour pouvoir comparer deux environnements.
3. **Prévoir le traitement du code de sortie 1** : diagnostics `error` présents mais fichiers JSON produits. Propose une option : faire échouer la tâche ou continuer en le signalant. Le `warning` `CLOUD_CONFIG_LABEL_MISMATCH` ne fait échouer la tâche qu'avec `--fail-on-warning`, mais il révèle une erreur du DAG : il faut le repérer.
4. **Lister ce qui te manque**, sans inventer de valeur :
   - l'URL Bitbucket de `spring-cloud-config-repo` ;
   - la liste des environnements autorisés et leur branche (identique au nom ? `prd` existera-t-elle ?) ;
   - la version du code à analyser pour chaque environnement (branche, tag ou commit déployé) ;
   - le tag de l'image ;
   - la liste des dépôts à analyser.

   Cherche ces valeurs dans le dépôt ; sinon, laisse des paramètres explicites et pose-moi la question.
