-- Écarts à corriger, dans l'extracteur, le référentiel urba ou le code.
select 'APP_NON_CLASSEE' as code, 'warning' as severite, app_id, app_id as objet,
       'Nom hors convention s-<sa>-<ssa> et absent de urba_application' as detail
from {{ ref('core_application') }} where ssa_id is null
union all
select 'APP_SA_HORS_PROJET', 'info', app_id, app_id,
       'SA ' || sa_code || ' déduit du nom, projet Bitbucket ' || repository_project
from {{ ref('core_application') }}
where classification_source = 'convention' and repository_project is not null and sa_code <> lower(repository_project)
union all
select 'SOURCE_NON_RATTACHEE', 'warning', app_id, datasource,
       'Source absente de datasource_binding : ses tables ne sont comparées à aucune autre application'
from {{ ref('core_datasource') }} where not is_bound
union all
select distinct
       case catalog_status when 'absent' then 'TABLE_ABSENTE_CATALOGUE' else 'TABLE_AMBIGUE_CATALOGUE' end,
       case catalog_status when 'absent' then 'error' else 'warning' end,
       app_id, table_id, via || ' ' || object_ref
from {{ ref('core_table_usage') }} where catalog_status in ('absent', 'ambiguous')
union all
select 'COLONNE_ABSENTE_CATALOGUE', 'error', app_id, table_id || '.' || column_name, entity_id || '#' || coalesce(field, '?')
from {{ ref('core_column_usage') }} where catalog_status = 'absent'
union all
select 'TABLE_SANS_PROPRIETAIRE', 'warning', null, table_id,
       'Utilisée par ' || nb_applications || ' application(s), aucun SSA propriétaire'
from {{ ref('core_table') }} where used_by_java and owner_ssa_id is null
union all
select case when c.mappe or c.ecrit then 'ECRITURE_AUTRE_SSA' else 'LECTURE_AUTRE_SSA' end,
       case when c.mappe or c.ecrit then 'warning' else 'info' end,
       c.applications, c.table_id, c.ssa_id || ' accède aux données de ' || c.owner_ssa_id
from {{ ref('mart_crud_ssa_table') }} c
where c.owner_ssa_id is not null and not c.proprietaire
union all
select case status
         when 'unresolved' then 'APPEL_URL_INCONNUE'
         when 'partial' then 'APPEL_URL_PARTIELLE'
         when 'ambiguous' then 'APPEL_AMBIGU'
         when 'no_endpoint' then 'APPEL_SANS_ENDPOINT'
         when 'version_mismatch' then 'APPEL_VERSION_DIFFERENTE'
         else 'APPEL_CIBLE_NON_EXTRAITE' end,
       case status when 'no_endpoint' then 'error' when 'target_not_extracted' then 'info' else 'warning' end,
       from_app, call_id, coalesce(method, '?') || ' ' || coalesce(path, raw_url, '?')
from {{ ref('core_rest_call') }} where status <> 'resolved'
union all
select case status
         when 'no_consumer' then 'JMS_SANS_CONSOMMATEUR'
         when 'no_producer' then 'JMS_SANS_PRODUCTEUR'
         else 'JMS_DESTINATION_INCONNUE' end,
       'warning', coalesce(producer_app, consumer_app), coalesce(destination, exchange_id), coalesce(producer, consumer)
from {{ ref('core_jms_exchange') }} where status <> 'linked'
