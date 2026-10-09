-- Une source non rattachée reçoit une base propre à l'application : elle ne partage rien.
select
  d.app_id,
  d.datasource,
  d.kind,
  d.jdbc_url,
  d.jndi_name,
  coalesce(b.database, '?' || d.app_id || '/' || d.datasource) as database,
  b.database is not null                                       as is_bound,
  upper(coalesce(b.default_schema, d.default_schema))          as default_schema
from {{ ref('stg_datasource') }} d
left join {{ ref('datasource_binding') }} b
  on b.application = d.app_id and b.datasource = d.datasource
