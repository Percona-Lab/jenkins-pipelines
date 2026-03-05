/**
 * psBuildMatrix.groovy - PS build matrix shared library
 *
 * Runs parallel build stages for Percona Server packages.
 * Handles agent label selection, FIPS mode, and artifact folder routing.
 *
 * Usage:
 *   psBuildMatrix(
 *       cloud: params.CLOUD,
 *       awsStashPath: AWS_STASH_PATH,
 *       fipsMode: env.FIPSMODE
 *   )
 */
def call(Map args = [:]) {
    def cloud        = args.get('cloud', '')
    def awsStashPath = args.get('awsStashPath', '')
    def fipsMode     = args.get('fipsMode', 'NO')

    // Build type -> [sourceFolder, targetFolder]
    def artifactFolders = [
        rpm:     ['srpm/',           'rpm/'],
        deb:     ['source_deb/',     'deb/'],
        tarball: ['source_tarball/', 'tarball/'],
    ]

    // All build stage definitions, faithfully matching the original pipeline.
    // Each map: name, image, arch, buildType, flags, fipsFlags (null = no FIPS variant),
    //           skipInFips (true = skip when FIPSMODE==YES)
    def stages = [
        // ---- RPM stages ----
        [
            name: 'Oracle Linux 8',
            image: 'oraclelinux:8', arch: 'x64', buildType: 'rpm',
            flags: '--build_rpm=1',
            fipsFlags: null, skipInFips: true,
        ],
        [
            name: 'Centos 8 ARM',
            image: 'centos:8', arch: 'aarch64', buildType: 'rpm',
            flags: '--build_rpm=1',
            fipsFlags: null, skipInFips: true,
        ],
        [
            name: 'Oracle Linux 9',
            image: 'oraclelinux:9', arch: 'x64', buildType: 'rpm',
            flags: '--build_rpm=1 --with_zenfs=1',
            fipsFlags: '--build_rpm=1 --with_zenfs=1 --enable_fipsmode=1', skipInFips: false,
        ],
        [
            name: 'Oracle Linux 9 ARM',
            image: 'oraclelinux:9', arch: 'aarch64', buildType: 'rpm',
            flags: '--build_rpm=1',
            fipsFlags: '--build_rpm=1 --enable_fipsmode=1', skipInFips: false,
        ],
        [
            name: 'Oracle Linux 10',
            image: 'oraclelinux:10', arch: 'x64', buildType: 'rpm',
            flags: '--build_rpm=1 --with_zenfs=1',
            fipsFlags: '--build_rpm=1 --with_zenfs=1 --enable_fipsmode=1', skipInFips: false,
        ],
        [
            name: 'Oracle Linux 10 ARM',
            image: 'oraclelinux:10', arch: 'aarch64', buildType: 'rpm',
            flags: '--build_rpm=1',
            fipsFlags: '--build_rpm=1 --enable_fipsmode=1', skipInFips: false,
        ],
        [
            name: 'Amazon Linux 2023',
            image: 'amazonlinux:2023', arch: 'x64', buildType: 'rpm',
            flags: '--build_rpm=1',
            fipsFlags: null, skipInFips: false,
        ],
        [
            name: 'Amazon Linux 2023 ARM',
            image: 'amazonlinux:2023', arch: 'aarch64', buildType: 'rpm',
            flags: '--build_rpm=1',
            fipsFlags: null, skipInFips: false,
        ],

        // ---- DEB stages (x64) ----
        [
            name: 'Ubuntu Focal(20.04)',
            image: 'ubuntu:focal', arch: 'x64', buildType: 'deb',
            flags: '--build_deb=1 --with_zenfs=1',
            fipsFlags: null, skipInFips: true,
        ],
        [
            name: 'Ubuntu Jammy(22.04)',
            image: 'ubuntu:jammy', arch: 'x64', buildType: 'deb',
            flags: '--build_deb=1 --with_zenfs=1',
            fipsFlags: '--build_deb=1 --with_zenfs=1 --enable_fipsmode=1', skipInFips: false,
        ],
        [
            name: 'Ubuntu Noble(24.04)',
            image: 'ubuntu:noble', arch: 'x64', buildType: 'deb',
            flags: '--build_deb=1 --with_zenfs=1',
            fipsFlags: '--build_deb=1 --with_zenfs=1 --enable_fipsmode=1', skipInFips: false,
        ],
        [
            name: 'Debian Bullseye(11)',
            image: 'debian:bullseye', arch: 'x64', buildType: 'deb',
            flags: '--build_deb=1 --with_zenfs=1',
            fipsFlags: null, skipInFips: true,
        ],
        [
            name: 'Debian Bookworm(12)',
            image: 'debian:bookworm', arch: 'x64', buildType: 'deb',
            flags: '--build_deb=1 --with_zenfs=1',
            fipsFlags: '--build_deb=1 --with_zenfs=1 --enable_fipsmode=1', skipInFips: false,
        ],
        [
            name: 'Debian Trixie(13)',
            image: 'debian:trixie', arch: 'x64', buildType: 'deb',
            flags: '--build_deb=1 --with_zenfs=1',
            fipsFlags: '--build_deb=1 --with_zenfs=1 --enable_fipsmode=1', skipInFips: false,
        ],

        // ---- DEB stages (ARM) ----
        [
            name: 'Ubuntu Focal(20.04) ARM',
            image: 'ubuntu:focal', arch: 'aarch64', buildType: 'deb',
            flags: '--build_deb=1 --with_zenfs=1',
            fipsFlags: null, skipInFips: true,
        ],
        [
            name: 'Ubuntu Jammy(22.04) ARM',
            image: 'ubuntu:jammy', arch: 'aarch64', buildType: 'deb',
            flags: '--build_deb=1 --with_zenfs=1',
            fipsFlags: '--build_deb=1 --with_zenfs=1 --enable_fipsmode=1', skipInFips: false,
        ],
        [
            name: 'Ubuntu Noble(24.04) ARM',
            image: 'ubuntu:noble', arch: 'aarch64', buildType: 'deb',
            flags: '--build_deb=1 --with_zenfs=1',
            fipsFlags: '--build_deb=1 --with_zenfs=1 --enable_fipsmode=1', skipInFips: false,
        ],
        [
            name: 'Debian Bullseye(11) ARM',
            image: 'debian:bullseye', arch: 'aarch64', buildType: 'deb',
            flags: '--build_deb=1 --with_zenfs=1',
            fipsFlags: null, skipInFips: true,
        ],
        [
            name: 'Debian Bookworm(12) ARM',
            image: 'debian:bookworm', arch: 'aarch64', buildType: 'deb',
            flags: '--build_deb=1 --with_zenfs=1',
            fipsFlags: '--build_deb=1 --with_zenfs=1 --enable_fipsmode=1', skipInFips: false,
        ],
        [
            name: 'Debian Trixie(13) ARM',
            image: 'debian:trixie', arch: 'aarch64', buildType: 'deb',
            flags: '--build_deb=1 --with_zenfs=1',
            fipsFlags: '--build_deb=1 --with_zenfs=1 --enable_fipsmode=1', skipInFips: false,
        ],

        // ---- Tarball stages ----
        [
            name: 'Oracle Linux 8 binary tarball',
            image: 'oraclelinux:8', arch: 'x64', buildType: 'tarball',
            flags: '--build_tarball=1',
            fipsFlags: null, skipInFips: true,
        ],
        [
            name: 'Oracle Linux 8 debug tarball',
            image: 'oraclelinux:8', arch: 'x64', buildType: 'tarball',
            flags: '--debug=1 --build_tarball=1',
            fipsFlags: null, skipInFips: true,
        ],
        [
            name: 'Oracle Linux 9 tarball',
            image: 'oraclelinux:9', arch: 'x64', buildType: 'tarball',
            flags: '--build_tarball=1',
            fipsFlags: '--build_tarball=1 --enable_fipsmode=1', skipInFips: false,
        ],
        [
            name: 'Oracle Linux 9 ZenFS tarball',
            image: 'oraclelinux:9', arch: 'x64', buildType: 'tarball',
            flags: '--build_tarball=1 --with_zenfs=1',
            fipsFlags: null, skipInFips: true,
        ],
        [
            name: 'Oracle Linux 9 debug tarball',
            image: 'oraclelinux:9', arch: 'x64', buildType: 'tarball',
            flags: '--debug=1 --build_tarball=1',
            fipsFlags: '--debug=1 --build_tarball=1 --enable_fipsmode=1', skipInFips: false,
        ],
        [
            name: 'Ubuntu Focal(20.04) tarball',
            image: 'ubuntu:focal', arch: 'x64', buildType: 'tarball',
            flags: '--build_tarball=1',
            fipsFlags: null, skipInFips: true,
        ],
        [
            name: 'Ubuntu Focal(20.04) debug tarball',
            image: 'ubuntu:focal', arch: 'x64', buildType: 'tarball',
            flags: '--debug=1 --build_tarball=1',
            fipsFlags: null, skipInFips: true,
        ],
        [
            name: 'Ubuntu Jammy(22.04) tarball',
            image: 'ubuntu:jammy', arch: 'x64', buildType: 'tarball',
            flags: '--build_tarball=1',
            fipsFlags: '--build_tarball=1 --enable_fipsmode=1', skipInFips: false,
        ],
        [
            name: 'Ubuntu Jammy(22.04) ZenFS tarball',
            image: 'ubuntu:jammy', arch: 'x64', buildType: 'tarball',
            flags: '--build_tarball=1 --with_zenfs=1',
            fipsFlags: null, skipInFips: true,
        ],
        [
            name: 'Ubuntu Jammy(22.04) debug tarball',
            image: 'ubuntu:jammy', arch: 'x64', buildType: 'tarball',
            flags: '--debug=1 --build_tarball=1',
            fipsFlags: '--debug=1 --build_tarball=1 --enable_fipsmode=1', skipInFips: false,
        ],
    ]

    // Build the parallel branches map
    def branches = [:]

    stages.each { s ->
        def stageName  = s.name
        def image      = s.image
        def arch       = s.arch
        def buildType  = s.buildType
        def flags      = s.flags
        def fipsFl     = s.fipsFlags
        def skip       = s.skipInFips

        def sourceFolder = artifactFolders[buildType][0]
        def targetFolder = artifactFolders[buildType][1]

        // Determine agent label based on arch and cloud
        def agentLabel
        if (arch == 'aarch64') {
            agentLabel = cloud == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
        } else {
            agentLabel = cloud == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
        }

        branches[stageName] = {
            stage(stageName) {
                // Skip stages marked skipInFips when FIPS mode is active
                if (skip && fipsMode == 'YES') {
                    echo "Skipping '${stageName}' (not supported in FIPS mode)"
                    return
                }

                node(agentLabel) {
                    cleanUpWS()
                    installCli("rpm")
                    unstash 'properties'
                    popArtifactFolder(cloud, sourceFolder, awsStashPath)

                    // Determine final build flags
                    def buildFlags
                    if (fipsMode == 'YES' && fipsFl != null) {
                        buildFlags = fipsFl
                    } else {
                        buildFlags = flags
                    }

                    buildStage(image, buildFlags)
                    pushArtifactFolder(cloud, targetFolder, awsStashPath)
                }
            }
        }
    }

    parallel branches
}
