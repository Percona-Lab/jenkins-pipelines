/**
 * OpenShift SSL certificate management library for automated certificate provisioning.
 *
 * This library provides comprehensive SSL/TLS certificate management for OpenShift clusters,
 * supporting both Let's Encrypt (via cert-manager) and AWS ACM certificate providers.
 *
 * @since 1.0.0
 */

/**
 * Installs cert-manager operator in the OpenShift cluster.
 *
 * cert-manager is a Kubernetes add-on to automate the management and issuance of
 * TLS certificates from various issuing sources including Let's Encrypt.
 *
 * @param config Map containing:
 *   - kubeconfig: Path to kubeconfig file (required)
 *   - namespace: Target namespace (optional, default: 'openshift-operators')
 *   - channel: Operator channel (optional, default: 'stable')
 *   - timeout: Installation timeout in seconds (optional, default: 300)
 *
 * @return Boolean indicating installation success
 */
def installCertManager(Map config = [:]) {
    def params = [
        namespace: 'openshift-operators',
        channel: 'stable',
        timeout: 300
    ] + config

    if (!params.kubeconfig) {
        error 'Missing required parameter: kubeconfig'
    }

    openshiftTools.log('INFO', 'Installing cert-manager operator...')

    try {
        // Set kubeconfig for all operations
        env.KUBECONFIG = params.kubeconfig

        // Check if cert-manager is already installed
        def isInstalled = sh(
            script: "oc get csv -n ${params.namespace} 2>/dev/null | grep -q cert-manager",
            returnStatus: true
        ) == 0

        if (isInstalled) {
            openshiftTools.log('INFO', 'cert-manager operator is already installed')
            return true
        }

        // Create subscription for cert-manager operator
        sh """
            cat << 'EOF' | oc apply -f -
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: cert-manager
  namespace: ${params.namespace}
spec:
  channel: ${params.channel}
  installPlanApproval: Automatic
  name: cert-manager
  source: community-operators
  sourceNamespace: openshift-marketplace
EOF
        """

        // Wait for operator installation
        openshiftTools.log('INFO', 'Waiting for cert-manager operator to be ready...')
        sh """
            sleep 30
            oc wait --for=condition=Succeeded csv \\
                -n ${params.namespace} \\
                -l operators.coreos.com/cert-manager.openshift-operators \\
                --timeout=${params.timeout}s
        """

        openshiftTools.log('INFO', 'cert-manager operator installed successfully')
        return true
    } catch (Exception e) {
        openshiftTools.log('ERROR', "Failed to install cert-manager: ${e.message}")
        return false
    }
}

/**
 * Creates Let's Encrypt ClusterIssuers for certificate generation.
 *
 * Creates both staging and production issuers to support testing and production deployments.
 * Staging issuer should be used for testing to avoid Let's Encrypt rate limits.
 *
 * @param config Map containing:
 *   - email: Email address for Let's Encrypt registration (required)
 *   - kubeconfig: Path to kubeconfig file (required)
 *   - createStaging: Create staging issuer (optional, default: true)
 *   - createProduction: Create production issuer (optional, default: true)
 *
 * @return Boolean indicating success
 */
def createClusterIssuers(Map config = [:]) {
    def params = [
        createStaging: true,
        createProduction: true
    ] + config

    if (!params.email || !params.kubeconfig) {
        error 'Missing required parameters: email and kubeconfig'
    }

    env.KUBECONFIG = params.kubeconfig
    openshiftTools.log('INFO', "Creating Let's Encrypt ClusterIssuers with email: ${params.email}")

    try {
        def issuersYaml = ""

        if (params.createStaging) {
            issuersYaml += """
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-staging
spec:
  acme:
    email: ${params.email}
    server: https://acme-staging-v02.api.letsencrypt.org/directory
    privateKeySecretRef:
      name: letsencrypt-staging-account-key
    solvers:
    - http01:
        ingress:
          class: openshift-default
---
"""
        }

        if (params.createProduction) {
            issuersYaml += """
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-production
spec:
  acme:
    email: ${params.email}
    server: https://acme-v02.api.letsencrypt.org/directory
    privateKeySecretRef:
      name: letsencrypt-production-account-key
    solvers:
    - http01:
        ingress:
          class: openshift-default
"""
        }

        // Apply ClusterIssuers
        sh """
            cat << 'EOF' | oc apply -f -
${issuersYaml}
EOF
        """

        // Wait for ClusterIssuers to be ready
        openshiftTools.log('INFO', 'Waiting for ClusterIssuers to be ready...')
        sleep(time: 10, unit: 'SECONDS')

        def issuersToCheck = []
        if (params.createStaging) issuersToCheck.add('letsencrypt-staging')
        if (params.createProduction) issuersToCheck.add('letsencrypt-production')

        issuersToCheck.each { issuer ->
            sh """
                oc wait --for=condition=Ready clusterissuer ${issuer} --timeout=60s || true
            """
        }

        openshiftTools.log('INFO', 'ClusterIssuers created successfully')
        return true
    } catch (Exception e) {
        openshiftTools.log('ERROR', "Failed to create ClusterIssuers: ${e.message}")
        return false
    }
}

