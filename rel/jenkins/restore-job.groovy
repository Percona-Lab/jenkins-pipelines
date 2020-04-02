def awsCredentials = [[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'S3-BACKUP-JENKINS']]

void popArtifactFile(String FILE_NAME) {
    sh """
        S3_PATH=s3://jenkins-percona-jobs-backup
        aws s3 cp --quiet \$S3_PATH/${FILE_NAME} old-job.xml || :
    """
}

pipeline {
     parameters {
        string(
            defaultValue: '',
            description: 'Path to job in S3 bucket',
            name: 'PATH_TO_JOB')
    }
    agent {
        label 'min-centos-7-x64'
    }
    options {
        skipStagesAfterUnstable()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        withCredentials(awsCredentials)
    }
    stages {
        stage('Prepare') {
            steps {
                sh '''
                    sudo yum -y install epel-release curl
                    sudo yum -y install python2-pip
                    sudo pip install awscli
                '''
            }
        }
        stage('Download from S3') {
            steps {
                popArtifactFile("${PATH_TO_JOB}")
                script {
                    if (fileExists('old-job.xml')) {
                        echo "The ${PATH_TO_JOB} file was successfully downloaded from S3 bucket"
                    }
                    else {
                        error('Cannot find old job in S3 bucket. Please check the name of the job.')
                    }
                }
                stash allowEmpty: true, includes: "old-job.xml", name: "job-xml-file"
            }
        }
        stage('Restore Job') {
            steps {
                unstash "job-xml-file"
                withCredentials([usernamePassword(credentialsId: 'SYS-JENKINS-USER', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        JOB_NAME=$(echo "${PATH_TO_JOB}" | awk -F'/' '{print $2}')
                        curl -X POST "https://${USER}:${PASS}@jenkins.percona.com/job/${JOB_NAME%.*}/config.xml" --data-binary "@old-job.xml"
                        echo "The $JOB_NAME job was re-created!"
                    '''
                }
            }
        }
    }
    post {
        always {
            deleteDir()
        }
    }
}

