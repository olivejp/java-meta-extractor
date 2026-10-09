-- Une ligne par table citée dans un accès SQL.
select
  e.app_id,
  s->>'id'                 as sql_access_id,
  s->>'origin'             as origin,
  s->>'datasource'         as datasource,
  (s->>'parsed')::boolean  as parsed,
  s->>'caller'             as caller,
  t->>'schema'             as schema_name,
  t->>'name'               as table_name,
  t->>'access'             as access
from {{ ref('stg_extraction') }} e
cross join lateral jsonb_array_elements(e.doc->'sql_accesses') s
cross join lateral jsonb_array_elements(s->'tables') t
