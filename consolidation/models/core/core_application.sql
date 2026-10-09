-- Rattachement urba : la ligne de urba_application, sinon la convention s-<sa>-<ssa>.
with conv as (
  select
    a.*,
    substring(lower(a.name) from '^s-([a-z0-9]+)-[a-z0-9]+') as conv_sa,
    substring(lower(a.name) from '^s-[a-z0-9]+-([a-z0-9]+)') as conv_ssa
  from {{ ref('stg_application') }} a
),
classified as (
  select
    c.*,
    coalesce(o.sa_code, c.conv_sa)   as sa_code,
    coalesce(o.ssa_code, c.conv_ssa) as ssa_code,
    case when o.application is not null then 'referentiel'
         when c.conv_ssa is not null then 'convention' end as classification_source
  from conv c
  left join {{ ref('urba_application') }} o on o.application = c.app_id
)
select
  app_id, name, version, context_path, repository, repository_project, module, tech, profiles, commit, loaded_at,
  sa_code,
  ssa_code,
  case when ssa_code is not null then sa_code || '.' || ssa_code end as ssa_id,
  classification_source
from classified
