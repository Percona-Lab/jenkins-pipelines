/**
 * The pipeline defined in this file:
 * - builds PSMDB Docker images in parallel for both `amd64` and `arm64` CPU
 *   architectures;
 * - generates architecture-specific CycloneDX 1.6 SBOMs from the locally-built
 *   images;
 * - pushes the images to Docker registry;
 * - assembles a multi-arch manifest;
 * - attaches the SBOMs as OCI 1.1 referrer artifacts.
 *
 */
library changelog: false, identifier: "lib@hetzner", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// Note. This file uses:
// - the JavaDoc-style comments (slash-asterisk & asterisk-slash, like the one
//   on the top of the file) for "public documentation", e.g. function APIs;
// - the leading-double-slash-style comments (like this one) for implementation
//   details.
//
// Please stick the described approach and **DO NOT MIX TWO COMMENT STYLES**.

// Note. For the sake of readability, please keep line length under 100 columns
// for source code and 80 columns for comments.

/**
 * @defgroup Helper Functions That Install Command-Line Tools
 * @{
 */
def installSyft() {
    sh '''
        abort() {
            printf "Error: %s\n" "${1:-unknown error}" >&2
            exit "${2:-1}"
        }

        install_syft() {
            SYFT_URL="https://raw.githubusercontent.com/anchore/syft/main/install.sh"
            for i in {1..3}; do
                # auto-detects arch via Anchore installer
                curl -fsSL "${SYFT_URL}" | sudo sh -s -- -b /usr/local/bin &&  break
                sleep 10
            done
            command -v syft >/dev/null 2>&1 || abort 'failed to install `syft`'
        }

        if ! command -v syft >/dev/null 2>&1; then
            install_syft
        fi

        echo "\`syft\` version: $(syft version | awk '/^Version: /{print $2}')";
    '''
}

def installOras() {
    sh '''
        abort() {
            printf "Error: %s\n" "${1:-unknown error}" >&2
            exit "${2:-1}"
        }

        install_oras() {
            ORAS_VERSION=1.2.3

            CPU_ARCH=$(uname -m)
            case "$CPU_ARCH" in
                x86_64)   ORAS_ARCH=amd64 ;;
                aarch64|arm64)  ORAS_ARCH=arm64 ;;
                *) abort "unsupported CPU architecture \`${CPU_ARCH}\` for \`oras\`" ;;
            esac

            ORAS_URL="https://github.com/oras-project/oras/releases/download/v${ORAS_VERSION}"
            ORAS_URL="$ORAS_URL/oras_${ORAS_VERSION}_linux_${ORAS_ARCH}.tar.gz"
            for i in {1..3}; do
                curl -fsSL "${ORAS_URL}" -o /tmp/oras.tar.gz \
                    && sudo tar -xzf /tmp/oras.tar.gz -C /usr/local/bin oras \
                    && rm -f /tmp/oras.tar.gz \
                    && break
                sleep 10
            done

            command -v oras >/dev/null 2>&1 || abort 'failed to install `oras`'
        }

        if ! command -v oras >/dev/null 2>&1; then
            install_oras
        fi

        echo "\`oras\` version: $(oras version | awk '/^Version: /{print $2}')";
    '''
}

def installJq() {
    sh '''
        abort() {
            printf "Error: %s\n" "${1:-unknown error}" >&2
            exit "${2:-1}"
        }

        install_jq() {
            if command -v apt-get >/dev/null 2>&1; then
                sudo apt-get update && sudo apt-get install -y jq
            elif command -v dnf >/dev/null 2>&1; then
                sudo dnf install -y jq
            elif command -v yum >/dev/null 2>&1; then
                sudo yum install -y jq
            else
                abort 'no supported package manager for `jq`'
            fi
        }

        if ! command -v jq >/dev/null 2>&1; then
            install_jq
        fi

        echo "\`jq\` version: $(jq --version | awk -F '-' '{print $2}')";
    '''
}

