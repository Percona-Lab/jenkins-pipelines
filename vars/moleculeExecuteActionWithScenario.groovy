def call(moleculeDir, action, scenario) {
    sh """
        cd ${moleculeDir}
        molecule ${action} -s ${scenario}
    """
}
