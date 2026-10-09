select
  e.app_id,
  x->>'id'                          as entity_id,
  x->>'class'                       as class_name,
  x->>'kind'                        as kind,
  x->>'datasource'                  as datasource,
  x->>'schema'                      as schema_name,
  x->>'table'                       as table_name,
  (x->>'is_view')::boolean          as is_view,
  x->>'parent'                      as parent,
  x->'inheritance'->>'strategy'     as inheritance_strategy,
  x->'secondary_tables'             as secondary_tables,
  x->'source'->>'file'              as source_file,
  (x->'source'->>'line')::integer   as source_line
from {{ ref('stg_extraction') }} e
cross join lateral jsonb_array_elements(e.doc->'entities') x
