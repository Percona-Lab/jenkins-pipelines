pipeline {
    agent {
        label 'cli'
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        cron('H * * * *')
    }
    stages {
        stage('Terminate expired instances') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        # pmm3-ami-staging-start runs in us-east-1, which aws-staging-stop-robot doesn't cover
                        aws ec2 describe-instances \
                            --region us-east-1 \
                            --output text \
                            --filters Name=instance-state-name,Values=running,stopped Name=tag:iit-billing-tag,Values=pmm Name=tag-key,Values=stop-after-days \
                            --query 'Reservations[].Instances[].[InstanceId, LaunchTime, Tags[?Key==`stop-after-days`] | [0].Value, Tags[?Key==`Name`] | [0].Value]' \
                            > instances
                        cat instances

                        echo -n > expired
                        while read -r instance launched days name; do
                            # stop-after-days=0 means keep forever
                            if [ "$days" -gt 0 ] && [ $(( $(date -d "$launched" +%s) + days * 86400 )) -lt $(date +%s) ]; then
                                echo "$instance $name" >> expired
                            fi
                        done < instances

                        if [ -s expired ]; then
                            cut -d ' ' -f 1 expired | xargs aws ec2 --region us-east-1 terminate-instances --instance-ids
                        fi
                    '''
                }
                script {
                    env.EXPIRED = readFile('expired').trim()
                }
            }
        }
    }
    post {
        success {
            script {
                if (env.EXPIRED) {
                    slackSend botUser: true, channel: '#pmm-notifications', color: '#00FF00', message: "[${JOB_NAME}]: terminated expired instances:\n${env.EXPIRED}"
                }
            }
        }
        failure {
            slackSend botUser: true, channel: '#pmm-notifications', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}, URL: ${BUILD_URL}"
        }
        cleanup {
            deleteDir()
        }
    }
}
