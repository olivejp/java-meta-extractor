select
  e.app_id,
  a->>'name'                                              as name,
  a->>'version'                                           as version,
  a->>'context_path'                                      as context_path,
  a->>'repository'                                        as repository,
  -- Projet Bitbucket : le segment qui précède le dépôt dans l'URL.
  substring(a->>'repository' from '/([^/]+)/[^/]+$')      as repository_project,
  a->>'module'                                            as module,
  (select string_agg(t, ',' order by t) from jsonb_array_elements_text(a->'tech') t) as tech,
  (select string_agg(p, ',') from jsonb_array_elements_text(a->'profiles') p)       as profiles,
  e.commit,
  e.loaded_at
from {{ ref('stg_extraction') }} e
cross join lateral (select e.doc->'application' as a) x
