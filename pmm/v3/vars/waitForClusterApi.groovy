// Waits up to 20 minutes for the cluster API to answer /readyz, so a short outage does not fail
// the stage. A few minutes after a ROSA HCP cluster turns ready, its API hostname can stop
// resolving ("dial tcp: lookup api...: no such host"), and the p3.openshiftapps.com SOA lets
// resolvers cache that NXDOMAIN for 15 minutes, so a retry of a few seconds cannot ride it out.
// cli is the client the agent has: kubectl, or oc.
def call(String cli = 'kubectl') {
    sh """
        set +x
        for i in \$(seq 1 40); do
            # Bounded, so a load balancer that accepts but never answers cannot stall the wait.
            if OUT=\$(${cli} get --raw /readyz --request-timeout=10s 2>&1); then
                exit 0
            fi
            echo "Cluster API not reachable (\${i}/40), retrying in 30s: \$(echo "\${OUT}" | tail -n 1)"
            sleep 30
        done
        echo "ERROR: the cluster API is still not reachable after 20 minutes."
        exit 1
    """
}
