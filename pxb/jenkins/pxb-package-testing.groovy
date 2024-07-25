library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

product_action_playbooks = [
    pxb80: [
        install: 'pxb_80.yml',
        upgrade: 'pxb_80_upgrade.yml',
        upstream: 'pxb_80_upstream.yml',
        tarball: 'pxb_80_tarball.yml',
        kmip: 'pxb_80_kmip.yml'
    ],
    pxb24: [
        install: 'pxb_24.yml',
        upgrade: 'pxb_24_upgrade.yml',
        upstream: 'pxb_24_upstream.yml',
        tarball: 'pxb_24_tarball.yml'
    ],
    pxb81: [
        install: 'pxb_81.yml',
        upgrade: 'pxb_81_upgrade.yml',
        upstream: 'pxb_81_upstream.yml',
        tarball: 'pxb_81_tarball.yml',
        kmip: 'pxb_81_kmip.yml'
    ],
    pxb_innovation_lts: [
        install: 'pxb_innovation_lts.yml',
        upgrade: 'pxb_upgrade_innovation_lts.yml',
        upstream: 'pxb_upstream_innovation_lts.yml',
        tarball: 'pxb_tarball_innovation_lts.yml',
        kmip: 'pxb_kmip_innovation.yml',
        kms: 'pxb_kms_innovation.yml'
    ]

]

setup_centos_package_tests = { ->
    sh '''
        sudo yum install -y epel-release
        sudo yum -y update
        sudo yum install -y ansible zip
    '''
}

setup_oracle8_package_tests = { ->
    sh '''
        sudo yum install -y epel-release
        sudo yum -y update
        sudo yum install -y ansible-2.9.27
        sudo yum install -y tar zip
    '''
}

setup_oracle9_package_tests = { ->
    sh '''
        sudo yum install -y epel-release
        sudo yum -y update
        sudo yum install -y ansible
        sudo yum install -y tar zip
    '''
}

setup_buster_package_tests = { ->
    sh '''
        sudo apt-get update
        sudo apt-get install -y ansible zip
    '''
}

setup_bullseye_package_tests = { ->
    sh '''
        sudo apt-get update
        sudo apt-get install -y ansible zip
    '''
}

setup_ubuntu_package_tests = { ->
    sh '''
        sudo apt-get update
        sudo apt-get install -y software-properties-common
        sudo apt-add-repository --yes --update ppa:ansible/ansible
        sudo apt-get install -y ansible zip
    '''
}

setup_ubuntu_jammy_package_tests = { ->
    sh '''
        sudo apt-get update
        sudo apt-get install -y software-properties-common
        sudo apt-get install -y python3 python3-pip zip
        echo $PATH
        python3 -m pip install --user ansible==2.9.27
        ~/.local/bin/ansible --version
        sudo ln -s ~/.local/bin/ansible /usr/bin/ansible
        sudo ln -s ~/.local/bin/ansible-playbook /usr/bin/ansible-playbook
    '''
}


node_setups = [
    "min-buster-x64": setup_buster_package_tests,
    "min-bullseye-x64": setup_bullseye_package_tests,
    "min-bookworm-x64": setup_bullseye_package_tests,
    "min-centos-7-x64": setup_centos_package_tests,
    "min-ol-8-x64": setup_oracle8_package_tests,
    "min-ol-9-x64": setup_oracle9_package_tests,
    "min-bionic-x64": setup_ubuntu_package_tests,
    "min-focal-x64": setup_ubuntu_package_tests,
    "min-jammy-x64": setup_ubuntu_jammy_package_tests
]

void setup_package_tests() {
    node_setups[params.node_to_test]()
}

void runPlaybook(String action_to_test, String stt) {
    def playbook = product_action_playbooks[params.product_to_test][action_to_test]
    def playbook_path = "package-testing/playbooks/${playbook}"
    def git_repo = params.git_repo

    sh """
        git clone --depth 1 -b master "${git_repo}"
    """

    setup_package_tests()

    sh """
        export install_repo="\${install_repo}"
        export server_to_test="${stt}"

        ansible-playbook \
         -vvv \
        --connection=local \
        --inventory 127.0.0.1, \
        --limit 127.0.0.1 \
        ${playbook_path}
    """
}

void zipthelogs(String node_to_test, String action_to_test, String stt) {
    sh """
        sudo zip -r "${node_to_test}-${action_to_test}-${stt}"-logs.zip /var/log/*
    """
}