def installAwsCli() {
    sh '''
        abort() {
            printf "Error: %s\n" "${1:-unknown error}" >&2
            exit "${2:-1}"
        }

        install_aws_cli() {
            CPU_ARCH=$(uname -m)
            case "$CPU_ARCH" in
                x86_64)         AWS_ARCH=x86_64 ;;
                aarch64|arm64)  AWS_ARCH=aarch64 ;;
                *) abort "unsupported CPU architecture \`${CPU_ARCH}\` for aws cli" ;;
            esac

            if command -v apt-get >/dev/null 2>&1; then
                sudo apt-get update && sudo apt-get install -y unzip
            elif command -v dnf >/dev/null 2>&1; then
                sudo dnf install -y unzip
            elif command -v yum >/dev/null 2>&1; then
                sudo yum install -y unzip
            else
                abort 'no supported package manager for `unzip`'
            fi

            AWS_CLI_URL="https://awscli.amazonaws.com/awscli-exe-linux-${AWS_ARCH}.zip"
            curl -fsSL "${AWS_CLI_URL}" -o /tmp/awscliv2.zip
            unzip -q -o /tmp/awscliv2.zip -d /tmp
            sudo /tmp/aws/install --update
            rm -rf /tmp/awscliv2.zip /tmp/aws
        }

        if ! command -v aws >/dev/null 2>&1; then
            install_aws_cli
        fi
        echo "\`aws\` version: $(aws --version | awk -F '[ /]' '{print $2}')"
    '''
}

/** @} */


/**
 * PSMDB version and its components.
 *
 * The class consolidates parsing a PSMDB version in a single place using
 * a unified approach and provides interface to version components under
 * a consistent and conventional terminology, that is as close as possible to
 * semantic versioning and industry conventions. The following example
 * illustrates the terminology. In PSMDB version `8.0.26-11`:
 * - major version is 8;
 * - minor version is 0;
 * - patch version is 26;
 * - sequential number is 11;
 * - full version is `8.0.26-11`;
 * - core version is `8.0.26`;
 * - feature version is `8.0`.
 *
 * Sequential number, which is a Percona-specific add-on rather than a part
 * of semantic versioning, is just an incrementing release counter within
 * a particular feature version series. We sometimes skip some patch version
 * releases. For example, after releasing `8.0.23-10` we next released
 * `8.0.26-11`, skipping `8.0.24` and `8.0.25`.
 *
 * Example:
 * ```
 * def v = new Version("8.0.26-11")
 *
 * assert v.major == 8
 * assert v.minor == 0
 * assert v.patch == 26
 * assert v.seqNum == 11
 *
 * assert "$v" == "8.0.26-11"
 * assert v.full == "8.0.26-11"
 * assert v.core == "8.0.26"
 * assert v.feature == "8.0"
 *```
 */
class Version {
    private static final String VERSION_PATTERN =
        /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)-([1-9]\d*)$/

    final String full
    final String feature
    final String core
    final int major
    final int minor
    final int patch
    final int seqNum

    Version(String version) {
        if (version == null || version.trim().isEmpty()) {
            throw new IllegalArgumentException("Version string must not be null or empty")
        }

        this.full = version
        def matcher = this.full =~ VERSION_PATTERN

        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid version: '${version}'")
        }

        this.major = matcher.group(1) as int
        this.minor = matcher.group(2) as int
        this.patch = matcher.group(3) as int
        this.seqNum = matcher.group(4) as int
        this.feature = "${this.major}.${this.minor}"
        this.core = "${this.major}.${this.minor}.${this.patch}"
    }

    @Override
    String toString() {
        return full
    }
}

/**
 * Docker registry.
 *
 * The "Registry" term is used loosely here and encompasses almost all the
 * things related to a Docker image. A fully-qualified image reference has the
 * form `[REGISTRY_HOST[:PORT]/][NAMESPACE/]REPOSITORY_NAME[:TAG]`. In addition
 * to `REGISTRY_HOST` and `PORT` components, the class also manages
 * `NAMESPACE` and `REPOSITORY_NAME`, as well as `TAG`-related mechanisms.
 */
abstract class DockerRegistry {
    def cpsScript
    String repoPath
    String credId

