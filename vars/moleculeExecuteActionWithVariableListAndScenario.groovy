def call(moleculeDir, action, scenario, varList) {
    sh """
        . virtenv/bin/activate
        cd ${moleculeDir}
        ${varList} molecule ${action} -s ${scenario}
    """
}
