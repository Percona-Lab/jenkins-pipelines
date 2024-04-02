library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "psmdb-tarball/psmdb-tarball-pro"
def psmdb_default_os_list = ["rhel8","rhel9","ubuntu-jammy-pro"]
def psmdb_7_os_list = ["rhel9","ubuntu-jammy-pro"]

pipeline {
    agent {
        label 'min-centos-7-x64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        ANSIBLE_DISPLAY_SKIPPED_HOSTS = false
    }
    parameters {
        string(
            defaultValue: 'https://url_with_creds_if_needed.ol9.tar.gz',
            description: 'URL/S3 link for pro tarball for ol9',
            name: 'TARBALL_OL9'
        )
        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH'
        )
    }
    options {
          withCredentials(moleculePbmJenkinsCreds())
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}"
                    currentBuild.description = "${env.TARBALL_OL9}"

                    def versionNumber = TARBALL_OL9 =~ /percona-server-mongodb-pro-(\d+)/
                    def version = versionNumber ? Integer.parseInt(versionNumber[0][1]) : null

                    if (version == 7) {
                        psmdb_default_os_list = psmdb_7_os_list
                    }
                }
            }
        }
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: TESTING_BRANCH, url: 'https://github.com/Percona-QA/psmdb-testing.git'
            }
        }
        stage ('Prepare') {
            steps {
                script {
                    installMolecule()
                }
            }
        }
        stage('Test') {
            steps {
                moleculeParallelTest(psmdb_default_os_list, moleculeDir)
            }
        }
    }
    post {
        always {
            junit testResults: "**/*-report.xml", keepLongStdio: true
            moleculeParallelPostDestroy(psmdb_default_os_list, moleculeDir)
        }
    }
}
