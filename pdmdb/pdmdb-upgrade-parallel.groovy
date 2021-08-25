library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "pdmdb/pdmdb-upgrade"
def creds = [sshUserPrivateKey(credentialsId: 'MOLECULE_AWS_PRIVATE_KEY', keyFileVariable: 'MOLECULE_AWS_PRIVATE_KEY', passphraseVariable: '', usernameVariable: ''),
             string(credentialsId: 'GCP_SECRET_KEY', variable: 'GCP_SECRET_KEY'), string(credentialsId: 'GCP_ACCESS_KEY', variable: 'GCP_ACCESS_KEY'),
             [$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '4462f2e5-f01c-4e3f-9586-2ffcf5bf366a', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'],
             [$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'S3_ACCESS_KEY_ID', credentialsId: '2a84aea7-32a0-4598-9e8d-5153179097a9', secretKeyVariable: 'S3_SECRET_ACCESS_KEY']]

pipeline {
  agent {
      label 'min-centos-7-x64'
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
      OLDVERSIONS = "VERSION=${params.FROM_PBM_VERSION} PDMDB_VERSION=${params.FROM_PDMDB_VERSION}"
      NEWVERSIONS = "VERSION=${params.TO_PBM_VERSION} PDMDB_VERSION=${params.TO_PDMDB_VERSION}"
  }
  parameters {
        choice(
            name: 'FROM_REPO',
            description: 'Repo for base install',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: 'pdmdb-4.2.15',
            description: 'From this version PDMDB will be updated',
            name: 'FROM_PDMDB_VERSION'
        )
        choice(
            name: 'TO_REPO',
            description: 'Repo for update',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: 'pdmdb-4.4.8',
            description: 'To this version PDMDB will be updated',
            name: 'TO_PDMDB_VERSION'
        )
        string(
            defaultValue: '1.5.0',
            description: 'From this version PBM will be updated',
            name: 'FROM_PBM_VERSION'
        )
        string(
            defaultValue: '1.6.0',
            description: 'To this version PBM will be updated',
            name: 'TO_PBM_VERSION'
        )

        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH')
  }
  options {
          withCredentials(moleculePbmJenkinsCreds())
          disableConcurrentBuilds()
  }
    stages {
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
        stage('Create virtual machines') {
          steps {
                script {
                    runMoleculeCommandParallel(pdmdbOperatingSystems(), moleculeDir, "create")
                }
            }
         }
        stage('Prepare virtual machines') {
          steps {
                script {
                    runMoleculeCommandParallel(pdmdbOperatingSystems(), moleculeDir, "prepare")
                }
            }
         }
        stage('Install old version') {
          steps {
                script {
                    runMoleculeCommandParallelWithVariableList(pdmdbOperatingSystems(), moleculeDir, "converge", env.OLDVERSIONS)
                }
            }
         }
        stage('Test old version') {
          steps {
                script {
                    runMoleculeCommandParallelWithVariableList(pdmdbOperatingSystems(), moleculeDir, "verify", env.OLDVERSIONS)
                }
            }
         }
        stage('Install new version') {
          steps {
                script {
                    runMoleculeCommandParallelWithVariableList(pdmdbOperatingSystems(), moleculeDir, "side-effect", env.NEWVERSIONS)
                }
            }
         }
        stage('Test new version') {
          steps {
                script {
                    runMoleculeCommandParallelWithVariableList(pdmdbOperatingSystems(), moleculeDir, "verify", env.NEWVERSIONS)
                }
            }
         }
        stage('Remove old packages') {
          steps {
                script {
                    runMoleculeCommandParallel(pdmdbOperatingSystems(), moleculeDir, "cleanup")
                }
            }
         }
  }
    post {
        always {
          script {
              moleculeParallelPostDestroy(pdmdbOperatingSystems(), moleculeDir)
         }
      }
  }
}
