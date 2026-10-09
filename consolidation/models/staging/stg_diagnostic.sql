select
  e.app_id,
  x->>'id'                          as diagnostic_id,
  x->>'level'                       as level,
  x->>'code'                        as code,
  x->>'message'                     as message,
  x->'source'->>'class'             as source_class,
  x->'source'->>'file'              as source_file,
  (x->'source'->>'line')::integer   as source_line
from {{ ref('stg_extraction') }} e
cross join lateral jsonb_array_elements(e.doc->'diagnostics') x
