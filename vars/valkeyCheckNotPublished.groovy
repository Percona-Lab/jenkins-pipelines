// valkeyCheckNotPublished — stop a Valkey package job before it builds when the
// requested <version>-<release> is already published.
//
// Re-uploading the same version-release replaces the file under repo metadata
// that clients (and other CI jobs) have already cached, and dnf/apt then refuse
// it: "checksum doesn't match" or "Server reports Content-Length ... but expected
// size is ...". sync2ProdAutoBuild rsyncs the RPMs before createrepo rewrites the
// metadata, so even fresh clients hit this for a few minutes after each push.
// Every rebuild therefore needs a new release number.
//
// Checks the target channel and main (where testing is promoted to): the apt
// noble amd64 index and the EL9 x86_64 yum directory. An index that returns 404
// (a new repo or channel) counts as empty; any other fetch error fails the job,
// so a network problem can't let the check pass.
//
// allowRepublish=true marks the build UNSTABLE instead of failing it.
def call(Map args) {
    String repo    = args.repo
    String channel = args.channel
    String debName = args.deb
    String rpmName = args.rpm
    String verRel  = args.version
    boolean allowRepublish = args.allowRepublish ? true : false

    List channels = channel == 'main' ? ['main'] : [channel, 'main']
    List clashes = []
    for (String ch : channels) {
        String base = "https://repo.percona.com/${repo}"
        String apt = fetchIndex("${base}/apt/dists/noble/${ch}/binary-amd64/Packages")
        String yum = fetchIndex("${base}/yum/${ch}/9/RPMS/x86_64/")
        if (apt.contains("/${debName}_${verRel}.noble_") || yum.contains("${rpmName}-${verRel}.el9.x86_64.rpm")) {
            clashes << ch
        }
    }
    if (clashes) {
        String msg = "${debName} ${verRel} is already published in ${repo} (${clashes.join(', ')}). " +
                     "Raise the release number, or set ALLOW_REPUBLISH to override."
        if (allowRepublish) {
            unstable(msg)
        } else {
            error(msg)
        }
    } else {
        echo "Pre-flight OK: ${debName} ${verRel} is not published in ${repo} (${channels.join(', ')})"
    }
}

def fetchIndex(String url) {
    return sh(returnStdout: true, script: """
        set +x
        code=\$(curl -sS --max-time 60 -o .valkey-index.tmp -w '%{http_code}' '${url}') || code=000
        case "\$code" in
            200) cat .valkey-index.tmp ;;
            404) ;;
            *)   echo "HTTP \$code for ${url}" >&2; rm -f .valkey-index.tmp; exit 1 ;;
        esac
        rm -f .valkey-index.tmp
    """)
}
