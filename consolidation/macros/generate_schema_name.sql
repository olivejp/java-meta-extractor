{# Les schémas portent le nom de la couche (staging, core, carto), sans le préfixe de la cible. #}
{% macro generate_schema_name(custom_schema_name, node) -%}
  {{ (custom_schema_name or target.schema) | trim }}
{%- endmacro %}
