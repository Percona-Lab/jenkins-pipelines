def userInput = ''
def VMList = ''

stage('Ask input') {
    node('virtualbox') {
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
                        | sed -r "s/Groups:\\s+//; s/,\\/pmm-staging//"
                )
                printf "$VM_NAME\\t$IP\\t$OWNER\\n"
            done
        '''
        echo """
            What VM do you want to stop?
            please copy VM name below and press 'Input requested' button

$VMList
        """
    }

    timeout(time:10, unit:'MINUTES') {
        userInput = input message: 'What VM do you want to stop?', parameters: [string(defaultValue: '', description: '', name: 'Name or IP')]
        if ( !VMList.toLowerCase().contains(userInput.toLowerCase())) {
            echo  'Unknown VM'
            error 'Unknown VM'
        }
    }
}

node('virtualbox') {
    stage('Stop VM') {
        sh """
            set -o errexit
            VM_NAME=\$(echo '$VMList' | grep '$userInput' | awk '{print\$1}')
            VBoxManage controlvm "\$VM_NAME" poweroff
            VBoxManage unregistervm --delete "\$VM_NAME"
        """
    }
}
