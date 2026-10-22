# SPDX-License-Identifier: MIT

"""Resolve firmware versions from immutable source history, without editing it."""

import argparse
import json
import re
import subprocess
from pathlib import Path

CORE = r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
FINAL = re.compile(r"v" + CORE + r"\Z")
RELEASE = re.compile(r"release/" + CORE + r"\Z")


def git(repo, *args):
    result = subprocess.run(
        ["git", "-C", str(repo), *args], text=True, capture_output=True, check=False
    )
    if result.returncode:
        raise ValueError(f"git {' '.join(args)}: {result.stderr.strip()}")
    return result.stdout.strip()


def core(text):
    match = re.fullmatch(CORE, text)
    if not match:
        raise ValueError(f"Invalid version: {text!r}; expected x.y.z without leading zeros")
    return tuple(map(int, match.groups()))


def format_core(value):
    return ".".join(map(str, value))


def stable_ancestors(repo, commit):
    """Exclude tags on HEAD: tagging must not change a branch build on retry."""
    tags = []
    for tag in git(repo, "tag", "--merged", commit).splitlines():
        match = FINAL.fullmatch(tag)
        if match:
            sha = git(repo, "rev-parse", f"refs/tags/{tag}^{{commit}}")
            if sha != commit:
                tags.append((tuple(map(int, match.groups())), sha))
    return tags


def resolve(repo, ref=None, commit="HEAD", ci=False, pr_number=None, pr_head=None):
    repo = Path(repo).resolve()
    if git(repo, "rev-parse", "--is-shallow-repository") == "true":
        raise ValueError("Full Git history is required; fetch with depth 0 and all tags")
    commit = git(repo, "rev-parse", f"{commit}^{{commit}}")
    seed = core((repo / "VERSION").read_text(encoding="utf-8").strip())
    if ref is None:
        ref = git(repo, "symbolic-ref", "--quiet", "--short", "HEAD") if subprocess.run(
            ["git", "-C", str(repo), "symbolic-ref", "--quiet", "HEAD"],
            capture_output=True, check=False
        ).returncode == 0 else "detached"
        if ref == "detached":
            tags = [t for t in git(repo, "tag", "--points-at", commit).splitlines()
                    if FINAL.fullmatch(t)]
            if len(tags) == 1:
                ref = "refs/tags/" + tags[0]
    tag_context = ref.startswith("refs/tags/")
    name = ref.removeprefix("refs/heads/")
    anchor = None
    if tag_context:
        tag = ref.removeprefix("refs/tags/")
        match = FINAL.fullmatch(tag)
        if not match:
            raise ValueError(f"Invalid final tag: {tag!r}; expected vx.y.z")
        if git(repo, "rev-parse", f"refs/tags/{tag}^{{commit}}") != commit:
            raise ValueError("Final tag does not identify the requested source commit")
        base = tuple(map(int, match.groups()))
        version, channel = format_core(base), "final"
    else:
        tags = stable_ancestors(repo, commit)
        if name == "master":
            channel = "alpha"
            latest = max((v for v, _ in tags), default=None)
            base = max(seed, (latest[0], latest[1] + 1, 0)) if latest else seed
            if latest and (latest[0], latest[1] + 1, 0) >= seed:
                # The first final in this minor line starts the next alpha cycle.
                # Later patch tags in that line cannot decrease alpha.n.
                _, anchor = min((v, sha) for v, sha in tags if v[:2] == latest[:2])
        elif name.startswith("release/"):
            match = RELEASE.fullmatch(name)
            if not match:
                raise ValueError(f"Invalid release branch: {name!r}; expected release/x.y.z")
            channel, base = "beta", tuple(map(int, match.groups()))
            previous = [(v, sha) for v, sha in tags if v < base]
            if previous:
                _, anchor = max(previous)
        else:
            if ci:
                raise ValueError(f"Unsupported publication ref: {ref!r}")
            channel, base = "dev", seed
        revision_range = f"{anchor}..{commit}" if anchor else commit
        number = int(git(repo, "rev-list", "--count", revision_range))
        if pr_number is not None:
            if name != "master" or not pr_head or pr_number < 1:
                raise ValueError("PR versions require master, a PR number, and a head commit")
            pr_head = git(repo, "rev-parse", f"{pr_head}^{{commit}}")
            number += 1  # The local --no-commit merge is one candidate master revision.
        version = f"{format_core(base)}-{channel}.{number}"
        if pr_number is not None:
            version += f".pr.{pr_number}.g{pr_head[:12]}"
        elif channel == "dev":
            version += f"+g{commit[:12]}"
    return {"version": version, "channel": channel, "base_version": format_core(base),
            "commit": commit, "anchor": anchor, "ref": ref}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--ref")
    parser.add_argument("--commit", default="HEAD")
    parser.add_argument("--ci", action="store_true")
    parser.add_argument("--pr-number", type=int)
    parser.add_argument("--pr-head")
    parser.add_argument("--format", choices=["json", "version"], default="json")
    args = parser.parse_args()
    try:
        result = resolve(args.repo, args.ref, args.commit, args.ci, args.pr_number, args.pr_head)
    except (ValueError, OSError) as error:
        parser.exit(1, f"Version error: {error}\n")
    print(result["version"] if args.format == "version" else json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
