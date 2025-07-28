def call(Map config = [:]) {
    // Default configuration
    def defaults = [
        namespace: 'pmm-monitoring',
        pmmVersion: '3.3.0',
        helmChartVersion: '1.4.6',
        adminPassword: 'admin',
        storageSize: '10Gi',
        timeout: '10m',
        dryRun: false
    ]

    // Merge defaults with provided config
    def params = defaults + config

    echo "Deploying PMM ${params.pmmVersion} to OpenShift cluster"

    try {
        // Check prerequisites
        checkPrerequisites()

        // Install Helm if not present
        if (!isHelmInstalled()) {
            installHelm()
        }

        // Create namespace and configure permissions
        setupNamespace(params.namespace)

        // Deploy PMM using Helm
        deployPMM(params)

        // Create OpenShift route
        def routeInfo = createRoute(params.namespace)

        // Wait for deployment to be ready
        waitForDeployment(params.namespace, params.timeout)

        // Generate access information
        def accessInfo = generateAccessInfo(params.namespace, routeInfo, params.adminPassword)

        return accessInfo
    } catch (Exception e) {
        error "Failed to deploy PMM to OpenShift: ${e.message}"
    }
}

def checkPrerequisites() {
    echo 'Checking prerequisites...'

    // Check if oc is available
    def ocVersion = sh(script: 'oc version --client -o json', returnStatus: true)
    if (ocVersion != 0) {
        error 'OpenShift CLI (oc) is not installed or not in PATH'
    }

    // Check if logged in to cluster
    def whoami = sh(script: 'oc whoami', returnStatus: true)
    if (whoami != 0) {
        error "Not logged in to OpenShift cluster. Please run 'oc login' first"
    }

    echo 'Prerequisites check passed'
}

def isHelmInstalled() {
    def helmCheck = sh(script: 'which helm', returnStatus: true)
    return helmCheck == 0
}

def installHelm() {
    echo 'Installing Helm...'
    sh '''
        curl -fsSL https://get.helm.sh/helm-v3.12.3-linux-amd64.tar.gz | \
        sudo tar -C /usr/local/bin --strip-components 1 -xzf - linux-amd64/helm

        helm version
    '''
}

def setupNamespace(String namespace) {
    echo "Setting up namespace: ${namespace}"

    // Create namespace if it doesn't exist
    sh """
        oc create namespace ${namespace} --dry-run=client -o yaml | oc apply -f -
    """

    // Create service account
    sh """
        oc create serviceaccount pmm-server -n ${namespace} --dry-run=client -o yaml | oc apply -f -
    """

    // Grant anyuid SCC for PMM containers
    sh """
        oc adm policy add-scc-to-user anyuid -z pmm-server -n ${namespace}
    """

    echo "Namespace ${namespace} configured successfully"
}

def deployPMM(Map params) {
    echo 'Deploying PMM using Helm...'

    // Add Percona Helm repository
    sh '''
        helm repo add percona https://percona.github.io/percona-helm-charts/
        helm repo update
    '''

    // Prepare Helm values
    def helmValues = """
image:
  tag: ${params.pmmVersion}

service:
  type: ClusterIP

persistence:
  enabled: true
  size: ${params.storageSize}

serviceAccount:
  create: false
  name: pmm-server

pmmEnv:
  DISABLE_TELEMETRY: true

secret:
  pmm_password: ${params.adminPassword}
"""

    writeFile file: 'pmm-values.yaml', text: helmValues

    // Deploy PMM
    def helmCmd = """
        helm upgrade --install pmm percona/pmm \
            --namespace ${params.namespace} \
            --version ${params.helmChartVersion} \
            --values pmm-values.yaml \
            --wait \
            --timeout ${params.timeout}
    """

    if (params.dryRun) {
        helmCmd += ' --dry-run'
    }

    sh helmCmd

    // Clean up values file
    sh 'rm -f pmm-values.yaml'
}

def createRoute(String namespace) {
    echo 'Creating OpenShift route for PMM...'

    def routeYaml = """
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: pmm-https
  namespace: ${namespace}
spec:
  host: ""
  port:
    targetPort: https
  tls:
    termination: passthrough
  to:
    kind: Service
    name: pmm
    weight: 100
"""

    writeFile file: 'pmm-route.yaml', text: routeYaml
    sh 'oc apply -f pmm-route.yaml'
    sh 'rm -f pmm-route.yaml'

    // Get the route URL
    def routeHost = sh(
        script: "oc get route pmm-https -n ${namespace} -o jsonpath='{.spec.host}'",
        returnStdout: true
    ).trim()

    return [host: routeHost, url: "https://${routeHost}"]
}

def waitForDeployment(String namespace, String timeout) {
    echo 'Waiting for PMM deployment to be ready...'

    sh """
        oc wait --for=condition=ready pod -l app.kubernetes.io/name=pmm \
            -n ${namespace} \
            --timeout=${timeout}
    """

    echo 'PMM deployment is ready'
}

def generateAccessInfo(String namespace, Map routeInfo, String adminPassword) {
    def accessInfo = [
        namespace: namespace,
        url: routeInfo.url,
        username: 'admin',
        password: adminPassword,
        route: routeInfo.host
    ]

    echo """
========================================
PMM Successfully Deployed!
========================================
URL: ${accessInfo.url}
Username: ${accessInfo.username}
Password: ${accessInfo.password}
Namespace: ${accessInfo.namespace}

To access PMM:
1. Open ${accessInfo.url} in your browser
2. Login with the credentials above
3. Change the admin password on first login

To check deployment status:
oc get pods -n ${namespace}
========================================
"""

    // Save access info to file if in Jenkins workspace
    if (env.WORKSPACE) {
        def accessFile = "${env.WORKSPACE}/pmm-access-info.txt"
        writeFile file: accessFile, text: """
PMM Access Information
======================
URL: ${accessInfo.url}
Username: ${accessInfo.username}
Password: ${accessInfo.password}
Namespace: ${accessInfo.namespace}
Deployed at: ${new Date()}
"""
        echo "Access information saved to: ${accessFile}"
    }

    return accessInfo
}

// Method to undeploy PMM
def undeploy(String namespace = 'pmm-monitoring') {
    echo "Undeploying PMM from namespace: ${namespace}"

    try {
        sh """
            helm uninstall pmm -n ${namespace} || true
            oc delete route pmm-https -n ${namespace} || true
            oc delete namespace ${namespace} || true
        """
        echo 'PMM successfully removed'
    } catch (Exception e) {
        echo "Warning: Failed to completely remove PMM: ${e.message}"
    }
}
