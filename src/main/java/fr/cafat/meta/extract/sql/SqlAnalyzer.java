package fr.cafat.meta.extract.sql;

import fr.cafat.meta.model.SqlTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.ParseException;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.upsert.Upsert;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * Analyse d'un texte SQL : tables lues et écrites, par JSqlParser ; repli tolérant (lecture des
 * mots-clés FROM, JOIN, INTO, UPDATE, TABLE, USING) quand le parseur échoue.
 *
 * <p>Le nommage système DB2 for i ({@code BIBLIO/TABLE}) est accepté et rendu en schéma + nom. Une
 * table écrite n'est pas listée une seconde fois en lecture.
 */
public final class SqlAnalyzer {

  public static final String READ = "read";
  public static final String WRITE = "write";

  /** Résultat : {@code parsed} vrai si JSqlParser a accepté le texte ; {@code error} sinon. */
  public record Analysis(boolean parsed, List<SqlTable> tables, String error) {
  }

  private static final Pattern START = Pattern.compile(
      "^[\\s(]*(SELECT|INSERT|UPDATE|DELETE|MERGE|WITH|TRUNCATE|UPSERT)\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern CLAUSE = Pattern.compile("\\b(FROM|INTO|SET|TABLE|USING)\\b",
      Pattern.CASE_INSENSITIVE);
  private static final String IDENT = "[A-Za-z_$#@][\\w$#@]*";
  private static final Pattern SYSTEM_NAMING = Pattern.compile("(?<![\\w$#@.])(" + IDENT + ")/(" + IDENT + ")");
  private static final Pattern MYBATIS_PARAM = Pattern.compile("[#$]\\{[^}]*}");
  private static final Pattern HIBERNATE_QUALIFIER = Pattern.compile("\\{h-(?:schema|catalog|domain)}");
  private static final Pattern HIBERNATE_ALIAS = Pattern.compile("\\{(" + IDENT + "(?:\\." + IDENT + ")*\\.\\*)}");
  private static final Set<String> TABLE_KEYWORDS = Set.of("FROM", "JOIN", "INTO", "UPDATE", "TABLE", "USING");
  private static final Set<String> NOT_TABLES = Set.of("SELECT", "SET", "OF", "NOWAIT", "SKIP", "WAIT", "LATERAL",
      "ONLY", "WHERE", "VALUES", "AS", "ON", "IF", "EXISTS", "NOT", "DUAL", "LOCKED", "UNNEST", "DEFAULT");
  private static final Set<String> CLAUSE_END = Set.of("WHERE", "GROUP", "ORDER", "HAVING", "UNION", "EXCEPT",
      "INTERSECT", "MINUS", "LIMIT", "OFFSET", "FETCH", "FOR", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS",
      "NATURAL", "ON", "USING", "SET", "VALUES", "WITH", "WINDOW", "RETURNING");

  private SqlAnalyzer() {
  }

  /** Vrai si le texte commence comme une requête SQL et contient une clause de table. */
  public static boolean looksLikeSql(String text) {
    return text != null && START.matcher(text).find() && CLAUSE.matcher(text).find();
  }

