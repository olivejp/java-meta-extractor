#!/usr/bin/env bash
# Remplace le catalogue d'une base physique dans landing.catalog_column.
# Usage : scripts/load_catalog.sh <base> <export.csv>
#   <base> : identifiant de la base physique, le même que dans seeds/datasource_binding.csv.
#   <export.csv> : sortie d'une requête de scripts/catalog/, avec en-tête.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
[ $# -eq 2 ] || { echo "usage: $0 <base> <export.csv>" >&2; exit 3; }
psql -q -v ON_ERROR_STOP=1 -f "$here/init_landing.sql"
psql -q -v db="$1" -f "$here/load_catalog.sql" < "$2"
echo "catalogue chargé : $1" >&2
