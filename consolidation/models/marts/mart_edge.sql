-- Arêtes du graphe : appartenance, appels REST, usages de tables, échanges JMS.
-- weight compte les liens élémentaires agrégés ; detail résume leurs statuts ou accès.
select 'ssa:' || ssa_id as from_id, 'sa:' || sa_code as to_id, 'appartient_a' as edge_type, 1 as weight, null::text as detail
from {{ ref('core_ssa') }}
union all
select 'app:' || app_id, 'ssa:' || ssa_id, 'appartient_a', 1, classification_source
from {{ ref('core_application') }} where ssa_id is not null
union all
select 'table:' || table_id, 'ssa:' || owner_ssa_id, 'appartient_a', 1, owner_source
from {{ ref('core_table') }} where owner_ssa_id is not null
union all
select 'file:' || destination, 'ssa:' || owner_ssa_id, 'appartient_a', 1, owner_source
from {{ ref('core_queue') }} where owner_ssa_id is not null
union all
select 'app:' || from_app, coalesce('app:' || to_app, 'ext:' || to_app_hint), 'appelle_rest',
       count(*), string_agg(distinct status, ',' order by status)
from {{ ref('core_rest_call') }}
where coalesce(to_app, to_app_hint) is not null
group by 1, 2
union all
select 'app:' || app_id, 'table:' || table_id, 'utilise_table',
       count(*), string_agg(distinct access, ',' order by access)
from {{ ref('core_table_usage') }}
group by 1, 2
union all
select 'app:' || app_id, 'file:' || upper(destination), 'produit', count(*), null
from {{ ref('stg_messaging') }} where role = 'produce' and destination is not null
group by 1, 2
union all
select 'file:' || upper(destination), 'app:' || app_id, 'consomme', count(*), null
from {{ ref('stg_messaging') }} where role = 'consume' and destination is not null
group by 1, 2
