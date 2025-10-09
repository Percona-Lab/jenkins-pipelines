library changelog: false, identifier: "lib@master", retriever: modernSCM([
        $class: 'GitSCMSource',
        remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "plm-functional/replicaset"
def stageFailed = false

pipeline {
    agent {
        label 'min-bookworm-x64'
    }
    parameters {
        choice(name: 'OPERATING_SYSTEM',description: 'Operating System you want to test on',choices: ['ubuntu24', 'redhat8'])
        choice(name: 'PSMDB',description: 'PSMDB used for testing',choices: ['6', '7','8'])
        string(name: 'PLM_BRANCH',description: 'PLM Branch for testing',defaultValue: 'main')
        string(name: 'TESTING_BRANCH',description: 'Branch for testing repository',defaultValue: 'main')
        string(name: 'GO_VERSION',description: 'Version of Golang used',defaultValue: '1.24.1')
        choice(name: 'INSTANCE_TYPE',description: 'Ec2 instance type',choices: ['t2.micro','i3.large','i3en.large','i3.xlarge','i3en.xlarge','i3en.3xlarge','i3en.24xlarge'])
        string(name: 'DATASIZE',description: 'The max size of the data in mb. This is distributed over the amount of collections stated',defaultValue: '10')
        string(name: 'COLLECTIONS', description: 'The number of collections',defaultValue: '5')
        choice(name: 'DOC_TEMPLATE',description: 'Type of doc template, random or compact(compressible)',choices: ['random', 'compact'])
        booleanParam(name: 'RANDOM_DISTRIBUTE_DATA', defaultValue: false, description: 'Randomly distribute data throughout Collections')
        booleanParam(name: 'FULL_DATA_COMPARE', defaultValue: false, description: 'Run full data comparison after sync (not recommended for large datasets)')
        string(name: 'EXTRA_VARS', description: 'Any extra Environment Variables for PerconaLink (e.g. PLM_CLONE_NUM_INSERT_WORKERS: 0, PLM_CLONE_NUM_PARALLEL_COLLECTIONS: 0. Make sure to separate using commas)',defaultValue: '')
        string(name: 'TIMEOUT',description: 'Timeout for the data replication',defaultValue: '3600')
        string(name: 'SSH_USER',description: 'User for debugging',defaultValue: 'none')
        string(name: 'SSH_PUBKEY',description: 'User ssh public key for debugging',defaultValue: 'none')
        booleanParam(name: 'DESTROY', defaultValue: true, description: 'Automatically destroys environment upon finishing tests, leave unchecked if you do not want to delete instances.')
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        ANSIBLE_DISPLAY_SKIPPED_HOSTS = false
        STORAGE = 'aws'
    }
    options {
        withCredentials(moleculePbmJenkinsCreds())
        disableConcurrentBuilds()
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.SSH_USER}-aws"
                }
            }
        }
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: TESTING_BRANCH, url: 'https://github.com/Percona-QA/psmdb-testing.git'
            }
        }
        stage ('Install molecule') {
            steps {
                script {
                    installMoleculeBookworm()
                }
            }
        }
        stage ('Create instances') {
            steps {
                script{
                    try {
                        moleculeExecuteActionWithScenario(moleculeDir, "create", params.OPERATING_SYSTEM)
                    } catch (e) {
                        echo "Create stage failed"
                        stageFailed = true
                        throw e
                    }
                }
            }
        }
        stage ('Prepare instances') {
            steps {
                script{
                    try {
                        moleculeExecuteActionWithScenario(moleculeDir, "prepare", params.OPERATING_SYSTEM)
                    } catch (e) {
                        echo "Prepare stage failed"
                        stageFailed = true
                        throw e
                    }
                }
            }
        }
        stage ('Create infrastructure') {
            steps {
                  script{
                    try {
                            moleculeExecuteActionWithScenario(moleculeDir, "converge", params.OPERATING_SYSTEM)
                    } catch (e) {
                            echo "Converge stage failed"
                            stageFailed = true
                            throw e
                    }
                }
            }
        }
        stage ('Run tests') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(moleculeDir, "verify", params.OPERATING_SYSTEM)
                    withCredentials([string(credentialsId: 'olexandr_zephyr_token', variable: 'ZEPHYR_TOKEN')]) {
                        sh 'REPORT_FILE=$(find . -name report.xml | head -n1) && [ -f "$REPORT_FILE" ] || { echo "report.xml not found. Skipping Zephyr upload."; exit 0; } && echo "Uploading $REPORT_FILE to Zephyr..." && curl -H "Content-Type:multipart/form-data" -H "Authorization: Bearer $ZEPHYR_TOKEN" -F "file=@$REPORT_FILE;type=application/xml" -F "testCycle={\\"name\\":\\"${JOB_NAME}-${BUILD_NUMBER}\\",\\"customFields\\":{\\"PLM branch\\":\\"${PLM_BRANCH}\\",\\"Instance Type\\":\\"${INSTANCE_TYPE}\\",\\"Operating System\\":\\"${OPERATING_SYSTEM}\\",\\"PSMDB Version\\":\\"${PSMDB}\\",\\"Datasize(Mb)\\":\\"${DATASIZE}\\",\\"Number of Collections\\":\\"${COLLECTIONS}\\"}};type=application/json" "https://api.zephyrscale.smartbear.com/v2/automations/executions/junit?projectKey=PML" -i || true'

                    }
                }
            }
        }
    }
    post {
        always {
            junit testResults: "**/report.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
            script {
                if (params.DESTROY || stageFailed) {
                    echo "Destroying AWS instances because DESTROY=true or build failed"
                    moleculeExecuteActionWithScenario(moleculeDir, "destroy", params.OPERATING_SYSTEM)
                } else {
                    def timeoutSeconds = params.TIMEOUT.toInteger()
                    echo "Build succeeded and DESTROY=false - sleeping for ${timeoutSeconds} seconds"
                    echo "########################################################### NOTE ##########################################################\n" +
                            "To access PMM to see performance stats, run the following command: 'ssh -L 8443:127.0.0.1:443 <SSH-USER>@<AWS-PERCONALINK-IP>'\n" +
                            "The IP address for PerconaLink can be found in this Jenkins log under the 'TASK [Wait for SSH]' section.\n" +
                            "Once SSH has been established you can access PMM through your local browser on https://localhost:8443 using login details admin:admin \n" +
                            "########################################################### NOTE ##########################################################"
                    try {
                        timeout(time: timeoutSeconds, unit: 'SECONDS') {
                            waitUntil { return false }
                        }
                    }
                    catch (e) {
                        echo "Error during sleep: ${e}"
                    }
                    finally {
                        echo "Destroying AWS instances after timeout or manual abort"
                        moleculeExecuteActionWithScenario(moleculeDir, "destroy", params.OPERATING_SYSTEM)
                    }
                }
            }
        }
    }
}
