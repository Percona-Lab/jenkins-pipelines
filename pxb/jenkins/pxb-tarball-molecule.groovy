library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def PXBskipOSPRO() {
  return ['debian-11', 'oracle-8', 'rhel-8','ubuntu-focal']
}

def PXBskipOSNONPRO() {
  return ['al-2023']
}


def noSkip() {
  return []
}

def deleteBuildInstances(){
    script {
        echo "All tests completed"

        def awsCredentials = [
                sshUserPrivateKey(
                    credentialsId: 'MOLECULE_AWS_PRIVATE_KEY',
                    keyFileVariable: 'MOLECULE_AWS_PRIVATE_KEY',
                    passphraseVariable: '',
                    usernameVariable: ''
                ),
                aws(
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                )
        ]

        withCredentials(awsCredentials) {
            def jobName = env.JOB_NAME
            def BUILD_NUMBER = env.BUILD_NUMBER
            jobName.trim()

            echo "Fetched JOB_TO_RUN from environment: '${jobName}'"

            echo "Listing EC2 instances with job-name tag: ${jobName}"
            sh """
            aws ec2 describe-instances --region us-west-1 --filters "Name=tag:job-name,Values=${jobName}" "Name=tag:build-number,Values=${BUILD_NUMBER}"  --query "Reservations[].Instances[].InstanceId" --output text
            """

            sh """
            echo "=== EC2 Instances to be cleaned up ==="
            aws ec2 describe-instances --region us-west-1 \\
            --filters "Name=tag:job-name,Values=${jobName}" "Name=tag:build-number,Values=${BUILD_NUMBER}" \\
            --query "Reservations[].Instances[].[InstanceId,Tags[?Key=='Name'].Value|[0],State.Name]" \\
            --output table || echo "No instances found with job-name tag: ${jobName}"
            """

            def instanceIds = sh(
                script: """
                aws ec2 describe-instances --region us-west-1 \\
                --filters "Name=tag:job-name,Values=${jobName}" "Name=tag:build-number,Values=${BUILD_NUMBER}" "Name=instance-state-name,Values=running" \\
                --query "Reservations[].Instances[].InstanceId" \\
                --output text
                """,
                returnStdout: true
            ).trim()

            if (instanceIds != null && !instanceIds.trim().isEmpty()) {
                echo "Found instances to terminate: ${instanceIds.trim()}"

                sh """
                echo "${instanceIds.trim()}" | xargs -r aws ec2 terminate-instances --instance-ids
                """
              
                sleep(30)
                
                echo "Terminated instances: ${instanceIds.trim()}"
                
                echo "==========================================="

                echo "Verification: Status of terminated instances:"

                sh """
                sleep 5 && aws ec2 describe-instances --instance-ids ${instanceIds} --query "Reservations[].Instances[].[InstanceId,Tags[?Key=='Name'].Value|[0],State.Name]" --output table
                """
            
            } else {
                echo "No instances found to terminate"
            }
        }
    }
}


pipeline {
  agent {

    label 'min-bookworm-x64'

  }
  environment {
    PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
    MOLECULE_DIR = "molecule/pxb-binary-tarball/";
    PXB_VERSION = "${params.PXB_VERSION}";
    install_repo = "${params.TESTING_REPO}";
    TESTING_BRANCH = "${params.TESTING_BRANCH}";
    TESTING_GIT_ACCOUNT = "${params.TESTING_GIT_ACCOUNT}";
    REPO_TYPE = "${params.REPO_TYPE}";
  }
  parameters {
    string(
      name: 'PXB_VERSION', 
      defaultValue: '8.0.35-33', 
      description: 'PXB full version'
    )
    //string(
    //  name: 'install_repo',
    //  defaultValue: 'main',
    //  description: 'Repository to install PXB from'
    //)
    string(
      defaultValue: 'master',
      description: 'Branch for package-testing repository',
      name: 'TESTING_BRANCH'
    )
    string(
      defaultValue: 'Percona-QA',
      description: 'Git account for package-testing repository',
      name: 'TESTING_GIT_ACCOUNT'
    )
    choice(
        choices: ['NORMAL', 'PRO'],
        description: 'Choose the product to test',
        name: 'REPO_TYPE'
    )
    choice(
        choices: ['main', 'testing'],
        description: 'Choose the product to test',
        name: 'TESTING_REPO'
    )
  }
  options {
    //withCredentials(moleculepxcJenkinsCreds())
    withCredentials(moleculepxbJenkinsCreds())
    disableConcurrentBuilds()
  }

  stages {

    stage('Set build name'){
      steps {
        script {
          def PXB_RELEASE = params.PXB_VERSION.tokenize('-')[0].tokenize('.').with { it[0] + it[1] }
          env.PXB_RELEASE = PXB_RELEASE
          
          currentBuild.displayName = "${env.BUILD_NUMBER}-${env.PXB_VERSION}-${params.REPO_TYPE}-${params.TESTING_REPO}"
          currentBuild.description = "${env.PXB_REVISION}"
        }
      }
    }
    stage('Checkout') {
      steps {
        deleteDir()
        git poll: false, branch: TESTING_BRANCH, url: "https://github.com/${TESTING_GIT_ACCOUNT}/package-testing.git"
        echo "PXB_VERSION is ${env.PXB_VERSION}"
      }
    }
    stage ('Prepare') {
      steps {
        script {
          echo "PXB_RELEASE is ${env.PXB_RELEASE}"
          installMoleculeBookworm()
        }
      }
    }

    stage('Run tarball molecule') {
      steps {
          script {
              withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                if (params.REPO_TYPE == 'PRO') {
                  moleculeParallelTestSkip(pxbTarball(), env.MOLECULE_DIR, PXBskipOSPRO())
                } else if (params.REPO_TYPE != 'PRO') {
                  moleculeParallelTestSkip(pxbTarball(), env.MOLECULE_DIR, PXBskipOSNONPRO())
                }
                else {
                  error "Release type not recognized"
                }
              }
          }
      }
    }

  }
  post {

    always {
      script {
        //archiveArtifacts artifacts: "*.tar.gz" , followSymlinks: false
        moleculeParallelPostDestroy(pxbTarball(), env.MOLECULE_DIR)

      }
      deleteBuildInstances()
    }

    success {
        script {
            
        def product_to_test = ""
        
        def PXB_RELEASE = params.PXB_VERSION.tokenize('-')[0].tokenize('.').with { it[0] + it[1] }

        if (PXB_RELEASE == "80") {
            product_to_test = "pxb_80"
        } else if (PXB_RELEASE == "84") {
            product_to_test = "pxb_84"
        } else {
            product_to_test = "pxb_innovation_lts"
        }

        build job: 'pxb-package-testing-molecule-all', propagate: false, wait: false, parameters: [
            string(name: 'product_to_test', value: "${product_to_test}"),
            string(name: 'install_repo', value: "${params.TESTING_REPO}"),
            string(name: 'git_repo', value: "https://github.com/${params.TESTING_GIT_ACCOUNT}/package-testing.git"),
            string(name: 'TESTING_BRANCH', value: "${params.TESTING_BRANCH}"),
            string(name: 'upstream', value: "no")
        ]

        }
    }

  }
}
