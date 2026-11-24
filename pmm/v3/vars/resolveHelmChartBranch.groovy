// The percona-helm-charts branch to test PMM <version> with. requested is returned as given unless
// it is auto or empty; then pmm-<version> if it exists, else the newest older pmm-X.Y.Z not yet
// merged into main, else main. Needs a node.
def call(String requested, String version) {
    if (requested && requested != 'auto') {
        return requested
    }
    def branch
    withEnv(["RELEASE_BRANCH=pmm-${version}"]) {
        branch = sh(returnStdout: true, script: '''
            set -eu
            repo=https://github.com/percona/percona-helm-charts.git
            refs=$(git ls-remote --heads "$repo" 'refs/heads/pmm-*')
            branches=$(printf '%s\\n' "$refs" | sed -n 's#^.*refs/heads/\\(pmm-[0-9][0-9]*\\.[0-9][0-9]*\\.[0-9][0-9]*\\)$#\\1#p')
            for b in $branches; do
                if [ "$b" = "$RELEASE_BRANCH" ]; then
                    echo "$b"
                    exit 0
                fi
            done
            # v3/VERSION moves on at the RC cut, but a release branch merges into
            # main only with its release: until then the dev build still needs it.
            older=$(printf '%s\\n' $branches "$RELEASE_BRANCH" | sort -V | awk -v t="$RELEASE_BRANCH" '$0 == t { exit } { print }' | sort -rV)
            if [ -n "$older" ]; then
                tmp=$(mktemp -d)
                trap 'rm -rf "$tmp"' EXIT
                git clone -q --bare --filter=tree:0 "$repo" "$tmp/charts.git"
                for b in $older; do
                    if ! git -C "$tmp/charts.git" merge-base --is-ancestor "$b" main; then
                        echo "$b"
                        exit 0
                    fi
                done
            fi
            echo main
        ''').trim()
    }
    return branch
}