    /**
     * Constructs a new instance.
     *
     * @param cpsScript  A reference to an instance of the `CpsScript` class,
     *                   see below.
     * @param repoPath   The repo path, e.g. the
     *                   `[REGISTRY_HOST[:PORT]/][NAMESPACE/]REPOSITORY_NAME`
     *                   part of a fully-qualified Docker image reference.
     * @param credId     The identifier of the credentials used for the
     *                   registry.
     *
     * In top-level functions (e.g. `installSyft`, `scanImage`, etc.),
     * calls to pipeline steps like `sh` or `withCredentials` are actually
     * shorthands for `this.sh` and `this.withCredentials` where `this` is a
     * reference to an instance of the `CpsScript` class from the Jenkins
     * framework, that each Groovy script eventually becomes a part of.
     * Since top-level functions become the methods of `CpsScript`, simple
     * `sh` and `withCredentials` do work. For non-top-level functions, like
     * the methods of this class, we must explicitly provide an object to call
     * `sh` and `withCredentials` on. That is exactly what the `cpsScript`
     * parameter is for.
     */
    DockerRegistry(def cpsScript, String repoPath, String credId) {
        this.cpsScript = cpsScript
        this.repoPath = repoPath
        this.credId = credId
    }

    /** Login into the docker registry account */
    abstract void login()

    /**
     * Produces a shell function that generates a fully-qualified image
     * reference.
     *
     * The function is named `<fnName>` and accepts three positional parameters:
     * $1 - image version, mandatory;
     * $2 - image architecture, optional, possible values: `amd64`, `arm64`;
     * $3 - indicator of an image with debug info, optional, either nothing or
     *      literal `debug` string.
     *
     * @param fnName  The function name
     *
     * @return Shell code that defines the function
     */
    abstract String imageRefFn(String fnName = 'image_ref')

    String manifestRefFn() {
        // Manifest reference format repeats that of Docker image, so we can
        // just generate the same function body with a different name.
        return imageRefFn('manifest_ref')
    }

    void pushImage(Version v, String arch, String debug) {
        cpsScript.withCredentials([cpsScript.usernamePassword(credentialsId: this.credId,
                                                                passwordVariable: 'PASS',
                                                                usernameVariable: 'USER')]) {
            login()
            cpsScript.sh """
                ${this.imageRefFn()}

                for ver in ${v} ${v.core} ${v.feature}; do
                    ref=\$(image_ref \${ver} ${arch})
                    docker tag percona-server-mongodb:local-${arch} \${ref}
                    docker push \${ref}
                done

                if [ "${debug}" = "yes" ]; then
                    ref=\$(image_ref ${v} ${arch} "debug")
                    docker tag percona-server-mongodb-debug:local-${arch} \${ref}
                    docker push \${ref}
                fi
            """
        }

    }

    void createMultiArchManifest(Version v) {
        cpsScript.withCredentials([cpsScript.usernamePassword(credentialsId: this.credId,
                                                                passwordVariable: 'PASS',
                                                                usernameVariable: 'USER')]) {
            login()
            cpsScript.sh """
                ${this.manifestRefFn()}

                for ver in ${v} ${v.core} ${v.feature}; do
                    docker buildx imagetools create -t \$(manifest_ref \${ver}) \\
                        \$(manifest_ref \${ver} 'amd64') \$(manifest_ref \${ver} 'arm64')
                    docker buildx imagetools inspect \$(manifest_ref \${ver})
                done
            """
        }
    }

    void attachSboms(Version v) {
        cpsScript.installJq()
        cpsScript.installOras()
        cpsScript.withCredentials([cpsScript.usernamePassword(credentialsId: this.credId,
                                                                passwordVariable: 'PASS',
                                                                usernameVariable: 'USER')]) {
            login()
            cpsScript.sh """
                ${this.manifestRefFn()}

                abort() {
                    printf "Error: %s\\n" "\${1:-unknown error}" >&2
                    exit "\${2:-1}"
                }

                ref="\$(manifest_ref ${v.feature})"
                inspection_result="\$(docker buildx imagetools inspect --raw \${ref})"

                for arch in 'amd64' 'arm64'; do
                    digest=\$(echo "\${inspection_result}" \\
                        | jq -r ".manifests[] \\
                                 | select(.platform.architecture==\"\${arch}\") \\
                                 | .digest")
                    [ -n "\$digest" ] || abort "failed to resolve \${arch} digest"

                    oras attach --artifact-type application/vnd.cyclonedx+json \\
                        "${this.repoPath}@\${digest}" \\
                        "percona-server-mongodb-${v}-\${arch}.cdx.json"

                    echo "SBOM attached:"
                    oras discover --format tree "${this.repoPath}@\${digest}"
                done
            """
        }
    }

}

