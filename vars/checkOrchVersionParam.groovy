def call() {
    sh """
        if echo "${ORCHESTRATOR_VERSION}" | grep -qE '^[0-9]+(\\.[0-9]+)+-[0-9]+'; then
            echo "ORCHESTRATOR_VERSION ${ORCHESTRATOR_VERSION} has expected pattern"
        else
            echo "\${ORCHESTRATOR_VERSION} is unexpected pattern for ORCHESTRATOR_VERSION. Please use version with the release information like 3.2.6-7"
            exit 1
        fi
    """
}
