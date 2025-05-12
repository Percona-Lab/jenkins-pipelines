def call(moleculeDir, action, scenario) {
    sh """
        . virtenv/bin/activate
        cd ${moleculeDir}
        molecule ${action} -s ${scenario}
    """
}