class DockerHub extends DockerRegistry {

    DockerHub(def script, String repoPath) {
        super(script, repoPath, 'hub.docker.com')
    }

    @Override
    void login() {
        cpsScript.sh '''
            echo "${PASS}" | docker login -u "${USER}" --password-stdin
        '''
    }

    @Override
    /** See the comment for `imageRefFn` in the `DockerRegistry` class */
    String imageRefFn(String fnName = 'image_ref') {
        return """
            ${fnName}() {
                _ir="${this.repoPath}:\${1}"
                _ir="\${_ir}\$([ "\${3:-}" = "debug" ] && echo '-debug' || echo '')"
                _ir="\${_ir}\${2:+-\$2}"
                echo "\${_ir}"
            }
        """
    }


}

class AwsEcr extends DockerRegistry {
    final String tagPrefix

    AwsEcr(def script) {
        super(script, 'public.ecr.aws/e7j3v3n0/psmdb-build', '8468e4e0-5371-4741-a9bb-7c143140acea')
        this.tagPrefix ='psmdb-'
    }

    @Override
    void login() {
        cpsScript.installAwsCli()
        cpsScript.sh '''
            aws ecr-public get-login-password --region us-east-1 \
                | docker login --username AWS --password-stdin public.ecr.aws
        '''
    }

    @Override
    /** See the comment for `imageRefFn` in the `DockerRegistry` class */
    String imageRefFn(String fnName = 'image_ref') {
        return """
            ${fnName}() {
                _ir="${this.repoPath}:${this.tagPrefix}\${1}"
                _ir="\${_ir}\$([ "\${3:-}" = "debug" ] && echo '-debug' || echo '')"
                _ir="\${_ir}\${2:+-\$2}"
                echo "\${_ir}"
            }
        """
    }
}

/**
 * Creates a `DockerRegistry` instance.
 *
 * @param script     Enclosing pipeline script (a CpsScript)
 * @param imageType  The type of an image this pipeline creates.
 *                   See the `IMAGE_TYPE` pipeline parameter.
 *
 * @return An instance of the `DockerRegistry` class.
 */
DockerRegistry createDockerRegistry(def script, String imageType) {
    switch (imageType) {
        case 'experimental':
            return new AwsEcr(script)
        case 'testing':
            return new DockerHub(script, 'docker.io/perconalab/percona-server-mongodb')
        case 'release':
            return new DockerHub(script, 'docker.io/percona/percona-server-mongodb')
        default:
            error "Unknown image type: ${imageType}"
    }
}

/**
 * @defgroup Helper Functions That Comprise Pipeline Stages
 * @{
 *
 * Note. Each helper function depends exclusively on its arguments.
 * In particular, no pipeline parameters are used in the function definitions.
 * Instead, they are passed as arguments. This way, data dependencies are easier
 * to follow. Please maintain that invariant and
 * **DO NOT USE PIPELINE PARAMETERS DIRECTLY IN THE FUNCTIONS**.
 * Use function arguments instead.
 *
 * Note. Some of the functions below are not "real functions" in the sense
 * that they implicitly depend on other "functions" having been called before
 * in order to operate correctly. Nevertheless, splitting pipeline stage code
 * into such "functions" massively improves readability.
 */

/**
 * Builds a Docker image.
 *
 * For parameter descriptions, see the comment for the `buildStage` function.
 *
 * @return nothing
 */