  /** Blancs successifs réduits à une espace hors des littéraux entre apostrophes ; texte rogné. */
  public static String normalize(String sql) {
    StringBuilder sb = new StringBuilder(sql.length());
    boolean quoted = false;
    boolean space = false;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (c == '\'') {
        quoted = !quoted;
      }
      if (!quoted && Character.isWhitespace(c)) {
        space = true;
        continue;
      }
      if (space && !sb.isEmpty()) {
        sb.append(' ');
      }
      space = false;
      sb.append(c);
    }
    return sb.toString();
  }

  /** Paramètres MyBatis {@code #{x}} et {@code ${x}} remplacés par {@code ?} pour le parseur. */
  public static String withoutMyBatisParams(String sql) {
    return MYBATIS_PARAM.matcher(sql).replaceAll("?");
  }

  /**
   * Retire les marqueurs que Hibernate remplace dans une requête native : {@code {h-schema}} (schéma
   * par défaut, appliqué ensuite à la table) et {@code {alias.*}}.
   */
  static String withoutHibernatePlaceholders(String sql) {
    String out = HIBERNATE_QUALIFIER.matcher(sql).replaceAll("");
    return HIBERNATE_ALIAS.matcher(out).replaceAll("$1");
  }

  public static Analysis analyze(String sql) {
    String text = systemNaming(withoutHibernatePlaceholders(sql));
    try {
      Statements statements = parse(text);
      Map<String, SqlTable> out = new LinkedHashMap<>();
      for (Statement st : statements) {
        List<String> written = new ArrayList<>();
        for (Table t : writeTargets(st)) {
          if (t != null) {
            written.add(t.getFullyQualifiedName());
          }
        }
        List<String> all;
        try {
          all = new ArrayList<>(new TablesNamesFinder().getTables(st));
        } catch (RuntimeException e) {
          all = new ArrayList<>(written);
          for (SqlTable t : fallback(text)) {
            add(out, t);
          }
        }
        for (String w : written) {
          add(out, table(w, WRITE));
        }
        for (String name : all) {
          if (!written.contains(name)) {
            add(out, table(name, READ));
          }
        }
      }
      return new Analysis(true, sorted(out), null);
    } catch (Exception | StackOverflowError e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return new Analysis(false, sorted(toMap(fallback(text))), firstLine(message));
    }
  }

  /**
   * Parsing dans le thread courant, sans délai : {@code CCJSqlParserUtil.parseStatements} passe par un
   * exécuteur avec un délai de 6 s, dont l'issue dépendrait de la charge de la machine. Essai simple
   * puis complexe, comme JSqlParser.
   */
  private static Statements parse(String text) throws ParseException {
    try {
      return CCJSqlParserUtil.newParser(text).withAllowComplexParsing(false).withComplexParsing(false)
          .Statements();
    } catch (ParseException | RuntimeException simple) {
      return CCJSqlParserUtil.newParser(text).withAllowComplexParsing(true).withComplexParsing(true)
          .Statements();
    }
  }

  private static List<Table> writeTargets(Statement st) {
    List<Table> out = new ArrayList<>();
    if (st instanceof Insert i) {
      out.add(i.getTable());
    } else if (st instanceof Update u) {
      out.add(u.getTable());
    } else if (st instanceof Delete d) {
      if (d.getTables() != null && !d.getTables().isEmpty()) {
        out.addAll(d.getTables());
      } else {
        out.add(d.getTable());
      }
    } else if (st instanceof Merge m) {
      out.add(m.getTable());
    } else if (st instanceof Truncate t) {
      if (t.getTables() != null && !t.getTables().isEmpty()) {
        out.addAll(t.getTables());
      } else {
        out.add(t.getTable());
      }
    } else if (st instanceof Upsert u) {
      out.add(u.getTable());
    }
    return out;
  }

  /** {@code BIBLIO/TABLE} → {@code BIBLIO.TABLE}, hors des littéraux. */
  static String systemNaming(String sql) {
    StringBuilder sb = new StringBuilder();
    int start = 0;
    boolean quoted = false;
    for (int i = 0; i <= sql.length(); i++) {
      if (i == sql.length() || sql.charAt(i) == '\'') {
        String chunk = sql.substring(start, i);
        sb.append(quoted ? chunk : SYSTEM_NAMING.matcher(chunk).replaceAll("$1.$2"));
        if (i < sql.length()) {
          sb.append('\'');
        }
        quoted = !quoted;
        start = i + 1;
      }
    }
    return sb.toString();
  }

  /** Repli sans parseur : noms qui suivent FROM, JOIN, INTO, UPDATE, TABLE, USING (et listes FROM a, b). */
  static List<SqlTable> fallback(String sql) {
    List<String> tokens = tokenize(sql);
    List<SqlTable> out = new ArrayList<>();
    for (int i = 0; i < tokens.size(); i++) {
      String kw = tokens.get(i).toUpperCase(Locale.ROOT);
      if (!TABLE_KEYWORDS.contains(kw)) {
        continue;
      }
      boolean write = kw.equals("INTO") || kw.equals("UPDATE") || kw.equals("TABLE")
          || kw.equals("FROM") && i > 0 && tokens.get(i - 1).equalsIgnoreCase("DELETE");
      int j = i + 1;
      while (true) {
        int[] end = new int[1];
        String name = qualifiedName(tokens, j, end);
        if (name == null) {
          break;
        }
        out.add(table(name, write ? WRITE : READ));
        j = end[0];
        if (!kw.equals("FROM")) {
          break;
        }
        // alias éventuel, puis « , autre_table »
        if (j < tokens.size() && tokens.get(j).equalsIgnoreCase("AS")) {
          j++;
        }
        if (j < tokens.size() && isIdent(tokens.get(j))
            && !CLAUSE_END.contains(tokens.get(j).toUpperCase(Locale.ROOT))) {
          j++;
        }
        if (j < tokens.size() && tokens.get(j).equals(",")) {
          j++;
        } else {
          break;
        }
      }
    }
    return out;
  }

  private static String qualifiedName(List<String> tokens, int from, int[] end) {
    StringBuilder sb = new StringBuilder();
    int j = from;
    while (j < tokens.size() && isIdent(tokens.get(j))) {
      if (sb.isEmpty() && NOT_TABLES.contains(tokens.get(j).toUpperCase(Locale.ROOT))) {
        return null;
      }
      sb.append(tokens.get(j));
      j++;
      if (j + 1 < tokens.size() && (tokens.get(j).equals(".") || tokens.get(j).equals("/"))
          && isIdent(tokens.get(j + 1))) {
        sb.append('.');
        j++;
      } else {
        break;
      }
    }
    if (sb.isEmpty() || j < tokens.size() && tokens.get(j).equals("(")) {
      // fonction table (TABLE(...), UNNEST(...)) : pas une table
      return null;
    }
    end[0] = j;
    return sb.toString();
  }

  private static boolean isIdent(String token) {
    return token.startsWith("\"") || token.startsWith("`") || token.startsWith("[")
        || token.matches(IDENT);
  }

  /** Identifiants (y compris entre guillemets) et ponctuation utile ; littéraux et commentaires ignorés. */
  private static List<String> tokenize(String sql) {
    List<String> out = new ArrayList<>();
    int i = 0;
    int n = sql.length();
    while (i < n) {
      char c = sql.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (c == '\'') {
        int j = sql.indexOf('\'', i + 1);
        i = j < 0 ? n : j + 1;
      } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
        int j = sql.indexOf('\n', i);
        i = j < 0 ? n : j + 1;
      } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
        int j = sql.indexOf("*/", i + 2);
        i = j < 0 ? n : j + 2;
      } else if (c == '"' || c == '`' || c == '[') {
        char close = c == '[' ? ']' : c;
        int j = sql.indexOf(close, i + 1);
        j = j < 0 ? n - 1 : j;
        out.add(sql.substring(i, j + 1));
        i = j + 1;
      } else if (Character.isLetter(c) || c == '_' || c == '$' || c == '#' || c == '@') {
        int j = i + 1;
        while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || "_$#@".indexOf(sql.charAt(j)) >= 0)) {
          j++;
        }
        out.add(sql.substring(i, j));
        i = j;
      } else {
        out.add(String.valueOf(c));
        i++;
      }
    }
    return out;
  }

  /** {@code base.schema.table} (éventuellement entre guillemets) → schéma et nom sans guillemets. */
  static SqlTable table(String qualified, String access) {
    List<String> parts = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    char quote = 0;
    for (char c : qualified.toCharArray()) {
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        } else {
          cur.append(c);
        }
      } else if (c == '"' || c == '`') {
        quote = c;
      } else if (c == '[') {
        quote = ']';
      } else if (c == '.' || c == '/') {
        parts.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    parts.add(cur.toString());
    String name = parts.get(parts.size() - 1);
    String schema = parts.size() > 1 ? parts.get(parts.size() - 2) : null;
    return new SqlTable(schema == null || schema.isEmpty() ? null : schema, name, access);
  }

  private static void add(Map<String, SqlTable> out, SqlTable t) {
    if (t.name().isEmpty()) {
      return;
    }
    String key = (t.schema() == null ? "" : t.schema()) + "\u0000" + t.name();
    SqlTable existing = out.get(key);
    if (existing == null || WRITE.equals(t.access()) && READ.equals(existing.access())) {
      out.put(key, t);
    }
  }

  private static Map<String, SqlTable> toMap(List<SqlTable> tables) {
    Map<String, SqlTable> out = new LinkedHashMap<>();
    for (SqlTable t : tables) {
      add(out, t);
    }
    return out;
  }

  private static List<SqlTable> sorted(Map<String, SqlTable> tables) {
    List<SqlTable> out = new ArrayList<>(tables.values());
    out.sort(Comparator.comparing(SqlTable::sortKey));
    return out;
  }

  private static String firstLine(String message) {
    String line = message.strip();
    int nl = line.indexOf('\n');
    return nl < 0 ? line : line.substring(0, nl).strip();
  }
}
