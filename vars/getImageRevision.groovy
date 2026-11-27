// getImageRevision.groovy — Read the source revision from a docker image and check it against GitHub
//
// Runs `<binary> --version` in the image and takes the hash printed as "Revision <hash>" (mysqld)
// or "revision id: <hash>" (xtrabackup). The revision must be a prefix of the head commit of gitRef
// in the first of gitRepos where gitRef exists, so the image is known to contain packages built from
// that branch/tag. Private repositories work through GITHUB_API_TOKEN.
//
// Returns the revision exactly as the binary prints it (its length differs between builds), which is
// what docker image tests compare against.
//
// The agent needs docker and sudo (e.g. 'docker-x64').
//
// Usage in pipelines:
//   def revision = getImageRevision(image: 'perconalab/percona-server:9.7.2-3-amd64',
//                                   binary: 'mysqld',
//                                   gitRef: 'release-9.7.2-3',
//                                   gitRepos: ['https://github.com/percona/percona-server.git',
//                                              'https://github.com/percona/percona-server-private.git'])

def call(Map args = [:]) {
    def image    = args.get('image', '')
    def binary   = args.get('binary', '')
    def gitRef   = args.get('gitRef', '')
    def gitRepos = args.get('gitRepos', [])

    if (!image || !binary || !gitRef || !gitRepos) {
        error "getImageRevision: 'image', 'binary', 'gitRef' and 'gitRepos' are required"
    }

    def revision = ''
    try {
        withEnv(["RI_IMAGE=${image}", "RI_BINARY=${binary}", "RI_REF=${gitRef}", "RI_REPOS=${gitRepos.join(' ')}"]) {
            withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
                sh '''#!/bin/bash
                    set -eo pipefail
                    rm -f .image-revision

                    sudo docker pull -q "${RI_IMAGE}"
                    VERSION_OUTPUT=$(sudo docker run --rm --entrypoint "${RI_BINARY}" "${RI_IMAGE}" --version 2>&1)
                    sudo docker rmi "${RI_IMAGE}" || true
                    echo "${RI_BINARY} --version: ${VERSION_OUTPUT}"

                    REVISION=$(echo "${VERSION_OUTPUT}" | sed -nE 's/.*[Rr]evision( id:)? ([0-9a-f]+).*/\\2/p' | head -n1)
                    if [ -z "${REVISION}" ]; then
                        echo "ERROR: no revision found in ${RI_BINARY} --version output"
                        exit 1
                    fi

                    HEAD_SHA=""
                    for REPO in ${RI_REPOS}; do
                        API_URL="$(echo ${REPO} | sed -re 's|github.com|api.github.com/repos|; s|\\.git$||')/commits/${RI_REF}"
                        if HEAD_SHA=$(curl -fsSL -H "Authorization: token ${TOKEN}" -H "Accept: application/vnd.github.sha" "${API_URL}" 2>/dev/null); then
                            echo "${RI_REF} head in ${REPO}: ${HEAD_SHA}"
                            break
                        fi
                        HEAD_SHA=""
                    done
                    if [ -z "${HEAD_SHA}" ]; then
                        echo "ERROR: ${RI_REF} not found in: ${RI_REPOS}"
                        exit 1
                    fi

                    case "${HEAD_SHA}" in
                        "${REVISION}"*)
                            echo "Image revision ${REVISION} matches ${RI_REF}" ;;
                        *)
                            echo "ERROR: image revision ${REVISION} is not the head of ${RI_REF} (${HEAD_SHA})"
                            exit 1 ;;
                    esac
                    echo "${REVISION}" > .image-revision
                '''
            }
        }
    } finally {
        if (fileExists('.image-revision')) {
            revision = readFile('.image-revision').trim()
            sh 'rm -f .image-revision'
        }
    }
    return revision
}
