-- Flux entre SSA, du demandeur vers le fournisseur : REST (appelant → appelé), JMS (producteur → consommateur),
-- données (application utilisatrice → SSA propriétaire de la table, par type d'accès).
with app as (
  select app_id, coalesce(ssa_id, '?') as ssa_id from {{ ref('core_application') }}
),
links as (
  select a1.ssa_id as from_ssa, a2.ssa_id as to_ssa, 'rest' as flux, r.from_app || ' → ' || r.to_app as lien
  from {{ ref('core_rest_call') }} r
  join app a1 on a1.app_id = r.from_app
  join app a2 on a2.app_id = r.to_app
  union all
  select a1.ssa_id, a2.ssa_id, 'jms', j.producer_app || ' → ' || j.consumer_app
  from {{ ref('core_jms_exchange') }} j
  join app a1 on a1.app_id = j.producer_app
  join app a2 on a2.app_id = j.consumer_app
  where j.status = 'linked'
  union all
  select a.ssa_id, t.owner_ssa_id, 'donnees_' || u.access, u.app_id || ' → ' || t.table_id
  from {{ ref('core_table_usage') }} u
  join app a on a.app_id = u.app_id
  join {{ ref('core_table') }} t on t.table_id = u.table_id
  where t.owner_ssa_id is not null
)
select
  from_ssa,
  to_ssa,
  flux,
  from_ssa <> to_ssa                          as inter_ssa,
  count(*)                                    as nb_liens,
  string_agg(distinct lien, '; ' order by lien) as liens
from links
group by from_ssa, to_ssa, flux
