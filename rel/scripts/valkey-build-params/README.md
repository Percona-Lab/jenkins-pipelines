# valkey-build-params

Suggests the parameters for a full `hetzner-valkey-ALL-NEXT` build from what
repo.percona.com already publishes, so a rerun does not pick a version-release
that is lower than, or different from, what the repo has.

```sh
python3 rel/scripts/valkey-build-params/valkey_build_params.py valkey-92
python3 rel/scripts/valkey-build-params/valkey_build_params.py valkey-92 --bump --json
```

It reads every APT codename/arch and YUM release/arch of the target repo
(`valkey-92`) and the previous one (`valkey-91`), and for each component picks:

- **version**: the newest upstream version in the target repo, falling back to
  the previous repo for a component the target repo has never had. The bundle
  is the exception: it always takes the server's version, since the bundle is
  versioned with the server.
- **release**: the highest release either repo has for that version, so an
  unchanged component matches the previous repo, and a rebuild never goes
  below what the target repo already has. A version neither repo has starts
  at 1.
- with `--bump`: one release above both repos.

It then checks the picks against the bundle's dependency floors on the
valkey-packaging branch (`bundle/debian/control` and the RPM spec), and exits 1
when a floor is not met or a component has to be set by hand.

The tool only knows what is already published. For the first build of a new
upstream version (e.g. 9.2.1), set `VALKEY_VERSION`, `GIT_BRANCH` and
`VALKEY_BUNDLE_VERSION` to it by hand, with `SERVER_RELEASE=1` and
`BUNDLE_RELEASE=1`.

Standard library only (Python 3.9+). Tests use a fake fetcher, no network:

```sh
python3 -m pytest rel/scripts/valkey-build-params
```
