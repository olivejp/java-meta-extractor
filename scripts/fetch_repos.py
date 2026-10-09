#!/usr/bin/env python3
"""Clone ou met à jour les dépôts Java d'un projet Bitbucket Server, sur une branche donnée.

Étape 1 du pipeline : le répertoire produit se passe tel quel à
``java-meta-extractor --repos-dir``. Il ne contient que les dépôts Java (pom.xml ou build
Gradle à la racine) : les autres sont supprimés après examen et notés dans le manifeste.

Liste des dépôts :
  - par l'API REST (BITBUCKET_URL, BITBUCKET_TOKEN) ;
  - ou par un fichier de noms de dépôts (--repos-file), sans API.

Le répertoire cible appartient au script : modifications locales et fichiers non suivis y sont
écrasés à chaque passage. Il doit être vide, absent ou déjà marqué par le script.

Sortie : <dest>/manifest.tsv, trié par dépôt, sans date. Code 0 si tout a réussi, 1 si au moins
un dépôt est en erreur, 3 pour une erreur d'usage.
"""

import argparse
import concurrent.futures
import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

MARKER = ".fetch-repos"
MANIFEST = "manifest.tsv"
BUILD_FILES = ("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
COLUMNS = ("repo", "status", "action", "commit", "clone_url", "note")


class UsageError(Exception):
    pass


def list_repos_api(base_url, token, project, protocol):
    """Dépôts du projet par l'API REST paginée, avec leur URL de clonage."""
    repos, start = [], 0
    while True:
        query = urllib.parse.urlencode({"start": start, "limit": 100})
        url = f"{base_url.rstrip('/')}/rest/api/1.0/projects/{urllib.parse.quote(project)}/repos?{query}"
        request = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                page = json.load(response)
        except urllib.error.HTTPError as e:
            raise UsageError(f"API Bitbucket : HTTP {e.code} sur {url}") from e
        except urllib.error.URLError as e:
            raise UsageError(f"API Bitbucket injoignable : {e.reason}") from e
        for r in page.get("values", []):
            links = {link.get("name"): link.get("href") for link in r.get("links", {}).get("clone", [])}
            repos.append({"repo": r["slug"], "clone_url": links.get(protocol), "archived": bool(r.get("archived"))})
        if page.get("isLastPage", True):
            return repos
        start = page["nextPageStart"]


def list_repos_file(path, ssh_base, project):
    repos = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        slug = line.split("#", 1)[0].strip()
        if slug:
            repos.append({"repo": slug, "clone_url": f"{ssh_base.rstrip('/')}/{project}/{slug}.git", "archived": False})
    return repos


def git(*args, cwd=None):
    """Lance git sans invite interactive ; renvoie (code, sortie, erreur)."""
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
    env.setdefault("GIT_SSH_COMMAND", "ssh -o BatchMode=yes")
    p = subprocess.run(["git", *args], cwd=cwd, env=env, capture_output=True, text=True)
    return p.returncode, p.stdout.strip(), p.stderr.strip()


def first_line(text):
    """Ligne utile d'une erreur git : le « fatal: », sinon la première."""
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    fatal = [line for line in lines if line.startswith(("fatal:", "error:"))]
    return (fatal or lines or [""])[0]


def sync(entry, dest, branch, previous):
    """Met un dépôt à jour et renvoie sa ligne de manifeste."""
    slug, url = entry["repo"], entry["clone_url"]
    row = {"repo": slug, "status": "", "action": "", "commit": "", "clone_url": url or "", "note": ""}
    target = dest / slug
    if entry["archived"]:
        return remove(target, row, "archived", "dépôt archivé dans Bitbucket")
    if not url:
        return dict(row, status="error", action="failed", note="aucune URL de clonage pour ce protocole")

    code, out, err = git("ls-remote", url, f"refs/heads/{branch}")
    if code != 0:
        return fail(target, row, err)
    if not out:
        return remove(target, row, "no_branch", f"branche {branch} absente")
    remote_sha = out.split()[0]
    row["commit"] = remote_sha

    # Dépôt non Java déjà examiné à ce commit : inutile de le recloner.
    before = previous.get(slug)
    if before and before["status"] == "non_java" and before["commit"] == remote_sha and not target.exists():
        return dict(row, status="non_java", action="unchanged", note=before["note"])

    if (target / ".git").exists():
        code, head, _ = git("rev-parse", "HEAD", cwd=target)
        if code == 0 and head == remote_sha:
            row["action"] = "unchanged"
        else:
            steps = (
                ("remote", "set-url", "origin", url),
                ("fetch", "--quiet", "--depth", "1", "origin", f"+refs/heads/{branch}:refs/remotes/origin/{branch}"),
                ("checkout", "--quiet", "--force", "-B", branch, f"refs/remotes/origin/{branch}"),
            )
            for step in steps:
                code, _, err = git(*step, cwd=target)
                if code != 0:
                    return fail(target, row, err)
            row["action"] = "updated"
        # Le clone reflète exactement la branche distante : modifications locales et fichiers non suivis effacés.
        git("reset", "--quiet", "--hard", "HEAD", cwd=target)
        git("clean", "-ffdxq", cwd=target)
    else:
        if target.exists():
            shutil.rmtree(target)
        code, _, err = git("clone", "--quiet", "--depth", "1", "--single-branch", "--branch", branch, url, str(target))
        if code != 0:
            shutil.rmtree(target, ignore_errors=True)
            return dict(row, status="error", action="failed", note=first_line(err))
        row["action"] = "cloned"

    code, head, _ = git("rev-parse", "HEAD", cwd=target)
    row["commit"] = head if code == 0 else remote_sha

    if any((target / f).is_file() for f in BUILD_FILES):
        return dict(row, status="java")
    _, files, _ = git("ls-files", *(f"*{f}" for f in BUILD_FILES), cwd=target)
    nested = len(files.splitlines()) if files else 0
    note = f"aucun build à la racine, {nested} fichier(s) de build plus bas" if nested else "aucun build Maven ou Gradle"
    shutil.rmtree(target)
    return dict(row, status="non_java", note=note)


def fail(target, row, err):
    """Erreur sur un dépôt : un clone précédent reste en place et sera extrait tel quel."""
    note = first_line(err)
    code, head, _ = git("rev-parse", "HEAD", cwd=target) if (target / ".git").exists() else (1, "", "")
    if code == 0:
        note += f" ; clone précédent conservé au commit {head[:12]}"
    return dict(row, status="error", action="failed", commit=head if code == 0 else "", note=note)


def remove(target, row, status, note):
    action = "skipped"
    if target.exists():
        shutil.rmtree(target)
        action = "deleted"
    return dict(row, status=status, action=action, note=note)


def prepare_dest(dest):
    if dest.exists():
        if not dest.is_dir():
            raise UsageError(f"{dest} n'est pas un répertoire")
        if not (dest / MARKER).exists() and any(dest.iterdir()):
            raise UsageError(f"{dest} n'est ni vide ni géré par ce script ({MARKER} absent) : refus d'y écrire")
    dest.mkdir(parents=True, exist_ok=True)
    (dest / MARKER).write_text("Répertoire géré par scripts/fetch_repos.py : son contenu est écrasé.\n", encoding="utf-8")


def read_manifest(dest):
    path = dest / MANIFEST
    if not path.exists():
        return {}
    lines = path.read_text(encoding="utf-8").splitlines()
    rows = [dict(zip(COLUMNS, line.split("\t"))) for line in lines[1:] if line]
    return {r["repo"]: r for r in rows}


def write_manifest(dest, rows):
    lines = ["\t".join(COLUMNS)]
    for r in sorted(rows, key=lambda r: r["repo"]):
        lines.append("\t".join(r[c].replace("\t", " ") for c in COLUMNS))
    (dest / MANIFEST).write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("dest", type=Path, help="répertoire des clones, à passer à --repos-dir")
    parser.add_argument("--project", default="gen", help="clé du projet Bitbucket (défaut : gen)")
    parser.add_argument("--branch", default="master", help="branche à extraire (défaut : master)")
    parser.add_argument("--protocol", default="ssh", choices=("ssh", "http"),
                        help="lien de clonage fourni par l'API (défaut : ssh)")
    parser.add_argument("--repos-file", type=Path,
                        help="liste de dépôts, un par ligne, au lieu de l'API (# pour commenter)")
    parser.add_argument("--ssh-base", default="ssh://git@merlin-ref4.intra.cafat.nc:7999",
                        help="base des URL SSH avec --repos-file")
    parser.add_argument("--jobs", type=int, default=4, help="dépôts traités en parallèle (défaut : 4)")
    args = parser.parse_args(argv)

    try:
        if args.repos_file:
            repos = list_repos_file(args.repos_file, args.ssh_base, args.project)
        else:
            base_url, token = os.environ.get("BITBUCKET_URL"), os.environ.get("BITBUCKET_TOKEN")
            if not base_url or not token:
                raise UsageError("BITBUCKET_URL et BITBUCKET_TOKEN sont requis sans --repos-file")
            repos = list_repos_api(base_url, token, args.project, args.protocol)
        if not repos:
            raise UsageError(f"aucun dépôt dans le projet {args.project}")
        dest = args.dest.resolve()
        prepare_dest(dest)
    except (UsageError, OSError) as e:
        print(f"erreur : {e}", file=sys.stderr)
        return 3

    previous = read_manifest(dest)
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.jobs)) as pool:
        rows = list(pool.map(lambda r: sync(r, dest, args.branch, previous), repos))

    # Dépôt cloné lors d'un passage précédent mais disparu du projet.
    known = {r["repo"] for r in repos}
    for child in sorted(dest.iterdir()):
        if child.is_dir() and child.name not in known and (child / ".git").exists():
            shutil.rmtree(child)
            rows.append({"repo": child.name, "status": "removed", "action": "deleted", "commit": "",
                         "clone_url": "", "note": f"absent du projet {args.project}"})

    write_manifest(dest, rows)
    counts = {}
    for r in rows:
        counts[r["status"]] = counts.get(r["status"], 0) + 1
    print(" ".join(f"{k}={v}" for k, v in sorted(counts.items())), file=sys.stderr)
    for r in sorted(rows, key=lambda r: r["repo"]):
        if r["status"] == "error":
            print(f"erreur : {r['repo']} : {r['note']}", file=sys.stderr)
    return 1 if counts.get("error") else 0


if __name__ == "__main__":
    sys.exit(main())