pipeline {
    agent none

    parameters {
        choice(
            choices: ['pxb81', 'pxb80', 'pxb24', 'pxb_innovation_lts'],
            description: 'Choose the product version to test: PXB8.1, PXB8.0, PXB2.4 OR pxb_innovation_lts',
            name: 'product_to_test'
        )
        choice(
            choices: [
                'min-centos-7-x64',
                'min-ol-8-x64',
                'min-ol-9-x64',
                'min-bionic-x64',
                'min-focal-x64',
                'min-jammy-x64',
                'min-buster-x64',
                'min-bullseye-x64',
                'min-bookworm-x64'
            ],
            description: 'Node to run tests',
            name: 'node_to_test'
        )
        choice(
            choices: ['testing', 'main', 'experimental'],
            description: 'Choose the repo to install packages and run the tests',
            name: 'install_repo'
        )
        string(
            defaultValue: 'https://github.com/Percona-QA/package-testing.git',
            description: 'repo name',
            name: 'git_repo',
            trim: false
        )
        choice(
            choices: [
                'ps_innovation_lts',
                'ms_innovation_lts',
                'all',
            ],
            description: 'Server to test',
            name: 'server_to_test'
        )
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '15'))
    }

    stages {
        stage("Prepare") {
            steps {
                script {
                    currentBuild.displayName = "#${BUILD_NUMBER}-${params.product_to_test}-${params.install_repo}-${params.node_to_test}"
                }
            }
        }

        stage('Run parallel') {
            parallel {

                stage('PS INSTALL') {
                    agent {
                        label params.node_to_test
                    }

                    steps {
                        script{
                            boolean success = true
                            if (params.server_to_test == 'all' || params.server_to_test == 'ps_innovation_lts') {
                                try {
                                    runPlaybook("install","ps_innovation_lts")
                                } catch (Exception e) {
                                    echo "An error occurred in runPlaybook: ${e.getMessage()}"
                                    success = false
                                } finally {
                                    zipthelogs(params.node_to_test, "install", "ps_innovation_lts")
                                    archiveArtifacts artifacts: '*.zip' , followSymlinks: false
                                }
                                if (!success) {
                                    
                                    error "Stage encountered errors"
                                    
                                }
                            } else {
                                echo 'PS is not Selected so not running'
                            }
                        }
                    }
                }

                stage('MS INSTALL') {
                    agent {
                        label params.node_to_test
                    }

                    steps {
                        script{
                            boolean success = true
                            if (params.server_to_test == 'all' || params.server_to_test == 'ms_innovation_lts') {
                                try {
                                    runPlaybook("install","ms_innovation_lts")
                                }
                                catch (Exception e) {
                                    echo "An error occurred in runPlaybook: ${e.getMessage()}"
                                    success = false
                                }
                                finally {
                                    zipthelogs(params.node_to_test, "install", "ms_innovation_lts")
                                    archiveArtifacts artifacts: '*.zip' , followSymlinks: false
                                }
                                if (!success) {
                                    
                                    error "Stage encountered errors"
                                    
                                }
                            } else {
                                echo 'MS is not Selected so not running'
                            }
                        }
                    }
                }


                stage('PS UPGRADE') {
                    agent {
                        label params.node_to_test
                    }

                    steps {
                        script{
                            boolean success = true
                            if (params.server_to_test == 'all' || params.server_to_test == 'ps_innovation_lts') {
                                try {
                                    runPlaybook("upgrade","ps_innovation_lts")
                                } catch (Exception e) {
                                    echo "An error occurred in runPlaybook: ${e.getMessage()}"
                                    success = false
                                } finally {
                                    zipthelogs(params.node_to_test, "upgrade", "ps_innovation_lts")
                                    archiveArtifacts artifacts: '*.zip' , followSymlinks: false
                                }
                                if (!success) {
                                    
                                    error "Stage encountered errors"
                                    
                                }
                            } else {
                                echo 'PS is not Selected so not running'
                            }
                        }
                    }
                }

                stage('MS UPGRADE') {
                    agent {
                        label params.node_to_test
                    }

                    steps {
                        script{
                            boolean success = true
                            if (params.server_to_test == 'all' || params.server_to_test == 'ms_innovation_lts') {
                                try {
                                    runPlaybook("upgrade","ms_innovation_lts")
                                } catch (Exception e) {
                                    echo "An error occurred in runPlaybook: ${e.getMessage()}"
                                    success = false
                                } finally {
                                    zipthelogs(params.node_to_test, "upgrade", "ms_innovation_lts")
                                    archiveArtifacts artifacts: '*.zip' , followSymlinks: false
                                }
                                if (!success) {
                                    
                                    error "Stage encountered errors"
                                    
                                }
                            } else {
                                echo 'MS is not Selected so not running'
                            }
                        }
                    }
                }

                stage('PS TARBALL') {
                    agent {
                        label params.node_to_test
                    }

                    steps {
                        script{
                            boolean success = true
                            if (params.server_to_test == 'all' || params.server_to_test == 'ps_innovation_lts') {
                                try {
                                    runPlaybook("tarball","ps_innovation_lts")
                                } catch (Exception e) {
                                    echo "An error occurred in runPlaybook: ${e.getMessage()}"
                                    success = false
                                } finally {
                                    zipthelogs(params.node_to_test, "tarball", "ps_innovation_lts")
                                    archiveArtifacts artifacts: '*.zip' , followSymlinks: false
                                }
                                if (!success) {
                                    
                                    error "Stage encountered errors"
                                    
                                }
                            } else {
                                echo 'PS is not Selected so not running'
                            }
                        }
                    }
                }

                stage('MS TARBALL') {
                    agent {
                        label params.node_to_test
                    }

                    steps {
                        script{
                            boolean success = true
                            if (params.server_to_test == 'all' || params.server_to_test == 'ms_innovation_lts') {
                                try {
                                    runPlaybook("tarball","ms_innovation_lts")
                                } catch (Exception e) {
                                    echo "An error occurred in runPlaybook: ${e.getMessage()}"
                                    success = false
                                } finally {
                                    zipthelogs(params.node_to_test, "tarball", "ms_innovation_lts")
                                    archiveArtifacts artifacts: '*.zip' , followSymlinks: false
                                }
                                if (!success) {
                                    
                                    error "Stage encountered errors"
                                    
                                }
                            } else {
                                echo 'MS is not Selected so not running'
                            }
                        }
                    }
                }

                stage('PS KMIP') {
                    agent {
                        label params.node_to_test
                    }

                    steps {
                        script{
                            boolean success = true
                            if (params.server_to_test == 'all' || params.server_to_test == 'ps_innovation_lts') {
                                try {
                                    runPlaybook("kmip","ps_innovation_lts")
                                } catch (Exception e) {
                                    echo "An error occurred in runPlaybook: ${e.getMessage()}"
                                    success = false
                                } finally {
                                    zipthelogs(params.node_to_test, "kmip", "ps_innovation_lts")
                                    archiveArtifacts artifacts: '*.zip' , followSymlinks: false
                                }
                                if (!success) {
                                    
                                    error "Stage encountered errors"
                                    
                                }
                            } else {
                                echo 'PS is not Selected so not running'
                            }
                        }
                    }
                }

                stage('MS KMIP') {
                    agent {
                        label params.node_to_test
                    }

                    steps {
                        script{
                            boolean success = true
                            if (params.server_to_test == 'all' || params.server_to_test == 'ms_innovation_lts') {
                                try {
                                    runPlaybook("kmip","ms_innovation_lts")
                                } catch (Exception e) {
                                    echo "An error occurred in runPlaybook: ${e.getMessage()}"
                                    success = false
                                } finally {
                                    zipthelogs(params.node_to_test, "kmip", "ms_innovation_lts")
                                    archiveArtifacts artifacts: '*.zip' , followSymlinks: false
                                }
                                if (!success) {
                                    
                                    error "Stage encountered errors"
                                    
                                }
                            } else {
                                echo 'MS is not Selected so not running'
                            }
                        }
                    }
                }

                stage('PS kms') {
                    agent {
                        label params.node_to_test
                    }

                    steps {
                        script{
                            boolean success = true
                            if (params.server_to_test == 'all' || params.server_to_test == 'ps_innovation_lts') {
                                try {
                                    runPlaybook("kms","ps_innovation_lts")
                                } catch (Exception e) {
                                    echo "An error occurred in runPlaybook: ${e.getMessage()}"
                                    success = false
                                } finally {
                                    zipthelogs(params.node_to_test, "kms", "ps_innovation_lts")
                                    archiveArtifacts artifacts: '*.zip' , followSymlinks: false
                                }
                                if (!success) {
                                    error "Stage encountered errors"
                                }
                            } else {
                                echo 'PS is not Selected so not running'
                            }
                        }
                    }
                }

            }
        }
    }
}
