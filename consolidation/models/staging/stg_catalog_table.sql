select
  database,
  table_schema,
  table_name,
  system_schema,
  system_table_name,
  min(table_type)  as table_type,
  count(*)         as column_count
from {{ ref('stg_catalog_column') }}
group by 1, 2, 3, 4, 5