/**
 * Creates a certificate request using cert-manager.
 *
 * @param config Map containing:
 *   - namespace: Kubernetes namespace (required)
 *   - name: Certificate name (required)
 *   - domain: Domain name for the certificate (required)
 *   - kubeconfig: Path to kubeconfig file (required)
 *   - issuer: ClusterIssuer name (optional, default: 'letsencrypt-production')
 *   - secretName: TLS secret name (optional, default: '${name}-tls')
 *   - timeout: Certificate ready timeout (optional, default: 300)
 *
 * @return Map containing certificate details or null on failure
 */
def createCertificate(Map config) {
    def params = [
        issuer: 'letsencrypt-production',
        timeout: 300
    ] + config

    def required = ['namespace', 'name', 'domain', 'kubeconfig']
    required.each { param ->
        if (!params.containsKey(param) || !params[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    params.secretName = params.secretName ?: "${params.name}-tls"
    env.KUBECONFIG = params.kubeconfig

    openshiftTools.log('INFO', "Creating certificate '${params.name}' for domain '${params.domain}'")

    try {
        // Create certificate resource
        sh """
            cat << 'EOF' | oc apply -f -
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: ${params.name}
  namespace: ${params.namespace}
spec:
  secretName: ${params.secretName}
  issuerRef:
    name: ${params.issuer}
    kind: ClusterIssuer
  dnsNames:
  - ${params.domain}
EOF
        """

        // Wait for certificate to be ready
        openshiftTools.log('INFO', "Waiting for certificate to be ready (timeout: ${params.timeout}s)...")
        def ready = sh(
            script: """
                oc wait --for=condition=Ready \\
                    certificate/${params.name} \\
                    -n ${params.namespace} \\
                    --timeout=${params.timeout}s
            """,
            returnStatus: true
        ) == 0

        if (!ready) {
            // Check certificate status for debugging
            sh """
                echo "Certificate status:"
                oc describe certificate ${params.name} -n ${params.namespace}
                echo "Challenge status:"
                oc get challenges -n ${params.namespace}
            """
            error "Certificate not ready after ${params.timeout} seconds"
        }

        openshiftTools.log('INFO', "Certificate '${params.name}' created successfully")
        
        return [
            name: params.name,
            namespace: params.namespace,
            secretName: params.secretName,
            domain: params.domain,
            issuer: params.issuer
        ]
    } catch (Exception e) {
        openshiftTools.log('ERROR', "Failed to create certificate: ${e.message}")
        return null
    }
}

/**
 * Applies a certificate to an OpenShift route.
 *
 * @param config Map containing:
 *   - namespace: Route namespace (required)
 *   - routeName: Name of the route (required)
 *   - certificateSecret: Name of the certificate secret (required)
 *   - domain: Custom domain for the route (required)
 *   - kubeconfig: Path to kubeconfig file (required)
 *   - serviceName: Backend service name (optional, will try to detect)
 *   - servicePort: Backend service port (optional, default: 'https')
 *   - insecurePolicy: Insecure traffic policy (optional, default: 'Redirect')
 *
 * @return Boolean indicating success
 */
def applyCertificateToRoute(Map config) {
    def params = [
        servicePort: 'https',
        insecurePolicy: 'Redirect'
    ] + config

    def required = ['namespace', 'routeName', 'certificateSecret', 'domain', 'kubeconfig']
    required.each { param ->
        if (!params.containsKey(param) || !params[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    env.KUBECONFIG = params.kubeconfig
    openshiftTools.log('INFO', "Applying certificate to route '${params.routeName}' in namespace '${params.namespace}'")

    try {
        // Extract certificates from secret
        sh """
            mkdir -p /tmp/certs-${BUILD_NUMBER}
            
            # Extract certificate and key
            oc get secret ${params.certificateSecret} -n ${params.namespace} \\
                -o jsonpath='{.data.tls\\.crt}' | base64 -d > /tmp/certs-${BUILD_NUMBER}/tls.crt
            oc get secret ${params.certificateSecret} -n ${params.namespace} \\
                -o jsonpath='{.data.tls\\.key}' | base64 -d > /tmp/certs-${BUILD_NUMBER}/tls.key
            
            # Get service CA for reencrypt routes
            oc get cm -n openshift-config-managed service-ca \\
                -o jsonpath='{.data.ca-bundle\\.crt}' > /tmp/certs-${BUILD_NUMBER}/service-ca.crt
        """

        // Detect service name if not provided
        if (!params.serviceName) {
            params.serviceName = sh(
                script: """
                    oc get route ${params.routeName} -n ${params.namespace} \\
                        -o jsonpath='{.spec.to.name}' 2>/dev/null || echo ""
                """,
                returnStdout: true
            ).trim()

            if (!params.serviceName) {
                error "Could not detect service name for route ${params.routeName}"
            }
        }

        // Delete existing route if it exists
        sh """
            oc delete route ${params.routeName} -n ${params.namespace} --ignore-not-found=true
        """

        // Create route with certificate
        sh """
            oc create route reencrypt ${params.routeName} \\
                --service=${params.serviceName} \\
                --cert=/tmp/certs-${BUILD_NUMBER}/tls.crt \\
                --key=/tmp/certs-${BUILD_NUMBER}/tls.key \\
                --dest-ca-cert=/tmp/certs-${BUILD_NUMBER}/service-ca.crt \\
                --hostname=${params.domain} \\
                -n ${params.namespace} \\
                --port=${params.servicePort} \\
                --insecure-policy=${params.insecurePolicy}
        """

        // Cleanup temp files
        sh "rm -rf /tmp/certs-${BUILD_NUMBER}"

        openshiftTools.log('INFO', "Certificate applied to route '${params.routeName}' successfully")
        return true
    } catch (Exception e) {
        openshiftTools.log('ERROR', "Failed to apply certificate to route: ${e.message}")
        sh "rm -rf /tmp/certs-${BUILD_NUMBER} || true"
        return false
    }
}

/**
 * Verifies SSL certificate configuration for a domain.
 *
 * @param config Map containing:
 *   - domain: Domain to verify (required)
 *   - timeout: Connection timeout in seconds (optional, default: 10)
 *   - retries: Number of verification attempts (optional, default: 3)
 *   - retryDelay: Delay between retries in seconds (optional, default: 10)
 *
 * @return Map containing verification results
 */
def verifyCertificate(Map config) {
    def params = [
        timeout: 10,
        retries: 3,
        retryDelay: 10
    ] + config

    if (!params.domain) {
        error 'Missing required parameter: domain'
    }

    openshiftTools.log('INFO', "Verifying SSL certificate for ${params.domain}")

    def verified = false
    def certInfo = [:]
    def lastError = ''

    for (int i = 0; i < params.retries; i++) {
        if (i > 0) {
            openshiftTools.log('INFO', "Retry ${i}/${params.retries - 1} after ${params.retryDelay}s...")
            sleep(time: params.retryDelay, unit: 'SECONDS')
        }

        try {
            // Get certificate details
            def certDetails = sh(
                script: """
                    echo | openssl s_client -connect ${params.domain}:443 \\
                        -servername ${params.domain} 2>/dev/null | \\
                        openssl x509 -noout -subject -issuer -dates 2>/dev/null || echo "FAILED"
                """,
                returnStdout: true
            ).trim()

            if (certDetails && certDetails != 'FAILED') {
                // Parse certificate details
                certDetails.split('\n').each { line ->
                    if (line.startsWith('subject=')) {
                        certInfo.subject = line.replace('subject=', '').trim()
                    } else if (line.startsWith('issuer=')) {
                        certInfo.issuer = line.replace('issuer=', '').trim()
                    } else if (line.startsWith('notBefore=')) {
                        certInfo.notBefore = line.replace('notBefore=', '').trim()
                    } else if (line.startsWith('notAfter=')) {
                        certInfo.notAfter = line.replace('notAfter=', '').trim()
                    }
                }

                // Test HTTPS connectivity
                def httpStatus = sh(
                    script: """
                        curl -I https://${params.domain} --max-time ${params.timeout} \\
                            -o /dev/null -w '%{http_code}' -s
                    """,
                    returnStdout: true
                ).trim()

                certInfo.httpStatus = httpStatus
                certInfo.verified = httpStatus.startsWith('2') || httpStatus.startsWith('3')
                
                if (certInfo.verified) {
                    verified = true
                    openshiftTools.log('INFO', "SSL certificate verified successfully for ${params.domain}")
                    break
                } else {
                    lastError = "HTTP status: ${httpStatus}"
                }
            } else {
                lastError = 'Failed to retrieve certificate information'
            }
        } catch (Exception e) {
            lastError = e.message
        }
    }

    if (!verified) {
        openshiftTools.log('WARN', "SSL certificate verification failed for ${params.domain}: ${lastError}")
    }

    return [
        domain: params.domain,
        verified: verified,
        details: certInfo,
        error: verified ? null : lastError
    ]
}

/**
 * Sets up complete Let's Encrypt SSL configuration for an OpenShift cluster.
 *
 * This is a high-level function that orchestrates the entire Let's Encrypt setup process.
 *
 * @param config Map containing:
 *   - clusterName: Name of the OpenShift cluster (required)
 *   - baseDomain: Base domain (required)
 *   - email: Email for Let's Encrypt (required)
 *   - kubeconfig: Path to kubeconfig file (required)
 *   - useStaging: Use staging certificates (optional, default: false)
 *   - configureConsole: Configure console SSL (optional, default: true)
 *   - configurePMM: Configure PMM SSL if deployed (optional, default: true)
 *
 * @return Map containing setup results
 */
def setupLetsEncrypt(Map config) {
    def params = [
        useStaging: false,
        configureConsole: true,
        configurePMM: true
    ] + config

    def required = ['clusterName', 'baseDomain', 'email', 'kubeconfig']
    required.each { param ->
        if (!params.containsKey(param) || !params[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    openshiftTools.log('INFO', "Setting up Let's Encrypt SSL for cluster ${params.clusterName}")

    def results = [
        certManager: false,
        clusterIssuers: false,
        consoleCert: null,
        pmmCert: null,
        errors: []
    ]

    try {
        // Step 1: Install cert-manager
        results.certManager = installCertManager([
            kubeconfig: params.kubeconfig
        ])

        if (!results.certManager) {
            results.errors.add('Failed to install cert-manager')
            return results
        }

        // Step 2: Create ClusterIssuers
        results.clusterIssuers = createClusterIssuers([
            email: params.email,
            kubeconfig: params.kubeconfig
        ])

        if (!results.clusterIssuers) {
            results.errors.add('Failed to create ClusterIssuers')
            return results
        }

        def issuer = params.useStaging ? 'letsencrypt-staging' : 'letsencrypt-production'

        // Step 3: Configure OpenShift Console SSL
        if (params.configureConsole) {
            def consoleDomain = "console-${params.clusterName}.${params.baseDomain}"
            
            results.consoleCert = createCertificate([
                namespace: 'openshift-console',
                name: 'console-cert',
                domain: consoleDomain,
                kubeconfig: params.kubeconfig,
                issuer: issuer
            ])

            if (results.consoleCert) {
                applyCertificateToRoute([
                    namespace: 'openshift-console',
                    routeName: 'console',
                    certificateSecret: results.consoleCert.secretName,
                    domain: consoleDomain,
                    kubeconfig: params.kubeconfig,
                    serviceName: 'console',
                    servicePort: 'https'
                ])

                // Verify console certificate
                def verification = verifyCertificate([
                    domain: consoleDomain
                ])
                results.consoleCert.verified = verification.verified
            }
        }

        openshiftTools.log('INFO', 'Let\'s Encrypt SSL setup completed')
    } catch (Exception e) {
        openshiftTools.log('ERROR', "Let's Encrypt setup failed: ${e.message}")
        results.errors.add(e.message)
    }

    return results
}

return this
