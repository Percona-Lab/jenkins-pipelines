def call(moleculeDir, action, scenario) {
    sh """
        . virtenv/bin/activate
        cd ${moleculeDir}
        ${moleculeEnvPPG()}
        molecule ${action} -s ${scenario}
    """
}
