-- Version courante de chaque application : le dernier JSON chargé.
select distinct on (app_id)
  app_id,
  commit,
  sha256,
  loaded_at,
  doc
from {{ source('landing', 'extraction') }}
where doc->>'contract_version' = '1.0'
order by app_id, loaded_at desc
