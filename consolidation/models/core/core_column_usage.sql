-- Colonnes mappées par les entités, confrontées au catalogue quand la table y est trouvée.
select distinct
  c.entity_id || ':' || u.table_id || '.' || upper(c.column_name) || ':' || coalesce(c.field, '?') as column_usage_id,
  u.app_id,
  c.entity_id,
  u.table_id,
  upper(c.column_name) as column_name,
  c.field,
  c.java_type,
  c.column_kind,
  c.pk,
  case when u.catalog_status <> 'present' then u.catalog_status
       when k.column_name is not null then 'present'
       else 'absent' end as catalog_status,
  k.data_type          as catalog_data_type,
  k.nullable           as catalog_nullable
from {{ ref('stg_entity_column') }} c
join {{ ref('core_table_usage') }} u
  on u.object_ref = c.entity_id
 and u.via in ('jpa_entity', 'jpa_secondary_table')
 and u.referenced_table = upper(c.table_name)
left join {{ ref('stg_catalog_column') }} k
  on u.catalog_status = 'present'
 and k.database = u.database
 and k.table_schema = u.schema_name
 and k.table_name = u.table_name
 and upper(c.column_name) in (k.column_name, k.system_column_name)
where c.column_name is not null
