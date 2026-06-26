library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// This job tests the two representative families against per-family branch builds:
// oracle-9 (RedHat, OL9 build) and debian-12 (Debian, bookworm build).
def pxbTestOSes() {
  return ['debian-12', 'oracle-9']
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
    label 'deb12-x64-min'
  }
  environment {
    PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
    MOLECULE_DIR = "molecule/pxb-new-ps-inc-backup-load/";
    PXB_VERSION = "${params.PXB_VERSION}";
    PS_VERSION = "${params.PS_VERSION}";
    TESTING_BRANCH = "${params.TESTING_BRANCH}";
    SELECT_TEST_PYTEST = "${params.SELECT_TEST_PYTEST}";
  }
  parameters {
    string(
      name: 'PXB_VERSION',
      defaultValue: '8.4.0-6',
      description: 'PXB full version'
    )
    string(
      name: 'PXB_RHEL_GCLIBC_VERSION',
      defaultValue: '2.34',
      description: 'PXB glibc version for RHEL'
    )
    string(
      name: 'PXB_DEBIAN_GCLIBC_VERSION',
      defaultValue: '2.35',
      description: 'PXB glibc version for Debian'
    )
    string(
      name: 'PXB_GIT_REPO',
      defaultValue: 'https://github.com/percona/percona-xtrabackup',
      description: 'PXB git repository to build an unreleased PXB from (used only when PXB_BRANCH is set)'
    )
    string(
      name: 'PXB_BRANCH',
      defaultValue: '',
      description: 'PXB git branch/tag to build. Leave EMPTY to download the released PXB_VERSION (default behavior). When set, an unreleased PXB is built from this branch via the percona-xtrabackup compile pipeline and tested against the released PS_VERSION.'
    )
    string(
      name: 'PXB_BUILD_DOCKER_OS_RHEL',
      defaultValue: 'oraclelinux:9',
      description: 'DOCKER_OS used to build the PXB binary for the RedHat-family test host (oracle-9). Must be a valid DOCKER_OS choice of the target compile pipeline. Valid 8.0 choices: centos:8, oraclelinux:9, ubuntu:focal, ubuntu:jammy, ubuntu:noble, debian:bullseye, debian:bookworm. Valid 9.x choices: oraclelinux:9, ubuntu:jammy, ubuntu:noble, debian:bookworm, debian:trixie.'
    )
    string(
      name: 'PXB_BUILD_DOCKER_OS_DEB',
      defaultValue: 'debian:bookworm',
      description: 'DOCKER_OS used to build the PXB binary for the Debian-family test host (debian-12). Must be a valid DOCKER_OS choice of the target compile pipeline (see PXB_BUILD_DOCKER_OS_RHEL for valid values).'
    )
    choice(
      name: 'PXB_BUILD_TYPE',
      choices: ['RelWithDebInfo', 'Debug'],
      description: 'CMAKE_BUILD_TYPE for the branch build (used only when PXB_BRANCH is set)'
    )
    string(
      name: 'PS_VERSION',
      defaultValue: '8.4.8-8',
      description: 'PS full version'
    )
    string(
      name: 'PS_RHEL_GCLIBC_VERSION',
      defaultValue: '2.34',
      description: 'PS glibc version for RHEL'
    )
    string(
      name: 'PS_DEBIAN_GCLIBC_VERSION',
      defaultValue: '2.35',
      description: 'PS glibc version for Debian'
    )
    string(
      defaultValue: 'master',
      description: 'Branch for package-testing repository',
      name: 'TESTING_BRANCH'
    )
    string(
      name: 'SELECT_TEST_PYTEST',
      defaultValue: 'all',
      description: 'Pytest -k filter expression. Use "all" to run every test from inc_backup_load_tests.py, or a pytest -k expression like "test_normal_backup or test_rocksdb_backup"'
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

    stage('Build PXB from branch') {
      when {
        expression { params.PXB_BRANCH?.trim() }
      }
      steps {
        script {
          def pxbMajor = params.PXB_VERSION.tokenize('.')[0]
          def compileJob = (pxbMajor == '9') ? 'percona-xtrabackup-9.x-compile-pipeline' : 'percona-xtrabackup-8.0-compile-pipeline'

          // Build one PXB binary on the given DOCKER_OS via the compile pipeline and
          // resolve the public S3 URL of the resulting tarball.
          def buildOne = { dockerOS ->
            echo "Building unreleased PXB from ${params.PXB_GIT_REPO} branch '${params.PXB_BRANCH}' via ${compileJob} (${dockerOS}, ${params.PXB_BUILD_TYPE})"

            def b = build job: compileJob, wait: true, propagate: true, parameters: [
              string(name: 'GIT_REPO', value: params.PXB_GIT_REPO),
              string(name: 'BRANCH', value: params.PXB_BRANCH),
              string(name: 'DOCKER_OS', value: dockerOS),
              string(name: 'CMAKE_BUILD_TYPE', value: params.PXB_BUILD_TYPE)
            ]

            def dockerOsDashed = dockerOS.replaceAll(':', '-')
            // copyArtifacts writes COMPILE_BUILD_TAG to a fixed name; give each parallel
            // branch its own target dir so the two runs don't clobber each other.
            copyArtifacts(
              projectName: compileJob,
              selector: specific("${b.number}"),
              filter: 'COMPILE_BUILD_TAG',
              target: dockerOsDashed
            )

            def buildTag = readFile("${dockerOsDashed}/COMPILE_BUILD_TAG").trim()
            def grepPattern = (params.PXB_BUILD_TYPE == 'Debug') ? "x86_64-${dockerOsDashed}-debug.tar.gz" : "x86_64-${dockerOsDashed}.tar.gz"

            def tarball = sh(
              script: "aws s3 ls pxb-build-cache/${buildTag}/ | grep '${grepPattern}' | awk '{print \$4}' | head -1",
              returnStdout: true
            ).trim()

            if (!tarball) {
              error "No PXB tarball matching '${grepPattern}' found in s3://pxb-build-cache/${buildTag}/"
            }

            // Use virtual-hosted-style URL (path-style returns HTTP 301) with the bucket's real region.
            def loc = sh(script: "aws s3api get-bucket-location --bucket pxb-build-cache --query 'LocationConstraint' --output text 2>/dev/null || true", returnStdout: true).trim()
            def bucketRegion = loc ? (loc == 'None' ? 'us-east-1' : loc) : 'us-east-2'
            return "https://pxb-build-cache.s3.${bucketRegion}.amazonaws.com/${buildTag}/${tarball}"
          }

          // Build the RedHat (oracle-9) and Debian (debian-12) binaries in parallel.
          def urls = [:]
          parallel(
            rhel: { urls.rhel = buildOne(params.PXB_BUILD_DOCKER_OS_RHEL) },
            deb:  { urls.deb  = buildOne(params.PXB_BUILD_DOCKER_OS_DEB) }
          )
          env.PXB_TARBALL_URL_RHEL = urls.rhel
          env.PXB_TARBALL_URL_DEB  = urls.deb
          echo "Branch-built PXB tarball (RedHat/oracle-9): ${env.PXB_TARBALL_URL_RHEL}"
          echo "Branch-built PXB tarball (Debian/debian-12): ${env.PXB_TARBALL_URL_DEB}"
          currentBuild.displayName = "${env.BUILD_NUMBER}-${env.PXB_VERSION}-branch:${params.PXB_BRANCH}-${params.TESTING_BRANCH}"
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
          def testCredentials = [
            usernamePassword(
                credentialsId: 'PS_PRIVATE_REPO_ACCESS',
                passwordVariable: 'PASSWORD',
                usernameVariable: 'USERNAME'
            ),
            string(
                credentialsId: 'FORTANIX_EMAIL',
                variable: 'FORTANIX_EMAIL'
            ),
            string(
                credentialsId: 'FORTANIX_PASSWORD',
                variable: 'FORTANIX_PASSWORD'
            ),
            string(
                credentialsId: 'KMS_TESTING_KEY_ID',
                variable: 'KMS_KEYID'
            ),
          ]
          withEnv(envMap) {
            withCredentials(testCredentials) {
                moleculeParallelTestSkip(pxbTestOSes(), env.MOLECULE_DIR, [])
            }
          }
        }
      }
    }
  }

  post {
    always {
      script {
        archiveArtifacts artifacts: "*.tar.gz", followSymlinks: false, allowEmptyArchive: true
        junit allowEmptyResults: true, testResults: "**/pytest-junit*.xml"
        moleculeParallelPostDestroy(pxbTestOSes(), env.MOLECULE_DIR)
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
