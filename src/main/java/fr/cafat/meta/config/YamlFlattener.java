package fr.cafat.meta.config;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/** Aplatit un YAML multi-documents en clés pointées, avec la ligne de chaque clé. */
final class YamlFlattener {

  private YamlFlattener() {
  }

  static List<List<ConfigEntry>> parse(String text, String file) {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(true);
    options.setMaxAliasesForCollections(50);
    options.setCodePointLimit(64 * 1024 * 1024);
    Yaml yaml = new Yaml(options);
    List<List<ConfigEntry>> docs = new ArrayList<>();
    for (Node root : yaml.composeAll(new StringReader(text))) {
      List<ConfigEntry> entries = new ArrayList<>();
      flatten("", root, file, entries);
      docs.add(entries);
    }
    return docs;
  }

  private static void flatten(String prefix, Node node, String file, List<ConfigEntry> out) {
    if (node instanceof MappingNode map) {
      for (NodeTuple t : map.getValue()) {
        if (!(t.getKeyNode() instanceof ScalarNode k)) {
          continue;
        }
        String key = k.getValue();
        // clé entre crochets : « [a.b] » conserve les points
        if (key.startsWith("[") && key.endsWith("]")) {
          key = key.substring(1, key.length() - 1);
        }
        String full = prefix.isEmpty() ? key : prefix + "." + key;
        Node v = t.getValueNode();
        if (v instanceof ScalarNode s) {
          out.add(new ConfigEntry(full, s.getValue(), file, k.getStartMark().getLine() + 1));
        } else {
          flatten(full, v, file, out);
        }
      }
    } else if (node instanceof SequenceNode seq) {
      List<Node> items = seq.getValue();
      List<String> scalars = new ArrayList<>();
      for (int i = 0; i < items.size(); i++) {
        Node item = items.get(i);
        if (item instanceof ScalarNode s) {
          out.add(new ConfigEntry(prefix + "[" + i + "]", s.getValue(), file, s.getStartMark().getLine() + 1));
          scalars.add(s.getValue());
        } else {
          flatten(prefix + "[" + i + "]", item, file, out);
        }
      }
      if (!scalars.isEmpty() && scalars.size() == items.size()) {
        // forme liste « a,b » utilisée par Spring pour les listes de scalaires
        out.add(new ConfigEntry(prefix, String.join(",", scalars), file, seq.getStartMark().getLine() + 1));
      }
    } else if (node instanceof ScalarNode s && !prefix.isEmpty()) {
      out.add(new ConfigEntry(prefix, s.getValue(), file, s.getStartMark().getLine() + 1));
    }
  }
}
