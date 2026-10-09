select sa_code, max(libelle) as libelle, bool_or(declared) as declared
from (
  select sa_code, libelle, true as declared from {{ ref('urba_sa') }}
  union all
  select sa_code, null, false from {{ ref('urba_ssa') }}
  union all
  select sa_code, null, false from {{ ref('core_application') }} where sa_code is not null
  union all
  select sa_code, null, false from {{ ref('urba_data_owner') }} where sa_code is not null
) s
group by sa_code
