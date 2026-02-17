library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def PXBskipOSPRO() {
  return ['debian-11', 'debian-12', 'oracle-8', 'oracle-9', 'rhel-8', 'rhel-9', 'ubuntu-jammy', 'ubuntu-noble', 'al-2023']
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

def loadEnvFile(envFilePath) {
    def envMap = []
    def envFileContent = readFile(file: envFilePath).trim().split('\n')
    envFileContent.each { line ->
        if (line && !line.startsWith('#')) {
            def parts = line.split('=')
            if (parts.length == 2) {
                envMap << "${parts[0].trim()}=${parts[1].trim()}"
            }
        }
    }
    return envMap
}


pipeline {
  agent {
    label 'min-bookworm-x64'
  }
  environment {
    PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
    MOLECULE_DIR = "molecule/pxb-new-ps-tarball/";
    PXB_VERSION = "${params.PXB_VERSION}";
    PS_VERSION = "${params.PS_VERSION}";
    TESTING_BRANCH = "${params.TESTING_BRANCH}";
  }
  parameters {
    string(
      name: 'PXB_VERSION',
      defaultValue: '8.0.35-34',
      description: 'PXB full version'
    )
    string(
      name: 'PXB_RHEL_GCLIBC_VERSION',
      defaultValue: '2.39',
      description: 'PS full version'
    )
    string(
      name: 'PXB_DEBIAN_GCLIBC_VERSION',
      defaultValue: '2.36',
      description: 'PS full version'
    )
    string(
      name: 'PS_VERSION',
      defaultValue: '8.0.44-35',
      description: 'PS full version'
    )
    string(
      name: 'PS_RHEL_GCLIBC_VERSION',
      defaultValue: '2.35',
      description: 'PS full version'
    )
    string(
      name: 'PS_DEBIAN_GCLIBC_VERSION',
      defaultValue: '2.35',
      description: 'PS full version'
    )
    string(
      defaultValue: 'master',
      description: 'Branch for package-testing repository',
      name: 'TESTING_BRANCH'
    )
  }
  options {
    withCredentials(moleculepxbJenkinsCreds())
    disableConcurrentBuilds()
  }

  stages {
    stage('Set build name'){
      steps {
        script {
          def PXB_RELEASE = params.PXB_VERSION.tokenize('-')[0].tokenize('.').with { it[0] + it[1] }
          env.PXB_RELEASE = PXB_RELEASE
          
          currentBuild.displayName = "${env.BUILD_NUMBER}-${env.PXB_VERSION}-${params.TESTING_BRANCH}"
          //currentBuild.description = "${env.PXB_REVISION}"
        }
      }
    }

    stage('Checkout') {
      steps {
        deleteDir()
        git poll: false, branch: TESTING_BRANCH, url: "https://github.com/Percona-QA/package-testing.git"
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

    stage('Run test') {
      steps {
        script {
          sh """
              echo WORKSPACE_VAR=${WORKSPACE} >> .env.ENV_VARS
          """
          def envMap = loadEnvFile('.env.ENV_VARS')
          withEnv(envMap) {
            withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                moleculeParallelTestSkip(pxbTarball(), env.MOLECULE_DIR, PXBskipOSPRO())
            }
          }
        }
      }
    }
  }

  post {
    always {
      script {
        archiveArtifacts artifacts: "*.tar.gz, **/pytest-junit*.xml", followSymlinks: false, allowEmptyArchive: true
        junit allowEmptyResults: true, testResults: "**/pytest-junit*.xml"
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

        // build job: 'pxb-package-testing-molecule-all', propagate: false, wait: false, parameters: [
        //     string(name: 'product_to_test', value: "${product_to_test}"),
        //     string(name: 'install_repo', value: "${params.TESTING_REPO}"),
        //     string(name: 'git_repo', value: "https://github.com/${params.TESTING_GIT_ACCOUNT}/package-testing.git"),
        //     string(name: 'TESTING_BRANCH', value: "${params.TESTING_BRANCH}"),
        //     string(name: 'upstream', value: "no")
        // ]

        }
    }

  }
}