def buildImage(Version v,
               String arch,
               String imageType,
               String debug) {
    sh """
        git clone --depth 1 https://github.com/percona/percona-docker
        cd percona-docker/percona-server-mongodb-${v.feature}

        sed -E \\
            -e "s/ENV PSMDB_VERSION (.+)/ENV PSMDB_VERSION ${v}/" \\
            -e "s/ENV PSMDB_REPO (.+)/ENV PSMDB_REPO ${imageType}\\nENV IMAGE_TYPE ${imageType}/" \\
            -i Dockerfile.${arch}

        docker buildx build --provenance=false --sbom=false --load \\
            -f Dockerfile.${arch} -t percona-server-mongodb:local-${arch} .

        if [ ${debug} = "yes" ]; then
            sed -E "s/FROM percona(.+)/FROM percona-server-mongodb:local-${arch}/" \\
                -i Dockerfile.debug
            docker buildx build --provenance=false --sbom=false --load \\
                -f Dockerfile.debug -t percona-server-mongodb-debug:local-${arch} .
        fi
    """
}

/**
 * Scans the built Docker image for security issues.
 *
 * The `buildImage` must be called prior to calling this function.
 * The function also publishes the scanning report file.
 *
 * @param arch      See the comment for the `buildStage` function.
 * @param exitCode  The code Trivy exits with when any security issues are
 *                  found. Default value is 1.
 *
 * @return Trivy's exit code
 */
int scanImage(String arch, int exitCode = 1) {
    installTrivy(method: 'binary', junitTpl: true)
    String reportFile = "trivy-high-junit-${arch}.xml"
    int status = sh(
        returnStatus: true,
        script: """
            TRIVY_IGNORE_URL="https://raw.githubusercontent.com/Percona-QA/psmdb-testing"
            TRIVY_IGNORE_URL="\$TRIVY_IGNORE_URL/main/docker/trivyignore"
            curl -fsSL "\${TRIVY_IGNORE_URL}" -o ".trivyignore" || rm -f ".trivyignore"
            /usr/local/bin/trivy -q image --format template --template @junit.tpl \\
                -o ${reportFile} --timeout 10m0s --ignore-unfixed \\
                --exit-code ${exitCode} --severity HIGH,CRITICAL \\
                percona-server-mongodb:local-${arch}
        """
    )
    junit testResults: "${reportFile}", keepLongStdio: true, allowEmptyResults: true,
        skipPublishingChecks: true
    return status
}

/**
 * Generates an SBOM file for the built image
 *
 * The `buildImage` must be called prior to calling this function.
 *
 * For parameter descriptions, see the comment for the `buildStage` function.
 *
 * @return The name of the generated SBOM file.
 */
def createSbom(Version v, String arch, String repoPath) {
    installJq()
    installSyft()

    def sbomFile = "percona-server-mongodb-${v}-${arch}.cdx.json"

    sh """
        abort() {
            printf "Error: %s\\n" "\${1:-unknown error}" >&2
            exit "\${2:-1}"
        }

        LOCAL_DIGEST=\$(docker inspect --format='{{.Id}}' percona-server-mongodb:local-${arch})
        PURL="pkg:oci/percona-server-mongodb@\${LOCAL_DIGEST}?repository_url=${repoPath}"

        echo "Generating CycloneDX 1.6 SBOM for local-${arch} image..."
        syft scan "docker:percona-server-mongodb:local-${arch}" \\
            --override-default-catalogers go-module-binary-cataloger,rpm-db-cataloger \\
            --select-catalogers "-file" \\
            --source-name "percona-server-mongodb" \\
            --source-version "${v}" \\
            -o "cyclonedx-json@1.6=${sbomFile}"

        jq --arg purl "\$PURL" --arg ver "${v}" '.metadata.component = {
            "bom-ref": \$purl,
            "type": "application",
            "name": "percona-server-mongodb",
            "version": \$ver,
            "purl": \$purl
        }' "${sbomFile}" > "${sbomFile}.tmp" && mv "${sbomFile}.tmp" "${sbomFile}"

        COMPONENT_COUNT=\$(jq '.components | length' "${sbomFile}")
        [ "\${COMPONENT_COUNT}" -lt 10 ] \\
            && abort "${arch} SBOM has only \${COMPONENT_COUNT} components"
        echo "${arch} SBOM: ${sbomFile} (\${COMPONENT_COUNT} components)"
    """

    return sbomFile
}

