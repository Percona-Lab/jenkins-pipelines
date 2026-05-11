library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def pdpxcOperatingSystems84() {
    return [
        'oracle-8', 'oracle-9', 'rhel-8', 'rhel-9', 'rhel-10', 'debian-11', 'debian-12', 'debian-13', 'ubuntu-jammy', 'ubuntu-noble', 'al-2023'
    ]
}

def pdpxcOperatingSystems80() {
    return [
        'oracle-8', 'oracle-9', 'rhel-8', 'rhel-9', 'debian-11', 'debian-12', 'ubuntu-jammy', 'ubuntu-noble', 'al-2023'
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
      MOLECULE_DIR = "molecule/pdmysql/${SCENARIO}";
  }
  parameters {
        choice(
            name: 'REPO',
            description: 'Repo for testing',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: '8.0.35-27',
            description: 'PXC version for test. Possible values are with and without percona release and build: 8.0.32, 8.0.32-24 OR 8.0.32-24.2',
            name: 'VERSION'
        )
        string(
            defaultValue: '',
            description: 'PXC revision for test. Empty by default (not checked).',
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
        choice(
            name: 'SCENARIO',
            description: 'Scenario for test',
            choices: pdpxcScenarios()
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
        booleanParam(
            name: 'MAJOR_REPO',
            description: "Enable to use major (pdpxc-8.0) repo instead of pdpxc-8.0.XX"
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
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.SCENARIO}"
                    currentBuild.description = "${env.VERSION}-${env.REPO}-${env.TESTING_BRANCH}-${env.MAJOR_REPO}"
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
