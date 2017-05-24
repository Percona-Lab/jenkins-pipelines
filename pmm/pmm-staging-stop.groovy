def VMList = ''

pipeline {
    agent {
        label 'virtualbox'
    }
    parameters {
        string(
            defaultValue: 'list-all-vms',
            description: 'Name or IP of VM to stop. Also you can set "list-all-vms" value, in this case list of current VMs will be shown and pipeline will ask you VM again.',
            name: 'VM')
    }

    stages {
        stage('Ask input') {
            steps {
                script {
                    VMList = sh returnStdout: true, script: '''
                        set +o xtrace

                        for VM_NAME in $(VBoxManage list runningvms | cut -d '"' -f 2); do
                            IP=$(
                                grep eth0: /tmp/$VM_NAME-console.log \
                                    | cut -d "|" -f 4 \
                                    | sed -e "s/ //g"
                            )
                            OWNER=$(
                                VBoxManage showvminfo $VM_NAME \
                                | grep Groups: \
                                | sed -r "s/Groups:\\s+//"
                            )
                            printf "$VM_NAME\\t$IP\\t$OWNER\\n"
                        done
                    '''
                    if ( "${VM}" == "list-all-vms" ) {
                        echo """
                            What VM do you want to stop?
                            please copy VM name below and press 'Input requested' button
                        """
                        echo "${VMList}"
                        timeout(time:10, unit:'MINUTES') {
                            VM = input message: 'What VM do you want to stop?', parameters: [string(defaultValue: '', description: '', name: 'Name or IP')]
                        }
                    }
                    if ( !VMList.toLowerCase().contains(VM.toLowerCase())) {
                        echo 'Unknown VM'
                        error 'Unknown VM'
                    }
                }
            }
        }

        stage('Destroy VM') {
            steps {
                sh """
                    set -o errexit
                    VM_NAME=\$(echo '${VMList}' | grep '${VM}' | awk '{print\$1}')
                    VBoxManage controlvm "\$VM_NAME" poweroff
                    sleep 10
                    VBoxManage unregistervm --delete "\$VM_NAME"
                """
            }
        }
    }
}
