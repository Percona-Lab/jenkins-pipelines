def call(operatingSystems, moleculeDir) {
    posts = [:]
    operatingSystems.each { os ->
     posts["${os}"] = {
        sh """
            . virtenv/bin/activate
            cd ${moleculeDir}
            export driver=ec2
            molecule destroy -s ${os}
        """
  }
 }
 parallel posts
}
