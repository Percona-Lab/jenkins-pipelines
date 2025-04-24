def call(moleculeDir, action, scenario, varName, varValue) {
    sh """
        . virtenv/bin/activate
        cd ${moleculeDir}
        ${varName}=${varValue} molecule ${action} -s ${scenario}
    """
}
