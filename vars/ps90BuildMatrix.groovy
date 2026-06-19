/* groovylint-disable DuplicateStringLiteral, GStringExpressionWithinString, LineLength */

void cleanUpWS() {
    sh 'sudo rm -rf ./*'
}

void installCli(String PLATFORM) {
    sh """
        if [ \${CLOUD} = "AWS" ]; then
            set -o xtrace
            if [ -d aws ]; then
                rm -rf aws
            fi
            if [ ${PLATFORM} = "deb" ]; then
                sudo apt-get update
                sudo apt-get -y install wget curl unzip
            elif [ ${PLATFORM} = "rpm" ]; then
                export RHVER=\$(rpm --eval %rhel)
                if [ \${RHVER} = "7" ]; then
                    sudo sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-* || true
                    sudo sed -i 's|#\\s*baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-* || true
                fi
                sudo yum -y install wget curl unzip
            fi
            curl https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o awscliv2.zip
            unzip awscliv2.zip
            sudo ./aws/install || true
       fi
    """
}

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        mkdir -p test
        wget \$(echo ${env.GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${env.BRANCH}/build-ps/percona-server-9.0_builder.sh -O ps_builder.sh || curl \$(echo ${env.GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${env.BRANCH}/build-ps/percona-server-9.0_builder.sh -o ps_builder.sh
        export build_dir=\$(pwd -P)
        if [ "${DOCKER_OS}" = "none" ]; then
            set -o xtrace
            cd \${build_dir}
            if [ -f ./test/percona-server-9.0.properties ]; then
                . ./test/percona-server-9.0.properties
            fi
            sudo bash -x ./ps_builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${env.GIT_REPO} --branch=${env.BRANCH} --rpm_release=${env.RPM_RELEASE} --deb_release=${env.DEB_RELEASE} ${STAGE_PARAM}
        else
            docker run -u root --shm-size=16g --cap-add=SYS_NICE -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
                set -o xtrace
                cd \${build_dir}
                if [ -f ./test/percona-server-9.0.properties ]; then
                    . ./test/percona-server-9.0.properties
                fi
                bash -x ./ps_builder.sh --builddir=\${build_dir}/test --install_deps=1
                bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${env.GIT_REPO} --branch=${env.BRANCH} --rpm_release=${env.RPM_RELEASE} --deb_release=${env.DEB_RELEASE} ${STAGE_PARAM}"
        fi
    """
}

def call(Map args) {
    def cloud            = args.cloud
    def awsStashPath     = args.awsStashPath
    def fipsMode         = args.fipsMode
    def experimentalMode = args.experimentalMode
    def onlyStages       = args.get('onlyStages', [])

    def shouldRun = { String name -> !onlyStages || onlyStages.contains(name) }

    def stagesMap = [:]

    // ── RPM stages ────────────────────────────────────────────────────────────

    stagesMap['Oracle Linux 8'] = {
        stage('Oracle Linux 8') {
            if (!shouldRun('Oracle Linux 8')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-x64' : 'docker-32gb') {
                if (fipsMode == 'YES') {
                    echo 'The step is skipped'
                } else {
                    cleanUpWS()
                    installCli('rpm')
                    unstash 'properties'
                    popArtifactFolder(cloud, 'srpm/', awsStashPath)
                    buildStage('oraclelinux:8', '--build_rpm=1')
                    if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'rpm/', awsStashPath) }
                }
            }
        }
    }

    stagesMap['Oracle Linux 8 ARM'] = {
        stage('Oracle Linux 8 ARM') {
            if (!shouldRun('Oracle Linux 8 ARM')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64') {
                if (fipsMode == 'YES') {
                    echo 'The step is skipped'
                } else {
                    cleanUpWS()
                    installCli('rpm')
                    unstash 'properties'
                    popArtifactFolder(cloud, 'srpm/', awsStashPath)
                    buildStage('oraclelinux:8', '--build_rpm=1')
                    if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'rpm/', awsStashPath) }
                }
            }
        }
    }

    stagesMap['Oracle Linux 9'] = {
        stage('Oracle Linux 9') {
            if (!shouldRun('Oracle Linux 9')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-x64' : 'docker-32gb') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'srpm/', awsStashPath)
                if (fipsMode == 'YES') {
                    buildStage('oraclelinux:9', '--build_rpm=1 --with_zenfs=1 --enable_fipsmode=1')
                } else {
                    buildStage('oraclelinux:9', '--build_rpm=1 --with_zenfs=1')
                }
                if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'rpm/', awsStashPath) }
            }
        }
    }

    stagesMap['Oracle Linux 9 ARM'] = {
        stage('Oracle Linux 9 ARM') {
            if (!shouldRun('Oracle Linux 9 ARM')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'srpm/', awsStashPath)
                if (fipsMode == 'YES') {
                    buildStage('oraclelinux:9', '--build_rpm=1 --enable_fipsmode=1')
                } else {
                    buildStage('oraclelinux:9', '--build_rpm=1')
                }
                if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'rpm/', awsStashPath) }
            }
        }
    }

    stagesMap['Amazon Linux 2023'] = {
        stage('Amazon Linux 2023') {
            if (!shouldRun('Amazon Linux 2023')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-x64' : 'docker-32gb') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'srpm/', awsStashPath)
                buildStage('amazonlinux:2023', '--build_rpm=1')
                if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'rpm/', awsStashPath) }
            }
        }
    }

    stagesMap['Amazon Linux 2023 ARM'] = {
        stage('Amazon Linux 2023 ARM') {
            if (!shouldRun('Amazon Linux 2023 ARM')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'srpm/', awsStashPath)
                buildStage('amazonlinux:2023', '--build_rpm=1')
                if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'rpm/', awsStashPath) }
            }
        }
    }

    stagesMap['Oracle Linux 10'] = {
        stage('Oracle Linux 10') {
            if (!shouldRun('Oracle Linux 10')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-x64' : 'docker-32gb') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'srpm/', awsStashPath)
                if (fipsMode == 'YES') {
                    buildStage('oraclelinux:10', '--build_rpm=1 --with_zenfs=1 --enable_fipsmode=1')
                } else {
                    buildStage('oraclelinux:10', '--build_rpm=1 --with_zenfs=1')
                }
                if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'rpm/', awsStashPath) }
            }
        }
    }

    stagesMap['Oracle Linux 10 ARM'] = {
        stage('Oracle Linux 10 ARM') {
            if (!shouldRun('Oracle Linux 10 ARM')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'srpm/', awsStashPath)
                if (fipsMode == 'YES') {
                    buildStage('oraclelinux:10', '--build_rpm=1 --enable_fipsmode=1')
                } else {
                    buildStage('oraclelinux:10', '--build_rpm=1')
                }
                if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'rpm/', awsStashPath) }
            }
        }
    }

    // ── DEB stages ────────────────────────────────────────────────────────────

    stagesMap['Ubuntu Jammy(22.04)'] = {
        stage('Ubuntu Jammy(22.04)') {
            if (!shouldRun('Ubuntu Jammy(22.04)')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-x64' : 'docker-32gb') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'source_deb/', awsStashPath)
                if (fipsMode == 'YES') {
                    buildStage('ubuntu:jammy', '--build_deb=1 --with_zenfs=1 --enable_fipsmode=1')
                } else {
                    buildStage('ubuntu:jammy', '--build_deb=1 --with_zenfs=1')
                }
                if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'deb/', awsStashPath) }
            }
        }
    }

    stagesMap['Ubuntu Noble(24.04)'] = {
        stage('Ubuntu Noble(24.04)') {
            if (!shouldRun('Ubuntu Noble(24.04)')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-x64' : 'docker-32gb') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'source_deb/', awsStashPath)
                if (fipsMode == 'YES') {
                    buildStage('ubuntu:noble', '--build_deb=1 --with_zenfs=1 --enable_fipsmode=1')
                } else {
                    buildStage('ubuntu:noble', '--build_deb=1 --with_zenfs=1')
                }
                pushArtifactFolder(cloud, 'deb/', awsStashPath)
            }
        }
    }

    stagesMap['Ubuntu Resolute(26.04)'] = {
        stage('Ubuntu Resolute(26.04)') {
            if (!shouldRun('Ubuntu Resolute(26.04)')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-x64' : 'docker-32gb') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'source_deb/', awsStashPath)
                if (fipsMode == 'YES') {
                    buildStage('ubuntu:resolute', '--build_deb=1 --with_zenfs=1 --enable_fipsmode=1')
                } else {
                    buildStage('ubuntu:resolute', '--build_deb=1 --with_zenfs=1')
                }
                pushArtifactFolder(cloud, 'deb/', awsStashPath)
            }
        }
    }

    stagesMap['Debian Bookworm(12)'] = {
        stage('Debian Bookworm(12)') {
            if (!shouldRun('Debian Bookworm(12)')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-x64' : 'docker-32gb') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'source_deb/', awsStashPath)
                if (fipsMode == 'YES') {
                    buildStage('debian:bookworm', '--build_deb=1 --with_zenfs=1 --enable_fipsmode=1')
                } else {
                    buildStage('debian:bookworm', '--build_deb=1 --with_zenfs=1')
                }
                if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'deb/', awsStashPath) }
            }
        }
    }

    stagesMap['Debian Trixie(13)'] = {
        stage('Debian Trixie(13)') {
            if (!shouldRun('Debian Trixie(13)')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-x64' : 'docker-32gb') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'source_deb/', awsStashPath)
                if (fipsMode == 'YES') {
                    buildStage('debian:trixie', '--build_deb=1 --with_zenfs=1 --enable_fipsmode=1')
                } else {
                    buildStage('debian:trixie', '--build_deb=1 --with_zenfs=1')
                }
                if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'deb/', awsStashPath) }
            }
        }
    }

    stagesMap['Ubuntu Jammy(22.04) ARM'] = {
        stage('Ubuntu Jammy(22.04) ARM') {
            if (!shouldRun('Ubuntu Jammy(22.04) ARM')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'source_deb/', awsStashPath)
                if (fipsMode == 'YES') {
                    buildStage('ubuntu:jammy', '--build_deb=1 --with_zenfs=1 --enable_fipsmode=1')
                } else {
                    buildStage('ubuntu:jammy', '--build_deb=1 --with_zenfs=1')
                }
                if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'deb/', awsStashPath) }
            }
        }
    }

    stagesMap['Ubuntu Noble(24.04) ARM'] = {
        stage('Ubuntu Noble(24.04) ARM') {
            if (!shouldRun('Ubuntu Noble(24.04) ARM')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'source_deb/', awsStashPath)
                if (fipsMode == 'YES') {
                    buildStage('ubuntu:noble', '--build_deb=1 --with_zenfs=1 --enable_fipsmode=1')
                } else {
                    buildStage('ubuntu:noble', '--build_deb=1 --with_zenfs=1')
                }
                pushArtifactFolder(cloud, 'deb/', awsStashPath)
            }
        }
    }

    stagesMap['Ubuntu Resolute(26.04) ARM'] = {
        stage('Ubuntu Resolute(26.04) ARM') {
            if (!shouldRun('Ubuntu Resolute(26.04) ARM')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'source_deb/', awsStashPath)
                if (fipsMode == 'YES') {
                    buildStage('ubuntu:resolute', '--build_deb=1 --with_zenfs=1 --enable_fipsmode=1')
                } else {
                    buildStage('ubuntu:resolute', '--build_deb=1 --with_zenfs=1')
                }
                pushArtifactFolder(cloud, 'deb/', awsStashPath)
            }
        }
    }

    stagesMap['Debian Bookworm(12) ARM'] = {
        stage('Debian Bookworm(12) ARM') {
            if (!shouldRun('Debian Bookworm(12) ARM')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'source_deb/', awsStashPath)
                if (fipsMode == 'YES') {
                    buildStage('debian:bookworm', '--build_deb=1 --with_zenfs=1 --enable_fipsmode=1')
                } else {
                    buildStage('debian:bookworm', '--build_deb=1 --with_zenfs=1')
                }
                if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'deb/', awsStashPath) }
            }
        }
    }

    stagesMap['Debian Trixie(13) ARM'] = {
        stage('Debian Trixie(13) ARM') {
            if (!shouldRun('Debian Trixie(13) ARM')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'source_deb/', awsStashPath)
                if (fipsMode == 'YES') {
                    buildStage('debian:trixie', '--build_deb=1 --with_zenfs=1 --enable_fipsmode=1')
                } else {
                    buildStage('debian:trixie', '--build_deb=1 --with_zenfs=1')
                }
                if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'deb/', awsStashPath) }
            }
        }
    }

    // ── Tarball stages ────────────────────────────────────────────────────────

    stagesMap['Oracle Linux 8 binary tarball'] = {
        stage('Oracle Linux 8 binary tarball') {
            if (!shouldRun('Oracle Linux 8 binary tarball')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-x64' : 'docker-32gb') {
                if (fipsMode == 'YES') {
                    echo 'The step is skipped'
                } else {
                    cleanUpWS()
                    installCli('rpm')
                    unstash 'properties'
                    popArtifactFolder(cloud, 'source_tarball/', awsStashPath)
                    buildStage('oraclelinux:8', '--build_tarball=1')
                    if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'tarball/', awsStashPath) }
                }
            }
        }
    }

    stagesMap['Oracle Linux 8 debug tarball'] = {
        stage('Oracle Linux 8 debug tarball') {
            if (!shouldRun('Oracle Linux 8 debug tarball')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-x64' : 'docker-32gb') {
                if (fipsMode == 'YES') {
                    echo 'The step is skipped'
                } else {
                    cleanUpWS()
                    installCli('rpm')
                    unstash 'properties'
                    popArtifactFolder(cloud, 'source_tarball/', awsStashPath)
                    buildStage('oraclelinux:8', '--debug=1 --build_tarball=1')
                    if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'tarball/', awsStashPath) }
                }
            }
        }
    }

    stagesMap['Oracle Linux 9 tarball'] = {
        stage('Oracle Linux 9 tarball') {
            if (!shouldRun('Oracle Linux 9 tarball')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-x64' : 'docker-32gb') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'source_tarball/', awsStashPath)
                if (fipsMode == 'YES') {
                    buildStage('oraclelinux:9', '--build_tarball=1 --enable_fipsmode=1')
                } else {
                    buildStage('oraclelinux:9', '--build_tarball=1')
                }
                if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'tarball/', awsStashPath) }
            }
        }
    }

    stagesMap['Oracle Linux 9 ZenFS tarball'] = {
        stage('Oracle Linux 9 ZenFS tarball') {
            if (!shouldRun('Oracle Linux 9 ZenFS tarball')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-x64' : 'docker-32gb') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'source_tarball/', awsStashPath)
                if (fipsMode == 'YES') {
                    echo 'The step is skipped'
                } else {
                    buildStage('oraclelinux:9', '--build_tarball=1 --with_zenfs=1')
                    if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'tarball/', awsStashPath) }
                }
            }
        }
    }

    stagesMap['Oracle Linux 9 debug tarball'] = {
        stage('Oracle Linux 9 debug tarball') {
            if (!shouldRun('Oracle Linux 9 debug tarball')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-x64' : 'docker-32gb') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'source_tarball/', awsStashPath)
                if (fipsMode == 'YES') {
                    buildStage('oraclelinux:9', '--debug=1 --build_tarball=1 --enable_fipsmode=1')
                } else {
                    buildStage('oraclelinux:9', '--debug=1 --build_tarball=1')
                }
                if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'tarball/', awsStashPath) }
            }
        }
    }

    stagesMap['Ubuntu Jammy(22.04) tarball'] = {
        stage('Ubuntu Jammy(22.04) tarball') {
            if (!shouldRun('Ubuntu Jammy(22.04) tarball')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-x64' : 'docker-32gb') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'source_tarball/', awsStashPath)
                if (fipsMode == 'YES') {
                    buildStage('ubuntu:jammy', '--build_tarball=1 --enable_fipsmode=1')
                } else {
                    buildStage('ubuntu:jammy', '--build_tarball=1')
                }
                if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'tarball/', awsStashPath) }
            }
        }
    }

    stagesMap['Ubuntu Jammy(22.04) ZenFS tarball'] = {
        stage('Ubuntu Jammy(22.04) ZenFS tarball') {
            if (!shouldRun('Ubuntu Jammy(22.04) ZenFS tarball')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-x64' : 'docker-32gb') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'source_tarball/', awsStashPath)
                if (fipsMode == 'YES') {
                    echo 'The step is skipped'
                } else {
                    buildStage('ubuntu:jammy', '--build_tarball=1 --with_zenfs=1')
                    if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'tarball/', awsStashPath) }
                }
            }
        }
    }

    stagesMap['Ubuntu Jammy(22.04) debug tarball'] = {
        stage('Ubuntu Jammy(22.04) debug tarball') {
            if (!shouldRun('Ubuntu Jammy(22.04) debug tarball')) {
                echo 'Skipped: not in BUILD_STAGES filter'
                return
            }
            node(cloud == 'Hetzner' ? 'docker-x64' : 'docker-32gb') {
                cleanUpWS()
                installCli('rpm')
                unstash 'properties'
                popArtifactFolder(cloud, 'source_tarball/', awsStashPath)
                if (fipsMode == 'YES') {
                    buildStage('ubuntu:jammy', '--debug=1 --build_tarball=1 --enable_fipsmode=1')
                } else {
                    buildStage('ubuntu:jammy', '--debug=1 --build_tarball=1')
                }
                if (experimentalMode == 'NO') { pushArtifactFolder(cloud, 'tarball/', awsStashPath) }
            }
        }
    }

    parallel stagesMap
}
