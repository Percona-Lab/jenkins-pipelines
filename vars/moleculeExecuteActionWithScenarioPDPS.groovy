def call(moleculeDir, action, scenario) {
    sh """
        . /opt/virtenv/bin/activate
        cd ${moleculeDir}
        molecule ${action} -s ${scenario}
    """
}
