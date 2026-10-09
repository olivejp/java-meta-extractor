select
  e.app_id,
  x->>'id'            as call_id,
  x->>'client'        as client,
  x->>'method'        as method,
  x->>'raw_url'       as raw_url,
  x->>'resolved_url'  as resolved_url,
  x->>'path'          as path,
  x->>'target_app'    as target_app,
  x->>'caller'        as caller
from {{ ref('stg_extraction') }} e
cross join lateral jsonb_array_elements(e.doc->'calls') x
