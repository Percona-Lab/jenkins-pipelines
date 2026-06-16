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

    if (shouldRun('Oracle Linux 8')) {
        stagesMap['Oracle Linux 8'] = {
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

    if (shouldRun('Oracle Linux 8 ARM')) {
        stagesMap['Oracle Linux 8 ARM'] = {
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

    if (shouldRun('Oracle Linux 9')) {
        stagesMap['Oracle Linux 9'] = {
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

    if (shouldRun('Oracle Linux 9 ARM')) {
        stagesMap['Oracle Linux 9 ARM'] = {
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

    if (shouldRun('Amazon Linux 2023')) {
        stagesMap['Amazon Linux 2023'] = {
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

    if (shouldRun('Amazon Linux 2023 ARM')) {
        stagesMap['Amazon Linux 2023 ARM'] = {
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

    if (shouldRun('Oracle Linux 10')) {
        stagesMap['Oracle Linux 10'] = {
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

    if (shouldRun('Oracle Linux 10 ARM')) {
        stagesMap['Oracle Linux 10 ARM'] = {
            node(cloud == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64') {
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

    if (shouldRun('Ubuntu Jammy(22.04)')) {
        stagesMap['Ubuntu Jammy(22.04)'] = {
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

    if (shouldRun('Ubuntu Noble(24.04)')) {
        stagesMap['Ubuntu Noble(24.04)'] = {
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

    if (shouldRun('Ubuntu Resolute(26.04)')) {
        stagesMap['Ubuntu Resolute(26.04)'] = {
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

    if (shouldRun('Debian Bookworm(12)')) {
        stagesMap['Debian Bookworm(12)'] = {
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

    if (shouldRun('Debian Trixie(13)')) {
        stagesMap['Debian Trixie(13)'] = {
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

    if (shouldRun('Ubuntu Jammy(22.04) ARM')) {
        stagesMap['Ubuntu Jammy(22.04) ARM'] = {
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

    if (shouldRun('Ubuntu Noble(24.04) ARM')) {
        stagesMap['Ubuntu Noble(24.04) ARM'] = {
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

    if (shouldRun('Ubuntu Resolute(26.04) ARM')) {
        stagesMap['Ubuntu Resolute(26.04) ARM'] = {
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

    if (shouldRun('Debian Bookworm(12) ARM')) {
        stagesMap['Debian Bookworm(12) ARM'] = {
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

    if (shouldRun('Debian Trixie(13) ARM')) {
        stagesMap['Debian Trixie(13) ARM'] = {
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

    if (shouldRun('Oracle Linux 8 binary tarball')) {
        stagesMap['Oracle Linux 8 binary tarball'] = {
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

    if (shouldRun('Oracle Linux 8 debug tarball')) {
        stagesMap['Oracle Linux 8 debug tarball'] = {
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

    if (shouldRun('Oracle Linux 9 tarball')) {
        stagesMap['Oracle Linux 9 tarball'] = {
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

    if (shouldRun('Oracle Linux 9 ZenFS tarball')) {
        stagesMap['Oracle Linux 9 ZenFS tarball'] = {
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

    if (shouldRun('Oracle Linux 9 debug tarball')) {
        stagesMap['Oracle Linux 9 debug tarball'] = {
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

    if (shouldRun('Ubuntu Jammy(22.04) tarball')) {
        stagesMap['Ubuntu Jammy(22.04) tarball'] = {
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

    if (shouldRun('Ubuntu Jammy(22.04) ZenFS tarball')) {
        stagesMap['Ubuntu Jammy(22.04) ZenFS tarball'] = {
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

    if (shouldRun('Ubuntu Jammy(22.04) debug tarball')) {
        stagesMap['Ubuntu Jammy(22.04) debug tarball'] = {
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
