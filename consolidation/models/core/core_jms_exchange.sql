-- Couples producteur → consommateur sur la même destination, plus les orphelins et les destinations inconnues.
with m as (
  select *, upper(destination) as dest_key from {{ ref('stg_messaging') }}
),
producers as (select * from m where role = 'produce' and dest_key is not null),
consumers as (select * from m where role = 'consume' and dest_key is not null)
select
  coalesce(p.messaging_id, '-') || ' > ' || coalesce(c.messaging_id, '-') as exchange_id,
  coalesce(p.dest_key, c.dest_key)                  as destination,
  coalesce(p.destination_type, c.destination_type)  as destination_type,
  p.app_id                                          as producer_app,
  p.caller                                          as producer,
  c.app_id                                          as consumer_app,
  c.caller                                          as consumer,
  case when p.messaging_id is null then 'no_producer'
       when c.messaging_id is null then 'no_consumer'
       else 'linked' end                            as status
from producers p
full join consumers c
  on c.dest_key = p.dest_key
 and (p.destination_type is null or c.destination_type is null or p.destination_type = c.destination_type)
union all
select
  messaging_id,
  null,
  destination_type,
  case when role = 'produce' then app_id end,
  case when role = 'produce' then caller end,
  case when role = 'consume' then app_id end,
  case when role = 'consume' then caller end,
  'unresolved_destination'
from m
where dest_key is null
