def call(moleculeDir, action, scenario) {
    sh """
        . virtenv/bin/activate
        cd ${moleculeDir}
        ansible --version
        molecule --version
        molecule ${action} -s ${scenario}
    """
}
