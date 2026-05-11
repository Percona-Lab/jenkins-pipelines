def call(moleculeDir, action, scenario, varName, varValue) {
    sh """
        . virtenv/bin/activate
        cd ${moleculeDir}
        ${moleculeEnvPPG()}
        ${varName}=${varValue} molecule ${action} -s ${scenario}
    """
}
