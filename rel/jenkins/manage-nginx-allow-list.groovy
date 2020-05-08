@NonCPS
def USER_NAME = currentBuild.rawBuild.getCause(Cause.UserIdCause).getUserId()

isIPok = false

void isValidInet4Address() {
    try {
        sh '''
            ip=$IP
            if [[ $ip =~ ^[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}$ ]]; then
                OIFS=$IFS
                IFS='.'
                ip=($ip)
                IFS=$OIFS

                [[ ${ip[0]} -le 255 && ${ip[1]} -le 255 \
                    && ${ip[2]} -le 255 && ${ip[3]} -le 255 ]]
            else
                exit 1
            fi
        '''
        isIPok = true
        echo "$isIPok"
    }
    catch (Exception err) {
        currentBuild.result = 'FAILURE'
    }
}

pipeline {
     parameters {
        string(
            defaultValue: '',
            description: 'IP for allow list',
            name: 'IP',
            trim: true)
        choice(
            choices: ['Add', 'Delete'],
            description: 'Choose action',
            name: 'ACTION')
    }
    agent {
        label 'micro-amazon'
    }
    options {
        skipStagesAfterUnstable()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }
    stages {
        stage('Add IP') {
            steps {
                isValidInet4Address()
                script {
                    echo "$isIPok"
                    if ( isIPok.toBoolean() == false ) {
                        echo 'IP is not valid'
                        error('IP is not valid')
                    }
                }
                copyArtifacts projectName: "${JOB_NAME}", optional: true, selector: lastSuccessful()

                sh """
                    if [ -f 'nginx-white-list.conf' ]; then
                        isIPpresent=\$(grep 'allow     ${IP};      # was added by ${USER_NAME}' nginx-white-list.conf) || true
                        if [ -n "\$isIPpresent" -a $ACTION == 'Add' ]; then
                            echo  'IP already exists'
                        else
                            if [ $ACTION == 'Add' ]; then
                                echo 'allow     ${IP};      # was added by ${USER_NAME}' >> nginx-white-list.conf
                            else
                                grep -v 'allow     ${IP};      #' nginx-white-list.conf > nginx-white-list.conf.tmp || true
                                mv nginx-white-list.conf.tmp nginx-white-list.conf
                            fi
                        fi
                    else
                        if [ $ACTION == 'Add' ]; then
                            echo 'allow     ${IP};      # was added by ${USER_NAME}' >> nginx-white-list.conf
                        fi
                    fi
                """
                archiveArtifacts artifacts: '*.conf', allowEmptyArchive: true
            }
        }
    }
    post {
        always {
            deleteDir()
        }
    }
}

