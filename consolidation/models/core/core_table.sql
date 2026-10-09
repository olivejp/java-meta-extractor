-- Tables du catalogue et tables citées par le code, avec leur SSA propriétaire.
-- Propriétaire : règle de urba_data_owner, sinon bibliothèque M<SA><SSA>, sinon préfixe <SSA>_.
with referenced as (
  select distinct table_id, database, schema_name, table_name
  from {{ ref('core_table_usage') }}
),
catalog as (
  select database || ':' || table_schema || '.' || table_name as table_id,
         database, table_schema as schema_name, table_name, system_table_name, table_type, column_count
  from {{ ref('stg_catalog_table') }}
),
tables as (
  select
    coalesce(c.table_id, r.table_id)       as table_id,
    coalesce(c.database, r.database)       as database,
    coalesce(c.schema_name, r.schema_name) as schema_name,
    coalesce(c.table_name, r.table_name)   as table_name,
    c.system_table_name,
    c.table_type,
    c.column_count,
    c.table_id is not null                 as in_catalog
  from catalog c
  full join referenced r on r.table_id = c.table_id
),
usage as (
  select table_id, count(distinct app_id) as nb_applications
  from {{ ref('core_table_usage') }}
  group by table_id
)
select
  t.*,
  coalesce(u.nb_applications, 0) as nb_applications,
  u.table_id is not null         as used_by_java,
  o.sa_code                      as owner_sa_code,
  o.ssa_id                       as owner_ssa_id,
  o.source                       as owner_source
from tables t
left join usage u on u.table_id = t.table_id
left join lateral (
  select * from (
    select r.sa_code, r.sa_code || '.' || r.ssa_code as ssa_id, 'referentiel' as source, 1000000 + coalesce(r.priority, 0) as rank
    from {{ ref('urba_data_owner') }} r
    where coalesce(r.database, '') <> 'JMS'
      and (r.database is null or r.database = t.database)
      and coalesce(t.schema_name, '') like upper(coalesce(r.schema_pattern, '%'))
      and t.table_name like upper(coalesce(r.table_pattern, '%'))
    union all
    select s.sa_code, s.ssa_id, 'convention_schema', 2
    from {{ ref('core_ssa') }} s
    where t.schema_name = 'M' || upper(s.sa_code) || upper(s.ssa_code)
    union all
    select s.sa_code, s.ssa_id, 'convention_prefixe', 1
    from {{ ref('core_ssa') }} s
    where t.table_name like upper(s.ssa_code) || '\_%'
  ) candidates
  order by rank desc, ssa_id
  limit 1
) o on true
