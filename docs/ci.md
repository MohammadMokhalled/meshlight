# Firmware versioning and CI

[Project overview](../README.md) · [Setup and wiring](setup.md) · [Lighting API](lighting.md) · [Testing](testing.md)

The workflows build every product in the inventory, run the firmware and Python
tooling tests, and package traceable firmware. Only a final version tag triggers
GitHub Release publication.

## Versions

The root [VERSION](../VERSION) is the bootstrap version, currently `0.1.0`.
Do not change it for ordinary commits or releases. Version calculation needs
complete Git history and tags. For a shallow clone, run
`git fetch --unshallow --tags`; for a full clone missing tags, run `git fetch --tags`.

| Source | Example | Storage |
| --- | --- | --- |
| `master` | `0.1.0-alpha.4` | Actions artifact, 30 days |
| `release/0.1.0` | `0.1.0-beta.4` | Actions artifact, 30 days |
| `v0.1.0` tag | `0.1.0` | Published GitHub Release assets, no automatic expiry |
| PR to master | `0.1.0-alpha.5.pr.12.g0123456789ab` | Actions artifact, 30 days |
| Other local branch | `0.1.0-dev.4+g0123456789ab` | Local build |

From the repository root, `python scripts/version.py` reports the version,
source revision, and anchor. Use `--format version` for just the version.
The table shows example identities; the exact count comes from your checkout.
CI supplies the exact ref explicitly; a stable tag on a branch's current commit
does not change that branch build.
A detached local checkout of an exact final tag uses the final version.

Master selects the next minor after the highest reachable final release line:
after `v0.1.0` enters its history, new commits build `0.2.0-alpha.n`. Master
uses the first final version in the preceding minor line as its counter anchor,
so maintenance tags such as `v0.1.1` do not reset the `0.2.0` alpha sequence.
Release branches use their exact `x.y.z`, counting from the highest reachable
final version below it. Before any final release, count from repository start.
Tags outside the source commit's ancestry do not affect its version.

The number counts all commits after the anchor, including merge commits.
It resets per release cycle, can skip values when several commits are merged,
and stays the same on a retry. A beta includes the development commits since
the preceding release and therefore need not begin at `beta.1`. PR numbering
uses the base alpha count plus one, with the PR number and head SHA identifying
the candidate merge. Run IDs and retry numbers live in build metadata.
Keep release history intact: do not move final tags or force-push publication branches.

## Products and local builds

The [product inventory](../firmware/products.json) is shared by both pipelines.
Add a unique lowercase `id` and a Zephyr `board` target to add a product to both
pipelines. Add any required board overlay/configuration and pinned west
dependencies in the same change.
No workflow matrix changes are needed.

Follow [setup and wiring](setup.md) for the pinned SDK workspace, existing-SDK
alternative, and build commands. The repository manifest includes the STM32 HAL
omitted by Nordic's manifest. CI uses that manifest and a toolchain container
pinned by digest in [scripts/ci.py](../scripts/ci.py).

CMake derives local versions automatically and regenerates the header when Git
refs or version inputs change. CI overrides `MESHLIGHT_VERSION` and
`MESHLIGHT_GIT_SHA` with its single resolved identity. Each device logs that
identity at startup. Builds produce `zephyr.hex`, `zephyr.bin`, and `zephyr.elf`.
Both explicit CMake overrides are cached. To restore automatic local identity
after using them, pass `-DMESHLIGHT_VERSION= -DMESHLIGHT_GIT_SHA=` after the
`--` in the build command. Local identity reflects Git history; uncommitted
working-tree changes do not add a dirty marker to the version or source SHA.

## Workflow behavior and downloads

The entry points are [ci.yml](../.github/workflows/ci.yml) for pushes and releases,
[pr.yml](../.github/workflows/pr.yml) for PRs, and the reusable
[firmware.yml](../.github/workflows/firmware.yml) for shared validation.

