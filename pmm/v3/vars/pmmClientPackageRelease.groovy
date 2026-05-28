/**
 * RPM/DEB release suffix for pinned pmm-client package installs.
 * Matches percona/pmm build-client-packages after PMM-7 (RPM_RELEASE/DEB_RELEASE reset to 1 for 3.8.1+).
 */
String call(String clientVersion) {
    if (!(clientVersion ==~ /^3\.\d+\.\d+$/)) {
        return null
    }
    if (clientVersion in ['3.7.1', '3.8.0']) {
        return '8'
    }
    if (compareSemver(clientVersion, '3.8.0') > 0) {
        return '1'
    }
    return '7'
}

private static int compareSemver(String left, String right) {
    def leftParts = left.tokenize('.').collect { it as int }
    def rightParts = right.tokenize('.').collect { it as int }
    for (int i = 0; i < 3; i++) {
        if (leftParts[i] != rightParts[i]) {
            return leftParts[i] <=> rightParts[i]
        }
    }
    return 0
}
