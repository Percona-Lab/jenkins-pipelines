def call(String CHANNEL, String COLOR, String MESSAGE) {
    slackSend botUser: true,
    channel: "${CHANNEL}",
    color: "${COLOR}",
    message: "${MESSAGE}"
}
