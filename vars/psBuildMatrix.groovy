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

void installCli(String PLATFORM) {
    sh """
        set -o xtrace
        if [ -d aws ]; then
            rm -rf aws
        fi
        cat /etc/os-release
        if [ ${PLATFORM} = "deb" ]; then
            sudo apt-get update
            sudo apt-get -y install wget curl unzip
        elif [ ${PLATFORM} = "rpm" ]; then
            export RHVER=\$(rpm --eval %rhel)
            if [ \${RHVER} = "7" ]; then
                sudo sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-* || true
                sudo sed -i 's|#\\s*baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-* || true
                if [ -e "/etc/yum.repos.d/CentOS-SCLo-scl.repo" ]; then
                    cat /etc/yum.repos.d/CentOS-SCLo-scl.repo
                fi
            fi
            sudo yum -y install wget curl unzip
        fi
        curl https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o awscliv2.zip
        unzip awscliv2.zip
        sudo ./aws/install || true
    """
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
      sh """
          set -o xtrace
          free -h
          mkdir -p test
          if [ \${FIPSMODE} = "YES" ]; then
              MYSQL_VERSION_MINOR=\$(curl -s -O \$(echo \${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/\${BRANCH}/MYSQL_VERSION && grep MYSQL_VERSION_MINOR MYSQL_VERSION | awk -F= '{print \$2}')
              if [ \${MYSQL_VERSION_MINOR} = "0" ]; then
                  PRO_BRANCH="8.0"
              elif [ \${MYSQL_VERSION_MINOR} = "4" ]; then
                  PRO_BRANCH="8.4"
              else
                  PRO_BRANCH="trunk"
              fi
              curl -L -H "Authorization: Bearer \${TOKEN}" \
                      -H "Accept: application/vnd.github.v3.raw" \
                      -o ps_builder.sh \
                      "https://api.github.com/repos/percona/percona-server-private-build/contents/build-ps/percona-server-8.0_builder.sh?ref=\${PRO_BRANCH}"
              sed -i 's|percona-server-server/usr|percona-server-server-pro/usr|g' ps_builder.sh
              sed -i 's|dbg-package=percona-server-dbg|dbg-package=percona-server-pro-dbg|g' ps_builder.sh
          else
              wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BRANCH}/build-ps/percona-server-8.0_builder.sh -O ps_builder.sh || curl \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BRANCH}/build-ps/percona-server-8.0_builder.sh -o ps_builder.sh
          fi
          grep "percona-server-server" ps_builder.sh
          export build_dir=\$(pwd -P)
          if [ "$DOCKER_OS" = "none" ]; then
              set -o xtrace
              cd \${build_dir}
              if [ \${FIPSMODE} = "YES" ]; then
                  git clone --depth 1 --branch \${PRO_BRANCH} https://x-access-token:${TOKEN}@github.com/percona/percona-server-private-build.git percona-server-private-build
                  mv -f \${build_dir}/percona-server-private-build/build-ps \${build_dir}/test/.
              fi
              if [ -f ./test/percona-server-8.0.properties ]; then
                  . ./test/percona-server-8.0.properties
              fi
              sudo bash -x ./ps_builder.sh --builddir=\${build_dir}/test --install_deps=1
              if [ ${BUILD_TOKUDB_TOKUBACKUP} = "ON" ]; then
                  bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --build_tokudb_tokubackup=1 --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
              else
                  bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
              fi
          else
              docker run -u root --shm-size=16g --cap-add=SYS_NICE -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
                  set -o xtrace
                  free -h
                  cd \${build_dir}
                  if [ \${FIPSMODE} = "YES" ]; then
                      git clone --depth 1 --branch \${PRO_BRANCH} https://x-access-token:${TOKEN}@github.com/percona/percona-server-private-build.git percona-server-private-build
                      mv -f \${build_dir}/percona-server-private-build/build-ps \${build_dir}/test/.
                  fi
                  if [ -f ./test/percona-server-8.0.properties ]; then
                      . ./test/percona-server-8.0.properties
                  fi
                  bash -x ./ps_builder.sh --builddir=\${build_dir}/test --install_deps=1
                  if [ ${BUILD_TOKUDB_TOKUBACKUP} = \"ON\" ]; then
                      bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --build_tokudb_tokubackup=1 --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
                  else
                      bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
                  fi"
          fi
      """
    }
}

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
/*
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
*/
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
/*
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
*/
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
            agentLabel = cloud == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
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
                    if (cloud == 'Hetzner') {
                        installCli("deb")
                    } else {
                        installCli("rpm")
                    }
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
