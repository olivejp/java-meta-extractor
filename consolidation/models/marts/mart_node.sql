-- Nœuds du graphe de cartographie. parent_id donne l'emboîtement SA > SSA > application, table ou file.
select 'sa:' || sa_code as node_id, 'sa' as node_type, coalesce(libelle, sa_code) as label,
       null::text as parent_id, sa_code, null::text as ssa_id,
       jsonb_build_object('declare', declared) as attributes
from {{ ref('core_sa') }}
union all
select 'ssa:' || ssa_id, 'ssa', coalesce(libelle, ssa_code), 'sa:' || sa_code, sa_code, ssa_id,
       jsonb_build_object('declare', declared)
from {{ ref('core_ssa') }}
union all
select 'app:' || app_id, 'application', name, 'ssa:' || ssa_id, sa_code, ssa_id,
       jsonb_build_object('version', version, 'commit', commit, 'context_path', context_path,
                          'tech', tech, 'classification', classification_source)
from {{ ref('core_application') }}
union all
select distinct 'ext:' || to_app_hint, 'application_externe', to_app_hint, null, null, null,
       jsonb_build_object('ssa_presume', regexp_replace(to_app_hint, '^s-([a-z]+)-([a-z]+)$', '\1.\2'))
from {{ ref('core_rest_call') }}
where to_app is null and to_app_hint is not null
union all
select 'table:' || table_id, 'table', coalesce(schema_name || '.', '') || table_name, 'ssa:' || owner_ssa_id,
       owner_sa_code, owner_ssa_id,
       jsonb_build_object('database', database, 'type', table_type, 'catalogue', in_catalog,
                          'utilisee_java', used_by_java, 'nb_applications', nb_applications, 'proprietaire', owner_source)
from {{ ref('core_table') }}
union all
select 'file:' || destination, 'file_jms', destination, 'ssa:' || owner_ssa_id, owner_sa_code, owner_ssa_id,
       jsonb_build_object('type', destination_type, 'proprietaire', owner_source)
from {{ ref('core_queue') }}
