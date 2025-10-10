def awsCredentials = [[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: '4422eb0c-26be-4454-8823-fc76b9b3b120']]

void sendMessage(String REPO_NAME, String REPO_URL) {
    TAGS = sh (
        script: "cat ${REPO_NAME}.newtags 2>/dev/null || echo ''",
        returnStdout: true
    ).trim()
 
    if (TAGS) {
        slackSend channel: "${ReposSlackMap["$REPO_NAME"]}", color: '#00FF00', message: "[${JOB_NAME}]: New tag(s) have been created for ${REPO_NAME}, please check the following link(s):\n${TAGS}"
    } else {
        sh (script: "rm -rf ${REPO_NAME}.newtags", returnStdout: false)
    }
}

void checkRepo(String REPO_NAME, String REPO_URL) {
    popArtifactFile("${REPO_NAME}.last")
    sh """
        if [ -f ${REPO_NAME}.last ]; then
            git ls-remote --tags --refs ${REPO_URL} | awk -F'/' '{print \$3}' | sort | uniq > ${REPO_NAME}.current
            cat ${REPO_NAME}.last ${REPO_NAME}.current | sort | uniq -u | awk '{print "$REPO_URL/tree/"\$1}' > ${REPO_NAME}.newtags
            mv -fv ${REPO_NAME}.current ${REPO_NAME}.last
        else
            git ls-remote --tags --refs ${REPO_URL} | awk -F'/' '{print \$3}' | sort | uniq > ${REPO_NAME}.last
        fi
        
    """
    pushArtifactFile("${REPO_NAME}.last")
}

void pushArtifactFile(String FILE_NAME) {
    sh """
        S3_PATH=s3://rel-repo-cache/mysql-monitor
        aws s3 ls \$S3_PATH/${FILE_NAME} || :
        aws s3 cp --quiet ${FILE_NAME} \$S3_PATH/${FILE_NAME} || :
    """
}

void popArtifactFile(String FILE_NAME) {
    sh """
        S3_PATH=s3://rel-repo-cache/mysql-monitor
        aws s3 cp --quiet \$S3_PATH/${FILE_NAME} ${FILE_NAME} || :
    """
}

def GitHubURL='https://github.com'

// Filtered to only MySQL-related repositories
ReposPathMap = [
    'MySQL':                "${GitHubURL}/mysql/mysql-server"
]

// Corresponding Slack channels for MySQL repositories
ReposSlackMap = [
    'MySQL':                "#mysql"
]

pipeline {
    agent {
        label 'micro-amazon'
    }
    triggers {
        // Hourly on months 1,4,7,10 from 2nd week to 4th week (days 8-28)
        cron('H * 8-28 1,4,7,10 *')
    }
    options {
        skipStagesAfterUnstable()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        withCredentials(awsCredentials)
    }
    stages {
        stage('Check MySQL Repos') {
            steps {
                script {
                    ReposPathMap.each { repo_info ->
                        checkRepo("${repo_info.key}", "${repo_info.value}")
                    }
                }
                stash allowEmpty: true, includes: "*.newtags", name: "NewTagsFiles"
            }
        }
        stage('Process New Tags') {
            when {
                expression {
                    // Check if any repo has new tags using same logic as sendMessage function
                    def hasNewTags = false
                    ReposPathMap.each { repo_info ->
                        try {
                            def tags = sh(
                                script: "cat ${repo_info.key}.newtags 2>/dev/null || echo ''",
                                returnStdout: true
                            ).trim()
                            if (tags) {
                                hasNewTags = true
                            }
                        } catch (Exception e) {
                            // File doesn't exist, continue
                        }
                    }
                    return hasNewTags
                }
            }
            steps {
                script {
                    echo "New tags detected, downloading and executing processing script..."
                    
                    // Download the script
                    sh """
                        curl -fsSL -o gen_csv_diff.sh https://raw.githubusercontent.com/VarunNagaraju/sh/refs/heads/master/gen_csv_diff.sh
                        chmod +x gen_csv_diff.sh
                    """
                    
                    // Create directory for CSV output
                    sh "mkdir -p csv_output"
                    
                    // Clone mysql-server repository once
                    sh """
                        git clone ${ReposPathMap['MySQL']} mysql-server-repo
                    """
                    
                    // Define version patterns to track
                    def versionPatterns = ['mysql-5.7', 'mysql-8.0', 'mysql-8.4']
                    
                    versionPatterns.each { versionPattern ->
                        echo "Processing version series: ${versionPattern}"
                        
                        // Get latest two tags for this version series
                        def tags = sh(
                            script: """
                                git -C mysql-server-repo tag -l '${versionPattern}.*' | sort -V | tail -2
                            """,
                            returnStdout: true
                        ).trim().split('\n')
                        
                        if (tags.size() >= 2) {
                            def oldTag = tags[-2].trim()
                            def newTag = tags[-1].trim()
                            
                            if (oldTag && newTag) {
                                echo "Comparing ${versionPattern}: ${oldTag} -> ${newTag}"
                                
                                // Execute script with version-specific parameters
                                sh """
                                    cd mysql-server-repo
                                    ../gen_csv_diff.sh ${oldTag} ${newTag} ../csv_output
                                """
                            } else {
                                echo "Could not determine tags for ${versionPattern}"
                            }
                        } else {
                            echo "Not enough tags found for ${versionPattern} (found: ${tags.size()})"
                        }
                    }
                }
            }
        }
        stage('Sending notifications') {
            steps {
                unstash "NewTagsFiles"
                script {
                    ReposPathMap.each { repo_info ->
                        sendMessage("${repo_info.key}", "${repo_info.value}")
                    }
                }
            }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: '*.newtags, csv_output/**/*.csv', allowEmptyArchive: true
        }
    }
}
