select
  sa_code || '.' || ssa_code as ssa_id,
  sa_code,
  ssa_code,
  max(libelle)      as libelle,
  bool_or(declared) as declared
from (
  select sa_code, ssa_code, libelle, true as declared from {{ ref('urba_ssa') }}
  union all
  select sa_code, ssa_code, null, false from {{ ref('core_application') }} where ssa_code is not null
  union all
  select sa_code, ssa_code, null, false from {{ ref('urba_data_owner') }} where ssa_code is not null
) s
group by sa_code, ssa_code
