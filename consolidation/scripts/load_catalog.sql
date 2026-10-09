-- Appelé par load_catalog.sh : variable db, CSV sur l'entrée standard.
\set ON_ERROR_STOP on
begin;
create temp table cat (
  table_schema text, table_name text, system_schema text, system_table_name text, table_type text,
  column_name text, system_column_name text, ordinal_position integer,
  data_type text, length integer, numeric_scale integer, is_nullable text
) on commit drop;
\copy cat from pstdin with (format csv, header true)
delete from landing.catalog_column where database = :'db';
insert into landing.catalog_column (database, table_schema, table_name, system_schema, system_table_name, table_type,
                                    column_name, system_column_name, ordinal_position, data_type, length, numeric_scale, is_nullable)
select :'db', table_schema, table_name, system_schema, system_table_name, table_type,
       column_name, system_column_name, ordinal_position, data_type, length, numeric_scale, is_nullable
from cat;
commit;
