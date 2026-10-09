-- Destinations JMS (un seul broker supposé), avec leur SSA propriétaire :
-- règle de urba_data_owner (database = JMS, table_pattern sur la destination), sinon convention Q<SA><SSA>.
with destinations as (
  select upper(destination) as destination, min(destination_type) as destination_type
  from {{ ref('stg_messaging') }}
  where destination is not null
  group by 1
)
select
  d.destination,
  d.destination_type,
  o.sa_code  as owner_sa_code,
  o.ssa_id   as owner_ssa_id,
  o.source   as owner_source
from destinations d
left join lateral (
  select * from (
    select r.sa_code, r.sa_code || '.' || r.ssa_code as ssa_id, 'referentiel' as source, 1000000 + coalesce(r.priority, 0) as rank
    from {{ ref('urba_data_owner') }} r
    where r.database = 'JMS' and d.destination like upper(coalesce(r.table_pattern, '%'))
    union all
    select s.sa_code, s.ssa_id, 'convention', 1
    from {{ ref('core_ssa') }} s
    where d.destination ~ ('^Q' || upper(s.sa_code || s.ssa_code) || '(\.|$)')
  ) candidates
  order by rank desc, ssa_id
  limit 1
) o on true
