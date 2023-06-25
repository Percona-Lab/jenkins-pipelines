clusters=[]

void createCluster(String CLUSTER_SUFFIX){
    clusters.add("${CLUSTER_SUFFIX}")

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'openshift-cicd'], file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'openshift4-secrets', variable: 'OPENSHIFT_CONF_FILE')]) {
        sh """
            platform_version=`echo "${params.PLATFORM_VER}" | awk -F. '{ printf("%d%03d%03d%03d\\n", \$1,\$2,\$3,\$4); }';`
            version=`echo "4.12.0" | awk -F. '{ printf("%d%03d%03d%03d\\n", \$1,\$2,\$3,\$4); }';`
            if [ \$platform_version -ge \$version ];then
                POLICY="additionalTrustBundlePolicy: Proxyonly"
                NETWORK_TYPE="OVNKubernetes"
            else
                POLICY=""
                NETWORK_TYPE="OpenShiftSDN"
            fi
            mkdir -p openshift/${CLUSTER_SUFFIX}
cat <<-EOF > ./openshift/${CLUSTER_SUFFIX}/install-config.yaml
\$POLICY
apiVersion: v1
baseDomain: cd.percona.com
compute:
- architecture: amd64
  hyperthreading: Enabled
  name: worker
  platform:
    aws:
      type: m5.2xlarge
  replicas: 3
controlPlane:
  architecture: amd64
  hyperthreading: Enabled
  name: master
  platform: {}
  replicas: 1
metadata:
  creationTimestamp: null
  name: openshift4-custom-jenkins-${CLUSTER_SUFFIX}-$BUILD_NUMBER
networking:
  clusterNetwork:
  - cidr: 10.128.0.0/14
    hostPrefix: 23
  machineNetwork:
  - cidr: 10.0.0.0/16
  networkType: \$NETWORK_TYPE
  serviceNetwork:
  - 172.30.0.0/16
platform:
  aws:
    region: eu-west-2
    userTags:
      iit-billing-tag: openshift
      delete-cluster-after-hours: 8
      team: cloud
      product: pxc-operator
      
publish: External
EOF
            cat $OPENSHIFT_CONF_FILE >> ./openshift/${CLUSTER_SUFFIX}/install-config.yaml
        """

        sshagent(['aws-openshift-41-key']) {
            sh """
                /usr/local/bin/openshift-install create cluster --dir=./openshift/${CLUSTER_SUFFIX}
                export KUBECONFIG=./openshift/${CLUSTER_SUFFIX}/auth/kubeconfig
            """
        }
    }
}

void shutdownCluster(String CLUSTER_SUFFIX) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'openshift-cicd'], file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'openshift-secret-file', variable: 'OPENSHIFT-CONF-FILE')]) {
        sshagent(['aws-openshift-41-key']) {
            sh """
                source $HOME/google-cloud-sdk/path.bash.inc
                export KUBECONFIG=$WORKSPACE/openshift/$CLUSTER_SUFFIX/auth/kubeconfig
                for namespace in \$(kubectl get namespaces --no-headers | awk '{print \$1}' | grep -vE "^kube-|^openshift" | sed '/-operator/ s/^/1-/' | sort | sed 's/^1-//'); do
                    kubectl delete deployments --all -n \$namespace --force --grace-period=0 || true
                    kubectl delete sts --all -n \$namespace --force --grace-period=0 || true
                    kubectl delete replicasets --all -n \$namespace --force --grace-period=0 || true
                    kubectl delete poddisruptionbudget --all -n \$namespace --force --grace-period=0 || true
                    kubectl delete services --all -n \$namespace --force --grace-period=0 || true
                    kubectl delete pods --all -n \$namespace --force --grace-period=0 || true
                done
                kubectl get svc --all-namespaces || true

                /usr/local/bin/openshift-install destroy cluster --dir=./openshift/$CLUSTER_SUFFIX || true
            """
        }
    }
}

pipeline {
    parameters {
        string(
            defaultValue: '4.11.41',
            description: 'OpenShift version to use',
            name: 'PLATFORM_VER')
        string(
            defaultValue: '3',
            description: 'Number of hours to keep the cluster alive',
            name: 'CLUSTER_DURATION')
    }
    agent {
         label 'docker'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '20', artifactNumToKeepStr: '20'))
        skipDefaultCheckout()
    }

    stages {
        stage('Prepare') {
            steps {
                sh '''
                    if [ ! -d $HOME/google-cloud-sdk/bin ]; then
                        rm -rf $HOME/google-cloud-sdk
                        curl https://sdk.cloud.google.com | bash
                    fi

                    source $HOME/google-cloud-sdk/path.bash.inc
                    gcloud components update kubectl
                    gcloud version

                    curl -s -L https://mirror.openshift.com/pub/openshift-v4/clients/ocp/$PLATFORM_VER/openshift-client-linux-$PLATFORM_VER.tar.gz \
                        | sudo tar -C /usr/local/bin --wildcards -zxvpf -
                    curl -s -L https://mirror.openshift.com/pub/openshift-v4/clients/ocp/$PLATFORM_VER/openshift-install-linux-$PLATFORM_VER.tar.gz \
                        | sudo tar -C /usr/local/bin  --wildcards -zxvpf -
                '''
            }
        }
        stage('Create cluster') {
            parallel {
                stage('cluster1') {
                    steps {
                        createCluster('cluster1')
                        sleep(time:"${params.CLUSTER_DURATION}",unit:"HOURS")
                        shutdownCluster('cluster1')
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                clusters.each { shutdownCluster(it) }
            }
            deleteDir()
        }
    }
}
