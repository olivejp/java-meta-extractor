package fr.cafat.meta.scan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Lecture hors ligne de .git : URL du remote origin (sans identifiants) et commit de HEAD. */
public final class GitInfo {

  private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

  private GitInfo() {
  }

  /** Répertoire git effectif (gère le fichier {@code .git} des worktrees et sous-modules). */
  static Path gitDir(Path repo) {
    Path dotGit = repo.resolve(".git");
    try {
      if (Files.isRegularFile(dotGit)) {
        String content = Files.readString(dotGit, StandardCharsets.UTF_8).strip();
        if (content.startsWith("gitdir:")) {
          return repo.resolve(content.substring(7).strip()).normalize();
        }
      }
    } catch (IOException e) {
      return null;
    }
    return Files.isDirectory(dotGit) ? dotGit : null;
  }

  public static String remoteOrigin(Path repo) {
    Path git = gitDir(repo);
    if (git == null) {
      return null;
    }
    Path config = git.resolve("config");
    if (!Files.isRegularFile(config) && Files.isRegularFile(git.resolve("commondir"))) {
      try {
        config = git.resolve(Files.readString(git.resolve("commondir")).strip()).resolve("config");
      } catch (IOException e) {
        return null;
      }
    }
    try {
      List<String> lines = Files.readAllLines(config, StandardCharsets.UTF_8);
      boolean inOrigin = false;
      for (String raw : lines) {
        String line = raw.strip();
        if (line.startsWith("[")) {
          inOrigin = line.replace(" ", "").equalsIgnoreCase("[remote\"origin\"]");
        } else if (inOrigin && line.startsWith("url")) {
          int eq = line.indexOf('=');
          return eq < 0 ? null : cleanUrl(line.substring(eq + 1).strip());
        }
      }
    } catch (IOException e) {
      return null;
    }
    return null;
  }

  /** Supprime identifiants et suffixe .git : {@code https://u:p@h/x.git} → {@code https://h/x}. */
  public static String cleanUrl(String url) {
    if (url == null || url.isBlank()) {
      return null;
    }
    String u = url.strip();
    u = u.replaceFirst("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@]*@", "$1");
    if (!u.contains("://")) {
      u = u.replaceFirst("^[^@/]+@", "");
    }
    if (u.endsWith(".git")) {
      u = u.substring(0, u.length() - 4);
    }
    while (u.endsWith("/")) {
      u = u.substring(0, u.length() - 1);
    }
    return u.isEmpty() ? null : u;
  }

  /** SHA complet de HEAD, ou null. */
  public static String headCommit(Path repo) {
    Path git = gitDir(repo);
    if (git == null) {
      return null;
    }
    try {
      String head = Files.readString(git.resolve("HEAD"), StandardCharsets.UTF_8).strip();
      if (SHA.matcher(head).matches()) {
        return head;
      }
      if (!head.startsWith("ref:")) {
        return null;
      }
      String ref = head.substring(4).strip();
      Path common = git;
      if (Files.isRegularFile(git.resolve("commondir"))) {
        common = git.resolve(Files.readString(git.resolve("commondir")).strip()).normalize();
      }
      for (Path base : List.of(git, common)) {
        Path refFile = base.resolve(ref);
        if (Files.isRegularFile(refFile)) {
          String sha = Files.readString(refFile, StandardCharsets.UTF_8).strip();
          if (SHA.matcher(sha).matches()) {
            return sha;
          }
        }
      }
      for (Path base : List.of(git, common)) {
        Path packed = base.resolve("packed-refs");
        if (Files.isRegularFile(packed)) {
          for (String line : Files.readAllLines(packed, StandardCharsets.UTF_8)) {
            String[] parts = line.strip().split(" ");
            if (parts.length == 2 && parts[1].equals(ref) && SHA.matcher(parts[0]).matches()) {
              return parts[0];
            }
          }
        }
      }
    } catch (IOException e) {
      return null;
    }
    return null;
  }
}