/**
 * Implements the build stage of the pipeline.
 *
 * In particular:
 * - builds a Docker image for the specified PSMDB version and CPU architecture;
 * - scans the built image for security issues and publishes the report file;
 * - generates and stashes the SBOM file for the built image;
 * - pushes the built image to the Docker registry.
 *
 * @param psmdbVersion  The PSMDB version for which the image is created.
 * @param arch          The CPU architecture the image is created for.
 *                      Possible values: `amd64`, `arm64`.
 * @param imageType     The type of the image. See the `IMAGE_TYPE` pipeline
 *                      parameter for details.
 * @param debug         Whether the image with debug info must also be created.
 *                      Specify "yes" or "no"
 *
 * @return nothing
 */
def buildStage(String psmdbVersion,
               String arch,
               String imageType,
               String debug) {
    deleteDir()

    Version v = new Version(psmdbVersion)
    DockerRegistry reg = createDockerRegistry(this, imageType)

    buildImage(v, arch, imageType, debug)

    if (scanImage(arch, "${imageType}" == "release" ? 1 : 0) != 0) {
        error "Image scanning has found security issues. See the published report file."
    }

    def sbomFile = createSbom(v, arch, reg.repoPath)
    stash includes: sbomFile, name: "sbom-${arch}"

    // Postponing pushing the image(s) until after an SBOM has been created
    // ensures that images are pushed only if creating an SBOM succeeds.
    reg.pushImage(v, arch, debug)
}

/**
 * Tests the built PSMDB Docker image with the most recent PBM.
 *
 * For parameter descriptions, see the comment for the `buildStage` function.
 *
 * @return nothing
 */
def testWithPbmStage(String psmdbVersion,
                     String arch,
                     String imageType) {
    DockerRegistry reg = createDockerRegistry(this, imageType)
    // This only validates `psmdbVersion` validity
    Version v = new Version(psmdbVersion)

    def psmdbImage = sh(
        returnStdout: true,
        script: """
            ${reg.imageRefFn()}
            echo \$(image_ref ${v} ${arch})
        """
    ).trim()

    // The script below works as follows:
    // - List all branches (heads) in the remote repo. This stage produces lines
    //   in the `<40_char_hash><some_spaces>refs/heads/<branch_name>` format
    // - Ignore all the lines, except those that have the "release" word in
    //   them (`/release/`), for each remaining line:
    //   - consider only the part following the spaces (`$2`)
    //   - in that part, remove `refs/heads/` by globally substituting (`gsub`)
    //     it with an empty string (`""`); note that forward slashes in
    //     `refs/heads/` need escaping with backslashes to be differentiated
    //     with forward slashes that mark beginning and ending of a regular
    //     expression
    //   - print the result on its own line
    // - Sort the printed results by version number
    // - Take only the last one, e.g. that with the highest version
    def pbmBranch = sh(
        returnStdout: true,
        script: '''
            echo $(git ls-remote --heads https://github.com/percona/percona-backup-mongodb.git \
                | awk '/release/{gsub(/refs\/heads\//, "", $2); print $2}' \
                | sort --version-sort \
                | tail -1)
        '''
    ).trim()

    build \
        job: "hetzner-pbm-functional-tests",
        propagate: false,
        wait: false,
        parameters: [
            string(name: 'instance', value: "docker-${arch == 'arm64' ? 'aarch64' : 'x64'}"),
            string(name: 'PBM_BRANCH', value: pbmBranch),
            string(name: 'PSMDB', value: psmdbImage),
            string(name: 'TESTING_BRANCH', value: "pbm-${pbmBranch}"),
            booleanParam(name: 'ADD_JENKINS_MARKED_TESTS', value: true)
        ]
}

/** @} */


pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        choice(name: 'CLOUD', choices: [ 'Hetzner','AWS' ], description: 'Cloud infra for build')

        choice(name: 'IMAGE_TYPE',
               choices: ['experimental', 'testing','release'],
               description: "Type of the Docker images to create. \n"
                             + "The pipeline pushes:\n"
                             + "- experimental images -- to AWS ECR,\n"
                             + "- testing images -- to the 'PerconaLab' namespace on DockerHub,\n"
                             + "- release images -- to the 'Percona' namespace on DockerHub")

        string(name: 'PSMDB_VERSION', defaultValue: '6.0.2-1', description: 'PSMDB version')
        choice(name: 'DEBUG', choices: ['no','yes'], description: 'Additionally build debug image')
        choice(name: 'TESTS', choices: ['yes','no'], description: 'Run tests after building')
    }
    options {
        disableConcurrentBuilds()
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${params.IMAGE_TYPE}-${params.PSMDB_VERSION}"
                }
            }
        }

        stage('Build image and SBOM') {
            parallel {
                stage('amd64') {
                    agent { label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb' }
                    steps {
                        script {
                            buildStage("${params.PSMDB_VERSION}",
                                       'amd64',
                                       "${params.IMAGE_TYPE}",
                                       "${params.DEBUG}")
                        }
                    }
                    post {
                        always {
                            sh 'sudo docker rmi -f $(sudo docker images -q | uniq) || true'
                            deleteDir()
                        }
                    }
                }
                stage('arm64') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
                    }
                    steps {
                        script {
                            buildStage("${params.PSMDB_VERSION}",
                                       'arm64',
                                       "${params.IMAGE_TYPE}",
                                       "${params.DEBUG}")
                        }
                    }
                    post {
                        always {
                            sh 'sudo docker rmi -f $(sudo docker images -q | uniq) || true'
                            deleteDir()
                        }
                    }
                }
            }
        }

        stage('Create a multi-arch manifest') {
            agent { label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb' }
            steps {
                script {
                    Version v = new Version("${params.PSMDB_VERSION}")
                    DockerRegistry reg = createDockerRegistry(this, "${params.IMAGE_TYPE}")
                    reg.createMultiArchManifest(v)
                }
            }
        }

        stage('Attach SBOMs') {
            agent { label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb' }
            steps {
                script {
                    unstash 'sbom-amd64'
                    unstash 'sbom-arm64'

                    Version v = new Version("${params.PSMDB_VERSION}")
                    DockerRegistry reg = createDockerRegistry(this, "${params.IMAGE_TYPE}")
                    reg.attachSboms(v)
                }
            }
        }

        stage('Run tests with PBM') {
            when { environment name: 'TESTS', value: 'yes' }
            parallel {
                stage('amd64') {
                    agent { label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb' }
                    steps {
                        script {
                            testWithPbmStage("${params.PSMDB_VERSION}",
                                             'amd64',
                                             "${params.IMAGE_TYPE}")
                        }
                    }
                }
                stage('arm64') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
                    }
                    steps {
                        script {
                            testWithPbmStage("${params.PSMDB_VERSION}",
                                             'arm64',
                                             "${params.IMAGE_TYPE}")
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            sh """
                sudo docker rmi -f \$(sudo docker images -q | uniq) || true
                sudo rm -rf ${WORKSPACE}/*
            """
            deleteDir()
        }
        success {
            script {
                String msg = "[${JOB_NAME}]: "
                msg += "Multi-arch PSMDB ${PSMDB_VERSION} ${IMAGE_TYPE} Docker image: "
                msg += "succeeded"
                slackNotify("#mongodb_autofeed", "#00FF00", msg)
            }
        }
        unstable {
            script {
                String msg = "[${JOB_NAME}]: "
                msg += "Multi-arch PSMDB ${PSMDB_VERSION} ${IMAGE_TYPE} Docker image: "
                msg += "unstable - [${BUILD_URL}testReport/]"
                slackNotify("#mongodb_autofeed", "#F6F930", msg)
            }
        }
        failure {
            script {
                String msg = "[${JOB_NAME}]: "
                msg += "Multi-arch PSMDB ${PSMDB_VERSION} ${IMAGE_TYPE} Docker image: "
                msg += "failed - [${BUILD_URL}]"
                slackNotify("#mongodb_autofeed", "#F6F930", msg)
            }
        }
    }
}
