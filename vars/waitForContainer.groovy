def call(String CONTAINER_NAME, String LOGS) {
    sh """
        set -o errexit
        set -o xtrace
        export CONTAINER_NAME=${CONTAINER_NAME}
        export LOGS="${LOGS}"
        attempt=0
        while [ \$attempt -le 30 ]; do
            attempt=\$(( \$attempt + 1 ))
            echo "Waiting for ${CONTAINER_NAME} to be up (attempt: \$attempt)..."
            result=\$(docker logs ${CONTAINER_NAME} 2>&1)
            if grep -q "${LOGS}" <<< \$result ; then
              echo "${CONTAINER_NAME} is ready!"
              break
            fi
            sleep 1
        done;
    """
}