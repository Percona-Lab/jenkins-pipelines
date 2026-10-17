// Download files from a private GitHub raw URL.
// The caller must be inside withCredentials that sets GITHUB_API_TOKEN.
// Jenkins runs sh with tracing on. set +x keeps the token header out of the log.
// curl -s prints only "curl: (22) ... 404" on a failed GET. echo names the file.

def fetch(String baseUrl, List paths) {
    def base = baseUrl.replaceAll(/\/$/, '')
    def lines = [
        'set +x',
        'set -o errexit',
        'set -o nounset',
        "base='${base}'",
        '''fetch() {
    echo "$2"
    rm -f "$1"
    mkdir -p "$(dirname "$1")"
    curl -fsSL --retry 3 --retry-all-errors --max-time 60 \\
        -H "Authorization: token ${GITHUB_API_TOKEN}" -o "$1" "$base/$2"
}''',
    ]
    paths.each { path ->
        lines << "fetch '${path}' '${path}'"
    }
    sh(lines.join('\n') + '\n')
}

return this
