-- Zone d'atterrissage : les fichiers tels qu'ils arrivent, sans transformation.
set client_min_messages = warning;
create schema if not exists landing;

-- Une ligne par JSON de java-meta-extractor. Recharger le même contenu ne crée pas de doublon.
create table if not exists landing.extraction (
  app_id     text        not null,
  commit     text,
  sha256     text        not null,
  loaded_at  timestamptz not null default now(),
  doc        jsonb       not null,
  primary key (app_id, sha256)
);

-- Catalogue d'une base physique, au format commun des requêtes de scripts/catalog/.
create table if not exists landing.catalog_column (
  database           text        not null,
  table_schema       text        not null,
  table_name         text        not null,
  system_schema      text,
  system_table_name  text,
  table_type         text,
  column_name        text        not null,
  system_column_name text,
  ordinal_position   integer,
  data_type          text,
  length             integer,
  numeric_scale      integer,
  is_nullable        text,
  loaded_at          timestamptz not null default now()
);
