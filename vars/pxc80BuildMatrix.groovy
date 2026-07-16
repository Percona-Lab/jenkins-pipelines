/**
 * pxc80BuildMatrix.groovy - PXC 8.0 build matrix shared library
 *
 * Runs parallel build stages for Percona XtraDB Cluster 8.0 packages.
 * Handles agent label selection, FIPS mode, and version constraints.
 *
 * Usage:
 *   pxc80BuildMatrix(
 *       cloud:        params.CLOUD,
 *       awsStashPath: AWS_STASH_PATH,
 *       fipsMode:     env.FIPSMODE,
 *       onlyStages:   params.BUILD_STAGES ? params.BUILD_STAGES.split(',').collect { it.trim() } : []
 *   )
 */

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
        withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
            sh """
                set -o xtrace
                mkdir -p test
                if [ \${FIPSMODE} = "YES" ]; then
                    PXC_VERSION_MINOR=\$(curl -s -O \$(echo \${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/\${GIT_BRANCH}/MYSQL_VERSION && cat MYSQL_VERSION | grep MYSQL_VERSION_MINOR | awk -F= '{print \$2}')
                    if [ \${PXC_VERSION_MINOR} = "0" ]; then
                        PRO_BRANCH="8.0"
                    elif [ \${PXC_VERSION_MINOR} = "4" ]; then
                        PRO_BRANCH="8.4"
                    else
                        PRO_BRANCH="trunk"
                    fi
                    curl -L -H "Authorization: Bearer \${TOKEN}" \\
                        -H "Accept: application/vnd.github.v3.raw" \\
                        -o pxc_builder.sh \\
                        "https://api.github.com/repos/percona/percona-xtradb-cluster-private-build/contents/build-ps/pxc_builder.sh?ref=\${PRO_BRANCH}"
                    sed -i "s/PRIVATE_USERNAME/\${USERNAME}/g" pxc_builder.sh
                    sed -i "s/PRIVATE_TOKEN/\${PASSWORD}/g" pxc_builder.sh
                else
                    wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/build-ps/pxc_builder.sh -O pxc_builder.sh
                fi
                pwd -P
                export build_dir=\$(pwd -P)
                docker run --shm-size=16g --cap-add=SYS_NICE -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
                    set -o xtrace
                    cd \${build_dir}
                    bash -x ./pxc_builder.sh --builddir=\${build_dir}/test --install_deps=1
                    if [ \${FIPSMODE} = "YES" ]; then
                        git clone --depth 1 --branch \${PRO_BRANCH} https://x-access-token:${TOKEN}@github.com/percona/percona-xtradb-cluster-private-build.git percona-xtradb-cluster-private-build
                        mv -f \${build_dir}/percona-xtradb-cluster-private-build/build-ps \${build_dir}/test/.
                    fi
                    bash -x ./pxc_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${GIT_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} --bin_release=${BIN_RELEASE} ${STAGE_PARAM}"
            """
        }
    }
}

