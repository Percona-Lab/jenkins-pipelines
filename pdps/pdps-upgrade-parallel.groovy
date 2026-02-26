library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def extractMajorVersion(version) {
    def parts = version.split("\\.")
    return parts[0] + parts[1]
}

def pdps_80_operating_systems() {
    return [
        'oracle-8', 'oracle-9', 'rhel-8', 'rhel-9', 'debian-11', 'debian-12', 'ubuntu-jammy', 'ubuntu-noble'
    ]
}

def pdps_84_operating_systems() {
    return [
        'oracle-8', 'oracle-9', 'rhel-8', 'rhel-9', 'rhel-10', 'debian-11', 'debian-12', 'debian-13', 'ubuntu-jammy', 'ubuntu-noble', 'al-2023'
    ]
}

List allOS = pdps_80_operating_systems() + pdps_84_operating_systems()

def moleculeParallelTestALL(allOS, operatingSystems, moleculeDir) {
    def tests = [:]
    allOS.each { os ->
        tests["${os}"] = {
            stage("${os}") {
                if (operatingSystems.contains(os)) {
                    sh """
                        . ~/virtenv/bin/activate
                        cd ${moleculeDir}
                        molecule test -s ${os}
                    """
                } else {
                    echo "Skipping ${os} as it's not in operatingSystems"
                }
            }
        }
    }
    parallel tests
}

def moleculeParallelPostDestroyALL(allOS, operatingSystems, moleculeDir) {
    def posts = [:]
    allOS.each { os ->
        posts["${os}"] = {
            if (operatingSystems.contains(os)) {
                sh """
                    . ~/virtenv/bin/activate
                    cd ${moleculeDir}
                    molecule destroy -s ${os}
                """
            } else {
                echo "Skipping destroy for ${os} as it's not in operatingSystems"
            }
        }
    }
    parallel posts
}

