package fr.cafat.meta.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitInfoTest {

  private static final String SHA_A = "0123456789abcdef0123456789abcdef01234567";
  private static final String SHA_B = "89abcdef0123456789abcdef0123456789abcdef";

  private static void write(Path root, String file, String text) throws IOException {
    Path f = root.resolve(file);
    Files.createDirectories(f.getParent());
    Files.writeString(f, text);
  }

  @Test
  void headSurUneBrancheLueDansSaReference(@TempDir Path repo) throws IOException {
    write(repo, ".git/HEAD", "ref: refs/heads/master\n");
    write(repo, ".git/refs/heads/master", SHA_A + "\n");

    assertThat(GitInfo.headCommit(repo)).isEqualTo(SHA_A);
  }

  @Test
  void headLuDansPackedRefs(@TempDir Path repo) throws IOException {
    write(repo, ".git/HEAD", "ref: refs/heads/master\n");
    write(repo, ".git/packed-refs", "# pack-refs with: peeled fully-peeled sorted\n"
        + SHA_B + " refs/heads/develop\n" + SHA_A + " refs/heads/master\n");

    assertThat(GitInfo.headCommit(repo)).isEqualTo(SHA_A);
  }

  @Test
  void headDetache(@TempDir Path repo) throws IOException {
    write(repo, ".git/HEAD", SHA_B + "\n");

    assertThat(GitInfo.headCommit(repo)).isEqualTo(SHA_B);
  }

  @Test
  void worktreeAvecFichierGitdirEtCommondir(@TempDir Path dir) throws IOException {
    Path main = dir.resolve("principal");
    Path wt = dir.resolve("arbre");
    write(main, ".git/config", "[remote \"origin\"]\n\turl = https://bitbucket/scm/gen/s-gen.git\n");
    write(main, ".git/packed-refs", SHA_A + " refs/heads/feature\n");
    write(main, ".git/worktrees/arbre/HEAD", "ref: refs/heads/feature\n");
    write(main, ".git/worktrees/arbre/commondir", "../..\n");
    write(wt, ".git", "gitdir: ../principal/.git/worktrees/arbre\n");

    assertThat(GitInfo.headCommit(wt)).isEqualTo(SHA_A);
    assertThat(GitInfo.remoteOrigin(wt)).isEqualTo("https://bitbucket/scm/gen/s-gen");
  }

  @Test
  void sansGitNiReferenceValide(@TempDir Path repo) throws IOException {
    assertThat(GitInfo.headCommit(repo)).isNull();
    assertThat(GitInfo.remoteOrigin(repo)).isNull();
    write(repo, ".git/HEAD", "ref: refs/heads/absente\n");
    assertThat(GitInfo.headCommit(repo)).isNull();
  }

  @Test
  void originParmiPlusieursRemotes(@TempDir Path repo) throws IOException {
    write(repo, ".git/config", """
        [core]
        \tbare = false
        [remote "amont"]
        \turl = https://autre/x.git
        [remote "origin"]
        \tfetch = +refs/heads/*:refs/remotes/origin/*
        \turl = https://jdupont:jeton@bitbucket.cafat.nc/scm/gen/s-gen-gpp.git
        [branch "master"]
        \tremote = origin
        """);

    assertThat(GitInfo.remoteOrigin(repo)).isEqualTo("https://bitbucket.cafat.nc/scm/gen/s-gen-gpp");
  }

  @Test
  void nettoyageDesUrl() {
    assertThat(GitInfo.cleanUrl("https://u:p@h/x.git")).isEqualTo("https://h/x");
    assertThat(GitInfo.cleanUrl("ssh://git@merlin:7999/gen/s-gen.git")).isEqualTo("ssh://merlin:7999/gen/s-gen");
    assertThat(GitInfo.cleanUrl("git@github.com:cafat/s-gen.git")).isEqualTo("github.com:cafat/s-gen");
    assertThat(GitInfo.cleanUrl("https://h/x/")).isEqualTo("https://h/x");
    assertThat(GitInfo.cleanUrl("  ")).isNull();
    assertThat(GitInfo.cleanUrl(null)).isNull();
  }
}
