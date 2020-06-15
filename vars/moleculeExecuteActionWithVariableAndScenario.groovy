def call(moleculeDir, action, scenario, varName, varValue) {
    sh """
        cd ${moleculeDir}
        ${varName}=${varValue} molecule ${action} -s ${scenario}
    """
}
