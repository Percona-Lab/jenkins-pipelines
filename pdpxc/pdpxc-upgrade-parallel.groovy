library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def extractMajorVersion(version) {
    def parts = version.split("\\.")
    return parts[0] + parts[1]
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
                    moleculeParallelTest(pdpxcOperatingSystems(), env.MOLECULE_DIR)
                }
            }
         }

        stage('Fetch and Update PXCO Trigger Job') {
            steps {
                script {
                    
                        def pxc_operator_version_latest = sh(script: "curl -s https://api.github.com/repos/percona/percona-xtradb-cluster-operator/releases/latest | jq -r '.tag_name' | cut -c 2-", returnStdout: true).trim()
                        def PILLAR_VERSION = extractMajorVersion("${VERSION}")

                        echo "Latest PXC Operator version: ${pxc_operator_version_latest}"

                        sh """
                            aws s3 cp ${S3_BUCKET}/${TRIGGER_FILE} ${LOCAL_TRIGGER_FILE}

                            sed -i 's/^PXCO=.*/PXCO=1/' ${LOCAL_TRIGGER_FILE}
                            sed -i 's/^PXCO_VERSION=.*/PXCO_VERSION=${pxc_operator_version_latest}/' ${LOCAL_TRIGGER_FILE}
                            sed -i 's/^PXC_VERSION=.*/PXC_VERSION=${VERSION}/' ${LOCAL_TRIGGER_FILE}
                            sed -i 's/^PXB_VERSION=.*/PXB_VERSION=${PXB_VERSION}/' ${LOCAL_TRIGGER_FILE}
                            sed -i 's/^PROXYSQL_VERSION=.*/PROXYSQL_VERSION=${PROXYSQL_VERSION}/' ${LOCAL_TRIGGER_FILE}
                            sed -i 's/^HAPROXY_VERSION=.*/HAPROXY_VERSION=${HAPROXY_VERSION}/' ${LOCAL_TRIGGER_FILE}
                            sed -i 's/^PILLAR_VERSION=.*/PILLAR_VERSION=${PILLAR_VERSION}/' ${LOCAL_TRIGGER_FILE}

                            aws s3 cp ${LOCAL_TRIGGER_FILE} ${S3_BUCKET}/${TRIGGER_FILE}

                        """
                }
            }
        }



  }
    post {
        always {
          script {
             moleculeParallelPostDestroy(pdpxcOperatingSystems(), env.MOLECULE_DIR)
         }
      }
   }
}
