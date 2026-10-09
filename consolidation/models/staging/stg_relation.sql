select
  e.app_id,
  r->>'id'                         as relation_id,
  r->>'from_entity'                as from_entity,
  r->>'to_entity'                  as to_entity,
  r->>'to_class'                   as to_class,
  r->>'field'                      as field,
  r->>'type'                       as relation_type,
  (r->>'owning_side')::boolean     as owning_side,
  r->'join_table'->>'schema'       as join_table_schema,
  r->'join_table'->>'name'         as join_table_name
from {{ ref('stg_extraction') }} e
cross join lateral jsonb_array_elements(e.doc->'relations') r
