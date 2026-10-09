select
  e.app_id,
  d->>'id'             as datasource,
  d->>'kind'           as kind,
  d->>'jdbc_url'       as jdbc_url,
  d->>'jndi_name'      as jndi_name,
  d->>'default_schema' as default_schema,
  d->>'config_prefix'  as config_prefix
from {{ ref('stg_extraction') }} e
cross join lateral jsonb_array_elements(e.doc->'application'->'datasources') d
