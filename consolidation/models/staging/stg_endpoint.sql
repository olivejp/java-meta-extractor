select
  e.app_id,
  x->>'id'              as endpoint_id,
  x->>'framework'       as framework,
  x->>'method'          as method,
  x->>'path'            as path,
  x->>'handler'         as handler,
  x->>'request_type'    as request_type,
  x->>'response_type'   as response_type
from {{ ref('stg_extraction') }} e
cross join lateral jsonb_array_elements(e.doc->'endpoints') x
