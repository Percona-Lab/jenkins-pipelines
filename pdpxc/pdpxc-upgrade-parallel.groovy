library changelog: false, identifier: "lib@pdpxco", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/kaushikpuneet07/jenkins-pipelines.git'
])

def extractMajorVersion(version) {
    def parts = version.split("\\.")
    return parts[0] + parts[1]
}

def pdpxcOperatingSystems84() {
    return [
        'oracle-8', 'oracle-9', 'rhel-8', 'rhel-9', 'rhel-10', 'debian-11', 'debian-12', 'debian-13', 'ubuntu-jammy', 'ubuntu-noble', 'al-2023'
    ]
}

def pdpxcOperatingSystems80() {
    return [
        'oracle-8', 'oracle-9', 'rhel-8', 'rhel-9', 'debian-11', 'debian-12', 'ubuntu-jammy', 'ubuntu-noble'
    ]
}

List allOS = pdpxcOperatingSystems84() + pdpxcOperatingSystems80()

def moleculeParallelTestALL(allOS, operatingSystems, moleculeDir) {
    def tests = [:]
    allOS.each { os ->
        tests["${os}"] = {
            stage("${os}") {
                if (operatingSystems.contains(os)) {
                    sh """
                        . virtenv/bin/activate
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
                    . virtenv/bin/activate
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
    MOLECULE_DIR = "molecule/pdmysql/pdpxc_minor_upgrade";
    S3_BUCKET = "s3://package-testing-status-test"
    TRIGGER_FILE = "PXCO"
    LOCAL_TRIGGER_FILE = "PXCO"

  }
  parameters {
        choice(
            name: 'FROM_REPO',
            description: 'PDPXC will be upgraded from this repository',
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
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: '8.0.34',
            description: 'From this version pdmysql will be updated. Possible values are with and without percona release: 8.0.30 OR 8.0.30-22',
            name: 'FROM_VERSION'
        )
        string(
            defaultValue: '8.0.35-27',
            description: 'To this version pdmysql will be updated. Possible values are with and without percona release and build: 8.0.32, 8.0.32-24 OR 8.0.32-24.2',
            name: 'VERSION'
        )
        string(
            defaultValue: '',
            description: 'PXC revision for test after update. Empty by default (not checked).',
            name: 'PXC_REVISION'
        )
        string(
            defaultValue: '8.0.35-30',
            description: 'PXB version for test. Possible values are with and without percona release and build: 8.0.32, 8.0.32-25 OR 8.0.32-25.1',
            name: 'PXB_VERSION'
        )
        string(
            defaultValue: '2.5.5',
            description: 'Proxysql version for test',
            name: 'PROXYSQL_VERSION'
        )
        string(
            defaultValue: '2.8.5',
            description: 'HAProxy version for test',
            name: 'HAPROXY_VERSION'
        )
        string(
            defaultValue: '3.6.0',
            description: 'Percona toolkit version for test',
            name: 'PT_VERSION'
        )
        string(
            defaultValue: '1.0',
            description: 'replication-manager.sh version',
            name: 'REPL_MANAGER_VERSION'
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
          withCredentials(moleculePdpxcJenkinsCreds())
          disableConcurrentBuilds()
  }
    stages {
        stage('Set build name'){
          steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}"
                    currentBuild.description = "From: ${env.FROM_VERSION} ${env.FROM_REPO}; to: ${env.VERSION} ${env.TO_REPO}. Git br: ${env.TESTING_BRANCH}"
                }
            }
        }
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: TESTING_BRANCH, url: "https://github.com/${TESTING_GIT_ACCOUNT}/package-testing.git"
            }
        }
        stage ('Prepare') {
          steps {
                script {
                   installMoleculeBookworm()
                   sh """
                   sudo apt-get update -y
                   sudo apt-get install -y jq
                   """
             }
           }
        }
        stage('Test') {
          steps {
                script {
                    def selectedOSList = (env.VERSION.startsWith('8.4')) ? pdpxcOperatingSystems84() : pdpxcOperatingSystems80()
                    echo "selectedOSList: ${selectedOSList}"
                    moleculeParallelTestALL(allOS, selectedOSList, env.MOLECULE_DIR)
                }
            }
         }

        stage('Fetch and Update PXCO Trigger Job') {
            steps {
                script {
                    
                        def pxc_operator_version_latest = sh(script: "curl -s https://api.github.com/repos/percona/percona-xtradb-cluster-operator/releases/latest | jq -r '.tag_name' | cut -c 2-", returnStdout: true).trim()
                        def PILLAR_VERSION = extractMajorVersion("${VERSION}")

                        echo "Latest PXC Operator version: ${pxc_operator_version_latest}"

                            withCredentials([string(credentialsId: 'JNKPERCONA_CLOUD_TOKEN', variable: 'TOKEN')]) {

                                sh """
                                        curl -X POST \
                                        -u ${TOKEN} \
                                        --data-urlencode TEST_SUITE=run-distro.csv \
                                        --data-urlencode TEST_LIST= \
                                        --data-urlencode IGNORE_PREVIOUS_RUN=NO \
                                        --data-urlencode PILLAR_VERSION=${PILLAR_VERSION} \
                                        --data-urlencode GIT_BRANCH=v${pxc_operator_version_latest} \
                                        --data-urlencode GIT_REPO=https://github.com/percona/percona-xtradb-cluster-operator \
                                        --data-urlencode PLATFORM_VER=max \
                                        --data-urlencode IMAGE_PXC=perconalab/percona-xtradb-cluster:${VERSION} \
                                        --data-urlencode IMAGE_PROXY=percona/proxysql2:${PROXYSQL_VERSION} \
                                        --data-urlencode IMAGE_HAPROXY=perconalab/haproxy:${HAPROXY_VERSION} \
                                        --data-urlencode IMAGE_PMM_SERVER=perconalab/pmm-server:dev-latest https://cloud.cd.percona.com/job/pxco-gke-1/buildWithParameters
                                """

                            }




                }
            }
        }



  }
    post {
        always {
          script {
              def selectedOSList = (env.VERSION.startsWith('8.4')) ? pdpxcOperatingSystems84() : pdpxcOperatingSystems80()
              moleculeParallelPostDestroyALL(allOS, selectedOSList, env.MOLECULE_DIR)
         }
      }
   }
}
