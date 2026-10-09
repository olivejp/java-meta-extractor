#!/usr/bin/env bash
# Charge les JSON de java-meta-extractor dans landing.extraction.
# Usage : scripts/load_extractions.sh out/*.json
# Connexion par PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD.
# Le dernier fichier chargé pour une application devient sa version courante.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
[ $# -gt 0 ] || { echo "usage: $0 fichier.json..." >&2; exit 3; }
psql -q -v ON_ERROR_STOP=1 -f "$here/init_landing.sql"
for f in "$@"; do
  F=$(realpath "$f") psql -q -f "$here/load_extraction.sql"
  echo "chargé : $f" >&2
done
