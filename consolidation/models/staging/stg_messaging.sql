select
  e.app_id,
  x->>'id'                as messaging_id,
  x->>'role'              as role,
  x->>'destination_type'  as destination_type,
  x->>'raw_destination'   as raw_destination,
  x->>'destination'       as destination,
  x->>'caller'            as caller
from {{ ref('stg_extraction') }} e
cross join lateral jsonb_array_elements(e.doc->'messaging') x