def call(Map args = [:]) {
    def cloud        = args.get('cloud', '')
    def awsStashPath = args.get('awsStashPath', '')
    def fipsMode     = args.get('fipsMode', 'NO')
    def onlyStages   = args.get('onlyStages', [])

    // Each entry: name, image, arch, buildType, flags, fipsFlags (null = no FIPS variant),
    //             skipInFips, versionConstraint
    def stages = [
        // ---- RPM stages ----
        [name: 'Centos 8',                 image: 'centos:8',         arch: 'x64',     buildType: 'rpm',
         flags: '--build_rpm=1',           fipsFlags: null,
         skipInFips: true],
        [name: 'Centos 8 ARM',             image: 'centos:8',         arch: 'aarch64', buildType: 'rpm',
         flags: '--build_rpm=1',           fipsFlags: null,
         skipInFips: true],
        [name: 'Oracle Linux 9',           image: 'oraclelinux:9',    arch: 'x64',     buildType: 'rpm',
         flags: '--build_rpm=1',           fipsFlags: '--build_rpm=1 --enable_fipsmode=1',
         skipInFips: false],
        [name: 'Oracle Linux 9 ARM',       image: 'oraclelinux:9',    arch: 'aarch64', buildType: 'rpm',
         flags: '--build_rpm=1',           fipsFlags: '--build_rpm=1 --enable_fipsmode=1',
         skipInFips: false],
        [name: 'Oracle Linux 10',          image: 'oraclelinux:10',   arch: 'x64',     buildType: 'rpm',
         flags: '--build_rpm=1',           fipsFlags: '--build_rpm=1 --enable_fipsmode=1',
         skipInFips: false,
         versionConstraint: [[major: '8', minor: '4'], [major: '9', minor: '7']]],
        [name: 'Oracle Linux 10 ARM',      image: 'oraclelinux:10',   arch: 'aarch64', buildType: 'rpm',
         flags: '--build_rpm=1',           fipsFlags: '--build_rpm=1 --enable_fipsmode=1',
         skipInFips: false,
         versionConstraint: [[major: '8', minor: '4'], [major: '9', minor: '7']]],
        [name: 'Amazon Linux 2023',        image: 'amazonlinux:2023', arch: 'x64',     buildType: 'rpm',
         flags: '--build_rpm=1',           fipsFlags: null,
         skipInFips: false],
        [name: 'Amazon Linux 2023 ARM',    image: 'amazonlinux:2023', arch: 'aarch64', buildType: 'rpm',
         flags: '--build_rpm=1',           fipsFlags: null,
         skipInFips: false],
        // ---- DEB stages ----
        [name: 'Ubuntu Jammy(22.04)',      image: 'ubuntu:jammy',     arch: 'x64',     buildType: 'deb',
         flags: '--build_deb=1',           fipsFlags: '--build_deb=1 --enable_fipsmode=1',
         skipInFips: false],
        [name: 'Ubuntu Jammy(22.04) ARM',  image: 'ubuntu:jammy',     arch: 'aarch64', buildType: 'deb',
         flags: '--build_deb=1',           fipsFlags: '--build_deb=1 --enable_fipsmode=1',
         skipInFips: false],
        [name: 'Ubuntu Noble(24.04)',      image: 'ubuntu:noble',     arch: 'x64',     buildType: 'deb',
         flags: '--build_deb=1',           fipsFlags: '--build_deb=1 --enable_fipsmode=1',
         skipInFips: false],
        [name: 'Ubuntu Noble(24.04) ARM',  image: 'ubuntu:noble',     arch: 'aarch64', buildType: 'deb',
         flags: '--build_deb=1',           fipsFlags: '--build_deb=1 --enable_fipsmode=1',
         skipInFips: false],
        [name: 'Ubuntu Resolute(26.04)',   image: 'ubuntu:resolute',  arch: 'x64',     buildType: 'deb',
         flags: '--build_deb=1',           fipsFlags: '--build_deb=1 --enable_fipsmode=1',
         skipInFips: false,
         versionConstraint: [[major: '8', minor: '4'], [major: '9', minor: '7']]],
        [name: 'Ubuntu Resolute(26.04) ARM', image: 'ubuntu:resolute', arch: 'aarch64', buildType: 'deb',
         flags: '--build_deb=1',           fipsFlags: '--build_deb=1 --enable_fipsmode=1',
         skipInFips: false,
         versionConstraint: [[major: '8', minor: '4'], [major: '9', minor: '7']]],
        [name: 'Debian Bullseye(11)',      image: 'debian:bullseye',  arch: 'x64',     buildType: 'deb',
         flags: '--build_deb=1',           fipsFlags: null,
         skipInFips: true],
        [name: 'Debian Bullseye(11) ARM',  image: 'debian:bullseye',  arch: 'aarch64', buildType: 'deb',
         flags: '--build_deb=1',           fipsFlags: null,
         skipInFips: true],
        [name: 'Debian Bookworm(12)',      image: 'debian:bookworm',  arch: 'x64',     buildType: 'deb',
         flags: '--build_deb=1',           fipsFlags: '--build_deb=1 --enable_fipsmode=1',
         skipInFips: false],
        [name: 'Debian Bookworm(12) ARM',  image: 'debian:bookworm',  arch: 'aarch64', buildType: 'deb',
         flags: '--build_deb=1',           fipsFlags: '--build_deb=1 --enable_fipsmode=1',
         skipInFips: false],
        [name: 'Debian Trixie(13)',        image: 'debian:trixie',    arch: 'x64',     buildType: 'deb',
         flags: '--build_deb=1',           fipsFlags: '--build_deb=1 --enable_fipsmode=1',
         skipInFips: false,
         versionConstraint: [[major: '8', minor: '4'], [major: '9', minor: '7']]],
        [name: 'Debian Trixie(13) ARM',    image: 'debian:trixie',    arch: 'aarch64', buildType: 'deb',
         flags: '--build_deb=1',           fipsFlags: '--build_deb=1 --enable_fipsmode=1',
         skipInFips: false,
         versionConstraint: [[major: '8', minor: '4'], [major: '9', minor: '7']]],
        // ---- Tarball stages ----
        [name: 'Centos 8 tarball',         image: 'centos:8',         arch: 'x64',     buildType: 'tarball',
         flags: '--build_tarball=1',       fipsFlags: null,
         skipInFips: true],
        [name: 'Oracle Linux 9 tarball',   image: 'oraclelinux:9',    arch: 'x64',     buildType: 'tarball',
         flags: '--build_tarball=1',       fipsFlags: '--build_tarball=1 --enable_fipsmode=1',
         skipInFips: false],
        [name: 'Debian Bullseye(11) tarball', image: 'debian:bullseye', arch: 'x64',   buildType: 'tarball',
         flags: '--build_tarball=1',       fipsFlags: null,
         skipInFips: true],
        [name: 'Ubuntu Jammy(22.04) tarball', image: 'ubuntu:jammy',  arch: 'x64',     buildType: 'tarball',
         flags: '--build_tarball=1',       fipsFlags: '--build_tarball=1 --enable_fipsmode=1',
         skipInFips: false],
        [name: 'Debian Trixie(13) tarball', image: 'debian:trixie',   arch: 'x64',     buildType: 'tarball',
         flags: '--build_tarball=1',       fipsFlags: null,
         skipInFips: false,
         versionConstraint: [[major: '8', minor: '4'], [major: '9', minor: '7']]],
    ]

    def sourceFolders = [rpm: 'srpm/', deb: 'source_deb/', tarball: 'source_tarball/']
    def targetFolders = [rpm: 'rpm/',  deb: 'deb/',        tarball: 'test/tarball/']

    def branches = [:]

    stages.each { s ->
        def stageName    = s.name
        def image        = s.image
        def arch         = s.arch
        def buildType    = s.buildType
        def flags        = s.flags
        def fipsFl       = s.fipsFlags
        def _skipInFips       = s.skipInFips
        def versionConstraint = s.get('versionConstraint', null)

        def agentLabel = arch == 'aarch64'
            ? (cloud == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64')
            : (cloud == 'Hetzner' ? 'docker-x64'     : 'docker-32gb')

        def sourceFolder = sourceFolders[buildType]
        def targetFolder = targetFolders[buildType]

        branches[stageName] = {
            stage(stageName) {
                if (onlyStages && !onlyStages.contains(stageName)) {
                    echo "Skipped: not in BUILD_STAGES filter"
                    return
                }
                if (_skipInFips && fipsMode == 'YES') {
                    echo "Skipping '${stageName}' (not supported in FIPS mode)"
                    return
                }
                if (versionConstraint) {
                    def major = env.MYSQL_VERSION_MAJOR
                    def minor = env.MYSQL_VERSION_MINOR
                    def allowed = versionConstraint.any { vc -> vc.major == major && vc.minor == minor }
                    if (!allowed) {
                        echo "Skipping '${stageName}' (version constraint not met, detected ${major}.${minor})"
                        return
                    }
                }
                node(agentLabel) {
                    cleanUpWS()
                    unstash 'pxc-80.properties'
                    popArtifactFolder(cloud, sourceFolder, awsStashPath)
                    def buildFlags = (fipsMode == 'YES' && fipsFl != null) ? fipsFl : flags
                    buildStage(image, buildFlags)
                    stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                    pushArtifactFolder(cloud, targetFolder, awsStashPath)
                    if (buildType == 'rpm') {
                        uploadRPMfromAWS(cloud, targetFolder, awsStashPath)
                    } else if (buildType == 'deb') {
                        uploadDEBfromAWS(cloud, targetFolder, awsStashPath)
                    } else {
                        uploadTarballfromAWS(cloud, targetFolder, awsStashPath, 'binary')
                    }
                }
            }
        }
    }

    parallel branches
}
