-- Colonnes des entités. La table d'une colonne est celle de l'entité ou l'une de ses tables secondaires.
select
  e.app_id,
  x->>'id'                                   as entity_id,
  c->>'name'                                 as column_name,
  c->>'field'                                as field,
  c->>'java_type'                            as java_type,
  coalesce(c->>'table', x->>'table')         as table_name,
  case when st.value is not null then st.value->>'schema' else x->>'schema' end as schema_name,
  c->>'kind'                                 as column_kind,
  (c->>'pk')::boolean                        as pk,
  (c->>'nullable')::boolean                  as nullable,
  (c->>'length')::integer                    as length,
  c->>'references'                           as references_column,
  c->>'inherited_from'                       as inherited_from
from {{ ref('stg_extraction') }} e
cross join lateral jsonb_array_elements(e.doc->'entities') x
cross join lateral jsonb_array_elements(x->'columns') c
left join lateral (
  select s as value from jsonb_array_elements(x->'secondary_tables') s
  where s->>'name' = c->>'table'
  limit 1
) st on true
where x->>'kind' = 'entity'
