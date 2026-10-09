-- Une ligne par usage d'une table par une application : entité, table secondaire, table de jointure, SQL.
-- access : read / write pour le SQL analysé, mapped pour JPA (lecture et écriture possibles).
with raw_usage as (
  select app_id, datasource, schema_name, table_name, 'mapped' as access, 'jpa_entity' as via, entity_id as object_ref
  from {{ ref('stg_entity') }}
  where kind = 'entity' and table_name is not null
  union all
  select app_id, datasource, s->>'schema', s->>'name', 'mapped', 'jpa_secondary_table', entity_id
  from {{ ref('stg_entity') }}
  cross join lateral jsonb_array_elements(secondary_tables) s
  where kind = 'entity'
  union all
  select r.app_id, e.datasource, r.join_table_schema, r.join_table_name, 'mapped', 'jpa_join_table', r.relation_id
  from {{ ref('stg_relation') }} r
  left join {{ ref('stg_entity') }} e on e.entity_id = r.from_entity
  where r.join_table_name is not null
  union all
  select app_id, datasource, schema_name, table_name, access, origin, sql_access_id
  from {{ ref('stg_sql_table') }}
),
located as (
  select
    u.app_id, u.datasource, u.access, u.via, u.object_ref,
    coalesce(d.database, '?' || u.app_id || '/' || coalesce(u.datasource, '?')) as database,
    upper(coalesce(u.schema_name, d.default_schema))                            as schema_name,
    upper(u.table_name)                                                          as table_name
  from raw_usage u
  left join {{ ref('core_datasource') }} d on d.app_id = u.app_id and d.datasource = u.datasource
),
-- Recherche dans le catalogue par nom SQL ou nom système. Sans schéma, un seul candidat dans la base suffit.
matched as (
  select
    l.*,
    k.n,
    k.table_schema as cat_schema,
    k.table_name   as cat_table,
    exists (select 1 from {{ ref('stg_catalog_table') }} c where c.database = l.database) as catalog_loaded
  from located l
  left join lateral (
    select count(*) as n, min(c.table_schema) as table_schema, min(c.table_name) as table_name
    from {{ ref('stg_catalog_table') }} c
    where c.database = l.database
      and (l.schema_name is null or l.schema_name in (c.table_schema, c.system_schema))
      and l.table_name in (c.table_name, c.system_table_name)
  ) k on true
),
resolved as (
  select
    m.*,
    case when n = 1 then cat_schema else schema_name end as final_schema,
    case when n = 1 then cat_table  else table_name  end as final_table,
    case when not catalog_loaded then 'no_catalog'
         when n = 1 then 'present'
         when n = 0 then 'absent'
         else 'ambiguous' end as catalog_status
  from matched m
)
select distinct
  via || ':' || object_ref || ':' || database || ':' || coalesce(schema_name, '?') || '.' || table_name || ':' || access as usage_id,
  app_id,
  datasource,
  database,
  schema_name                                                        as referenced_schema,
  table_name                                                         as referenced_table,
  database || ':' || coalesce(final_schema, '?') || '.' || final_table as table_id,
  final_schema                                                       as schema_name,
  final_table                                                        as table_name,
  access,
  via,
  object_ref,
  catalog_status
from resolved
