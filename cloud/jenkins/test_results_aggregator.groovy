pipeline {
    environment {
        CLOUDSDK_CORE_DISABLE_PROMPTS = 1
    }
    parameters {
        choice(
            choices: 'PSMDBO\nPXCO\nPGO',
            description: 'Product for which test results should be collected',
            name: 'PRODUCT')
        string(
            defaultValue: 'xxxyyyzz',
            description: 'Short git commit hash for which test results should be collected',
            name: 'GIT_COMMIT')
    }
    agent {
        label 'docker'
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Collect test results') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        case "\${PRODUCT}" in
                            PSMDBO)
                                jobs=("psmdb-operator-gke-version" "psmdb-operator-gke-latest" "psmdb-operator-aws-openshift-latest" "psmdb-operator-aws-openshift-4" "psmdb-operator-eks" "psmdb-operator-minikube")
                                ;;
                            PXCO)
                                jobs=("pxc-operator-gke-version" "pxc-operator-gke-latest" "pxc-operator-aws-openshift-latest" "pxc-operator-aws-openshift-4" "pxc-operator-eks" "pxc-operator-minikube")
                                ;;
                            PGO)
                                jobs=("pgo-operator-gke-version" "pgo-operator-gke-latest" "pgo-operator-aws-openshift-latest" "pgo-operator-aws-openshift-4" "pgo-operator-eks" "pgo-operator-minikube")
                                ;;
                        esac

                        for job in \${jobs[@]}; do
                            echo "Downloading files from job: \$job"
                            mkdir -p \$job/\$GIT_COMMIT
                            pushd \$job/\$GIT_COMMIT >/dev/null
                            aws s3 cp s3://percona-jenkins-artifactory/\$job/\$GIT_COMMIT . --recursive --exclude "*" --include "*.xml"
                            find . -name "*.xml" -exec touch {} \\;
                            echo "---"
                            popd >/dev/null
                        done
                    """
                }
            }
        }
        stage('Publish and archive results') {
            steps {
                step([$class: 'JUnitResultArchiver', testResults: '**/*.xml', healthScaleFactor: 1.0])
                archiveArtifacts '**/*.xml'
            }
        }
    }
}
