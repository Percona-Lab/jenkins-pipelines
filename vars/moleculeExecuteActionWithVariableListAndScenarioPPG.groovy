def call(moleculeDir, action, scenario, varList) {
    sh """
        . virtenv/bin/activate
        cd ${moleculeDir}
        ${moleculeEnvPPG()}
        ${varList} molecule ${action} -s ${scenario}
    """
}