PR validation runs on opening, reopening, pushing commits, retargeting, and
marking ready for review, including draft PR updates. Each job checks out the
event's immutable merge SHA with full history, then checks out the exact base
and merges the exact PR head locally with `--no-ff --no-commit`. All jobs verify
their merged Git tree against the metadata job. PR jobs have read-only repository
permissions, do not retain checkout credentials, and receive no release secrets.
GitHub does not start `pull_request` builds for conflicting PRs; resolve the
conflict before validation can pass. See GitHub's
[pull request event documentation](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#pull_request).

Push CI runs on `master`, `release/**`, and `v*` tags without path filters.
A push containing several commits builds its final head revision. Runs are not
cancelled when a newer commit arrives. Malformed release names and final tags
fail version validation rather than silently publishing a different version.

Both entry workflows call the same build/test workflow. Every product builds
with `west build --no-sysbuild` in the pinned Nordic Linux toolchain container.
Twister executes all discovered test scenarios on `qemu_cortex_m3` (currently
five scenarios and 76 test cases). Report validation rejects missing scenarios,
skips, failures, and missing individual test cases. This verifies emulator
behavior; physical strip validation remains in [testing](testing.md).

Each firmware artifact contains `meshlight-<product>-<version>.zip`, with:

- Versioned HEX, BIN, and ELF files.
- `build-metadata.json`: version, product, source/tree identity, PR parents when
  applicable, SDK/toolchain identity, dependency revisions, and CI run/attempt.
- `SHA256SUMS` for the firmware files and metadata.

Packaging verifies the generated header and linked ELF contain the advertised
version and source SHA. Available logs, Twister JSON, and JUnit XML are also
uploaded, including on failure. To download, open the repository's **Actions**
tab, select a run, and choose its firmware or diagnostic artifact under
**Artifacts**. Final packages are also attached to the corresponding entry on
the repository's **Releases** page.

All Actions artifacts have 30-day retention, including temporary tag-build artifacts.
Final Release assets are independent of that retention setting.
Reruns replace that workflow run's temporary artifacts; each product package
records the attempt that actually built it, including when only failed jobs rerun.

## Publishing a release

The commands below use `0.1.0` as an example. Select the intended version and
commit before running them: pushing a final tag starts publication.

Create a release branch from the intended local master revision and push it:

```sh
git switch -c release/0.1.0 master
git push -u origin release/0.1.0
```

Review its beta artifacts. Fixes pushed to that branch produce new beta builds.
When ready, check out the release branch, confirm its current commit is the one
you validated, and tag it:

```sh
git switch release/0.1.0
git log -1 --oneline
git tag -a v0.1.0 -m "Meshlight 0.1.0"
git push origin v0.1.0
```

The tag chooses the final version and rebuilds every product with that exact
version embedded. Only after all builds and tests pass does publication create
a draft, upload every package, the workflow metadata, and aggregate checksums,
then publish it with generated release notes. Publication has `contents: write`;
other jobs have `contents: read`. It uses the built-in `GITHUB_TOKEN`.

A retry resumes an incomplete draft. A complete published release is checked
and left unchanged; published assets are never overwritten. Tag identity is
verified again against GitHub before publication. This works with immutable
releases because all assets are attached before the draft is published.

Merge the release branch back into master using a merge commit, preserving the
tagged commit in its ancestry. Squash/rebase merging does not preserve that
ancestry. Subsequent master commits then select the next minor version and reset
the counter. For a maintenance release, branch from the previous final tag using
the next patch version, for example:

```sh
git switch -c release/0.1.1 v0.1.0
git push -u origin release/0.1.1
```

Do not tag `v0.1.0` merely to initialize this system: it publishes a final release.

## Required GitHub settings and verification

- Enable Actions and allow the pinned official checkout/upload/download actions.
- Set repository/organization artifact retention to at least 30 days.
- Protect `master`: require the `PR CI` status check and require branches to be
  up to date before merging. Keep force pushes disabled. Resolve merge conflicts
  before merging; they cannot receive a successful PR check.
- Enable release immutability if desired; protect final `v*` tags against changes.
- For private repositories, check plan availability in GitHub's
  [branch protection documentation](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches).

Run [Python tooling tests and workflow checks](testing.md#python-tooling-and-workflow-checks)
before changing versioning, packaging, or publication. Python and Git are
sufficient for the unit tests; workflow linting additionally needs `actionlint`.
Publication tests mock GitHub and never create real tags or releases.