pipeline {
    agent {
        label 'min-bookworm-x64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
        MOLECULE_DIR = "~/package-testing/molecule/pdmysql/pdps_minor_upgrade";
    }
    parameters {
            choice(
                name: 'FROM_REPO',
                description: 'From this repo will be upgraded PDPS (for minor version).',
                choices: [
                    'release',
                    'testing',
                    'experimental'
                ]
            )
            choice(
                name: 'TO_REPO',
                description: 'Repo for testing',
                choices: [
                    'testing',
                    'release',
                    'experimental'
                ]
            )
            string(
                defaultValue: '8.0.32-24',
                description: 'From this version pdmysql will be updated. Possible values are with and without percona release: 8.0.31 OR 8.0.31-23',
                name: 'FROM_VERSION'
            )
            string(
                defaultValue: '8.0.33-25',
                description: 'To this version pdmysql will be updated. Possible values are with and without percona release and build: 8.0.32, 8.0.32-24 OR 8.0.32-24.2',
                name: 'VERSION'
            )
            string(
                defaultValue: '',
                description: 'Percona Server revision for test after update. Empty by default (not checked).',
                name: 'PS_REVISION'
            )
            string(
                defaultValue: '2.5.1',
                description: 'Updated Proxysql version',
                name: 'PROXYSQL_VERSION'
            )
            string(
                defaultValue: '8.0.33-27',
                description: 'Updated PXB version. Possible values are with and without percona release and build: 8.0.32, 8.0.32-25 OR 8.0.32-25.1',
                name: 'PXB_VERSION'
            )
            string(
                defaultValue: '3.5.3',
                description: 'Updated Percona Toolkit version',
                name: 'PT_VERSION'
            )
            string(
                defaultValue: '3.2.6-9',
                description: 'Updated Percona Orchestrator version',
                name: 'ORCHESTRATOR_VERSION'
            )
            string(
                defaultValue: '',
                description: 'Orchestrator revision for version from https://github.com/percona/orchestrator . Empty by default (not checked).',
                name: 'ORCHESTRATOR_REVISION'
            )
            string(
                defaultValue: 'master',
                description: 'Branch for testing repository',
                name: 'TESTING_BRANCH'
            )
            string(
                defaultValue: 'Percona-QA',
                description: 'Git account for package-testing repository',
                name: 'TESTING_GIT_ACCOUNT'
            )
    }
    options {
            withCredentials(moleculePdpsJenkinsCreds())
            disableConcurrentBuilds()
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.VERSION}"
                    currentBuild.description = "From: ${env.FROM_VERSION} ${env.FROM_REPO}; to: ${env.VERSION} ${env.TO_REPO}. Git br: ${env.TESTING_BRANCH}"
                }
            }
        }

        stage ('Prepare') {
            steps {
                script {
                    installMoleculeBookworm_pdps()
                    sh """
                        sudo apt-get update -y
                        sudo apt-get install -y jq
                    """
                }
            }
        }

        stage('Check version param and checkout') {
            steps {
                script {
                    sh "rm -rf ~/package-testing"                    
                    sh "cd ~/ && git clone -b ${TESTING_BRANCH} https://github.com/${TESTING_GIT_ACCOUNT}/package-testing.git"
                }
            }
        }

        stage('Test') {
            steps {
                script {
                    def selectedOSList = (env.VERSION.startsWith('8.4')) ? pdps_84_operating_systems() : pdps_80_operating_systems()
                    echo "selectedOSList: ${selectedOSList}"
                    moleculeParallelTestALL(allOS, selectedOSList, env.MOLECULE_DIR)
                }
            }
        }

    }
    post {
        
        failure {
            script {
                echo "Failed in previous stages so no need to trigger PSO Trigger Job"
            }
        }


        success {
            script {
                echo "Success"
                script {
                        def ps_operator_version_latest = sh(script: "curl -s https://api.github.com/repos/percona/percona-server-mysql-operator/releases/latest | jq -r '.tag_name' | cut -c 2-", returnStdout: true).trim()        
                        def HAPROXY_VERSION = sh(script: "curl -s https://api.github.com/repos/percona/percona-server-mysql-operator/releases/tags/v${ps_operator_version_latest} | jq -r '.body' | grep '* HAProxy' | awk '{print \$3}'", returnStdout: true).trim()
                        print "HAPROXY_VERSION: ${HAPROXY_VERSION}"
                        def PILLAR_VERSION = extractMajorVersion("${VERSION}")
                        echo "Latest PS Operator version: ${ps_operator_version_latest}"
                        withCredentials([string(credentialsId: 'JNKPERCONA_CLOUD_TOKEN', variable: 'TOKEN')]) {
                            sh """
                                    curl -X POST \
                                    -u ${TOKEN} \
                                    --data-urlencode TEST_SUITE=run-distro.csv \
                                    --data-urlencode TEST_LIST= \
                                    --data-urlencode IGNORE_PREVIOUS_RUN=NO \
                                    --data-urlencode PILLAR_VERSION=${PILLAR_VERSION} \
                                    --data-urlencode GIT_BRANCH=v${ps_operator_version_latest} \
                                    --data-urlencode GIT_REPO=https://github.com/percona/percona-server-mysql-operator \
                                    --data-urlencode PLATFORM_VER=max \
                                    --data-urlencode IMAGE_MYSQL=perconalab/percona-server-mysql-operator:${VERSION} \
                                    --data-urlencode IMAGE_HAPROXY=perconalab/haproxy:${HAPROXY_VERSION} \
                                    --data-urlencode IMAGE_PMM_SERVER=perconalab/pmm-server:dev-latest https://cloud.cd.percona.com/job/pso-gke-1/buildWithParameters
                            """
                        }
                }
            }    
        }
        
        always {
            script {
                echo "Post destroy"
                def selectedOSList = (env.VERSION.startsWith('8.4')) ? pdps_84_operating_systems() : pdps_80_operating_systems()
                moleculeParallelPostDestroyALL(allOS, selectedOSList, env.MOLECULE_DIR)
            }
        }
   }
}        