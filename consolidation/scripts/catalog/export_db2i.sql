-- Export du catalogue DB2 for i, en lecture seule, sans aucune donnée métier.
-- À lancer dans ACS (Exécution de scripts SQL) ou DBeaver, puis exporter en CSV avec en-tête,
-- colonnes dans cet ordre. Adapter la liste des bibliothèques.
-- L = fichier logique, P = fichier physique (DDS), T = table SQL.
SELECT c.TABLE_SCHEMA,
       c.TABLE_NAME,
       c.SYSTEM_TABLE_SCHEMA AS SYSTEM_SCHEMA,
       c.SYSTEM_TABLE_NAME,
       CASE t.TABLE_TYPE
         WHEN 'T' THEN 'TABLE' WHEN 'P' THEN 'TABLE'
         WHEN 'V' THEN 'VIEW'  WHEN 'L' THEN 'LOGICAL'
         WHEN 'A' THEN 'ALIAS' WHEN 'M' THEN 'MQT'
         ELSE t.TABLE_TYPE
       END AS TABLE_TYPE,
       c.COLUMN_NAME,
       c.SYSTEM_COLUMN_NAME,
       c.ORDINAL_POSITION,
       c.DATA_TYPE,
       c.LENGTH,
       c.NUMERIC_SCALE,
       c.IS_NULLABLE
FROM QSYS2.SYSCOLUMNS c
JOIN QSYS2.SYSTABLES t
  ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME
WHERE c.TABLE_SCHEMA IN ('MGENGPP', 'MGENGEO', 'MGENCLI')
ORDER BY 1, 2, 8;
