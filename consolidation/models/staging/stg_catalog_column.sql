-- Identifiants en majuscules : PostgreSQL les range en minuscules, DB2 en majuscules.
select
  database,
  upper(table_schema)                             as table_schema,
  upper(table_name)                               as table_name,
  upper(coalesce(system_schema, table_schema))    as system_schema,
  upper(coalesce(system_table_name, table_name))  as system_table_name,
  upper(table_type)                               as table_type,
  upper(column_name)                              as column_name,
  upper(coalesce(system_column_name, column_name)) as system_column_name,
  ordinal_position,
  lower(data_type)                                as data_type,
  length,
  numeric_scale,
  upper(left(is_nullable, 1)) = 'Y'               as nullable
from {{ source('landing', 'catalog_column') }}
