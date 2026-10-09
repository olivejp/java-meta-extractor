-- Appelé par load_extractions.sh, le fichier dans la variable d'environnement F.
\set ON_ERROR_STOP on
\set doc `cat "$F"`
insert into landing.extraction (app_id, commit, sha256, doc)
select j->'application'->>'id', j->'application'->>'commit',
       encode(sha256(convert_to(:'doc', 'UTF8')), 'hex'), j
from (select :'doc'::jsonb as j) x
on conflict (app_id, sha256) do update set loaded_at = now();
