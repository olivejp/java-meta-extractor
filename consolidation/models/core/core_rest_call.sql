-- Appel REST rapproché d'un endpoint : même verbe (ou ANY) et même gabarit de chemin, noms de variables ignorés.
-- Le context path versionné fait partie du chemin des deux côtés.
with calls as (
  select c.*, nullif(rtrim(regexp_replace(c.path, '\{[^}]*\}', '{}', 'g'), '/'), '') as path_key
  from {{ ref('stg_call') }} c
),
endpoints as (
  select e.*, nullif(rtrim(regexp_replace(e.path, '\{[^}]*\}', '{}', 'g'), '/'), '') as path_key
  from {{ ref('stg_endpoint') }} e
),
matches as (
  select c.call_id, count(*) as n, min(e.endpoint_id) as endpoint_id, min(e.app_id) as to_app
  from calls c
  join endpoints e
    on e.path_key = c.path_key
   and (c.method is null or e.method in (c.method, 'ANY'))
  group by c.call_id
),
targets as (
  select
    c.call_id,
    -- Application dont le context path préfixe le chemin.
    (select a.app_id from {{ ref('stg_application') }} a
      where a.context_path is not null and starts_with(c.path || '/', a.context_path || '/')
      order by length(a.context_path) desc limit 1) as by_context,
    -- Application de même nom mais d'une autre version (context path /<nom>-<version>).
    (select a.app_id from {{ ref('stg_application') }} a
      where c.path ~ ('^/' || a.name || '([^a-z0-9]|$)')
      order by length(a.name) desc limit 1) as by_name
  from calls c
),
classified as (
  select
    c.*,
    m.n,
    m.endpoint_id as matched_endpoint,
    coalesce(case when m.n = 1 then m.to_app end, t.by_context, t.by_name, c.target_app) as resolved_to_app,
    t.by_context,
    t.by_name
  from calls c
  left join matches m on m.call_id = c.call_id
  left join targets t on t.call_id = c.call_id
)
select
  call_id,
  app_id                                               as from_app,
  client,
  method,
  caller,
  raw_url,
  path,
  case when n = 1 then matched_endpoint end            as endpoint_id,
  resolved_to_app                                      as to_app,
  -- Application visée mais non extraite, déduite du context path /s-<sa>-<ssa>…
  case when resolved_to_app is null then substring(path from '^/(s-[a-z]+-[a-z]+)') end as to_app_hint,
  case
    when path is null then 'unresolved'
    -- Fragment inconnu collé au texte ({x} hors d'un segment entier) ou valeur masquée.
    when path ~ '[^/]\{|\}[^/]|\*\*\*' then 'partial'
    when n = 1 then 'resolved'
    when n > 1 then 'ambiguous'
    when by_context is not null then 'no_endpoint'
    when by_name is not null then 'version_mismatch'
    else 'target_not_extracted'
  end as status
from classified
