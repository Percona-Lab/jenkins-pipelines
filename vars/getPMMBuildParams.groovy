/**
 * Get the build params used to idenify the VM_NAME, the build author and their slackUserId.
 * Those are quite helpful when we analyze the resource consumption and ownership.
 * 
 * Returns: 
    - VM_NAME(String): a unique virtual machine name
    - OWNER(String): the owner or author of the build (cat take the valur of 'timer' if triggered by the timer)
    - OWNER_SLACK(String): slackUserId or null if impossible to extract it
 *
 * Side effects: it will set the relevant envvars so the developer does not have to.
 * Poor side effects: it will persist the VM_NAME and OWNER to the file system because some parts of 
 * code rely on those values being persisted as files. This a bad idea, especially when done on one-off agents.
 * TODO: refactor poor side effects.
**/

def call(String VM_PREFIX) {
    String VM_NAME = '';
    String OWNER = '';
    String OWNER_SLACK = '';

    if (!VM_PREFIX) {
        error "Error: 'VM_PREFIX' must be a non-empty string"
    }

    wrap([$class: 'BuildUser']) {
        // ONWER => BUILD_USER or BUILD_USER_ID or CHANGE_AUTHOR or CHANGE_ID
        OWNER = (env.BUILD_USER_EMAIL ?: '').split('@')[0] ?: (env.BUILD_USER_ID ?: (env.CHANGE_AUTHOR ?: env.CHANGE_ID))
        OWNER = OWNER ?: 'system'
        OWNER_SLACK = slackUserIdFromEmail(botUser: true, email: env.BUILD_USER_EMAIL ?: env.CHANGE_AUTHOR_EMAIL, tokenCredentialId: 'JenkinsCI-SlackBot-v2')
        VM_NAME = VM_PREFIX + OWNER.replaceAll("[^a-zA-Z0-9_.-]", "") + '-' + (new Date()).format("yyyyMMdd.HHmmss") + '-' + env.BUILD_NUMBER
    }

    env.VM_NAME = VM_NAME
    env.OWNER = OWNER
    env.OWNER_SLACK = OWNER_SLACK

    // Some pipelines rely on those values being persisted as files.
    sh """
        set -x
        echo "${VM_NAME}" > VM_NAME
        echo "${OWNER}" > OWNER_FULL
    """

    return [ VM_NAME, OWNER, OWNER_SLACK ];
}
