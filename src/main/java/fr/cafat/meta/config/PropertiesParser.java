package fr.cafat.meta.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Lecture d'un fichier .properties en conservant les numéros de ligne. Gère les continuations,
 * les commentaires, les échappements et les documents multiples ({@code #---}).
 */
final class PropertiesParser {

  private PropertiesParser() {
  }

  /** Documents successifs ; chaque document est une liste ordonnée d'entrées. */
  static List<List<ConfigEntry>> parse(String text, String file) {
    List<List<ConfigEntry>> docs = new ArrayList<>();
    List<ConfigEntry> current = new ArrayList<>();
    String[] lines = text.split("\r\n|\r|\n", -1);
    int i = 0;
    while (i < lines.length) {
      String line = lines[i];
      int startLine = i + 1;
      String trimmed = line.stripLeading();
      i++;
      if (trimmed.equals("#---") || trimmed.equals("!---")) {
        docs.add(current);
        current = new ArrayList<>();
        continue;
      }
      if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
        continue;
      }
      StringBuilder logical = new StringBuilder(trimmed);
      while (endsWithContinuation(logical) && i < lines.length) {
        logical.setLength(logical.length() - 1);
        logical.append(lines[i].stripLeading());
        i++;
      }
      String l = logical.toString();
      int sep = -1;
      for (int k = 0; k < l.length(); k++) {
        char c = l.charAt(k);
        if (c == '\\') {
          k++;
          continue;
        }
        if (c == '=' || c == ':' || Character.isWhitespace(c)) {
          sep = k;
          break;
        }
      }
      String key;
      String value;
      if (sep < 0) {
        key = l;
        value = "";
      } else {
        key = l.substring(0, sep);
        int v = sep;
        while (v < l.length() && Character.isWhitespace(l.charAt(v))) {
          v++;
        }
        if (v < l.length() && (l.charAt(v) == '=' || l.charAt(v) == ':')) {
          v++;
        }
        while (v < l.length() && Character.isWhitespace(l.charAt(v))) {
          v++;
        }
        value = l.substring(v);
      }
      current.add(new ConfigEntry(unescape(key), unescape(value), file, startLine));
    }
    docs.add(current);
    return docs;
  }

  private static boolean endsWithContinuation(CharSequence s) {
    int n = 0;
    for (int k = s.length() - 1; k >= 0 && s.charAt(k) == '\\'; k--) {
      n++;
    }
    return n % 2 == 1;
  }

  private static String unescape(String s) {
    StringBuilder sb = new StringBuilder();
    for (int k = 0; k < s.length(); k++) {
      char c = s.charAt(k);
      if (c != '\\' || k + 1 >= s.length()) {
        sb.append(c);
        continue;
      }
      char n = s.charAt(++k);
      switch (n) {
        case 't' -> sb.append('\t');
        case 'n' -> sb.append('\n');
        case 'r' -> sb.append('\r');
        case 'f' -> sb.append('\f');
        case 'u' -> {
          if (k + 4 < s.length()) {
            try {
              sb.append((char) Integer.parseInt(s.substring(k + 1, k + 5), 16));
              k += 4;
            } catch (NumberFormatException e) {
              sb.append('u');
            }
          } else {
            sb.append('u');
          }
        }
        default -> sb.append(n);
      }
    }
    return sb.toString();
  }
}
