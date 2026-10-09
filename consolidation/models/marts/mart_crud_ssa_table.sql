-- Matrice SSA × table : qui lit, écrit ou mappe quoi, et si le SSA en est propriétaire.
select
  coalesce(a.ssa_id, '?') || '|' || u.table_id                as crud_id,
  coalesce(a.ssa_id, '?')                                     as ssa_id,
  u.table_id,
  t.owner_ssa_id,
  a.ssa_id is not distinct from t.owner_ssa_id                as proprietaire,
  bool_or(u.access = 'read')                                  as lit,
  bool_or(u.access = 'write')                                 as ecrit,
  bool_or(u.access = 'mapped')                                as mappe,
  count(distinct u.app_id)                                    as nb_applications,
  string_agg(distinct u.app_id, ',' order by u.app_id)        as applications
from {{ ref('core_table_usage') }} u
join {{ ref('core_application') }} a on a.app_id = u.app_id
join {{ ref('core_table') }} t on t.table_id = u.table_id
group by 1, 2, 3, 4, 5
