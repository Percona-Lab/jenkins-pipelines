#!/usr/bin/env bash

set +x
set -euo pipefail

command -v globalprotect >/dev/null
command -v expect >/dev/null

expect <<'EXPECT'
log_user 0
set timeout 120
set password $env(NKP_VPN_PASSWORD)
unset env(NKP_VPN_PASSWORD)
proc connect {command password} {
    set connected 0
    set password_sent 0
    spawn -noecho {*}$command
    expect {
        -nocase -re {password[^:]*:} {
            if {$password_sent} {
                puts stderr "GlobalProtect authentication rejected"
                exit 1
            }
            set password_sent 1
            send -- "$password\r"
            exp_continue -continue_timer
        }
        -nocase -re {(authentication failed|invalid credentials|passcode|verification code|saml|browser)} {
            puts stderr "GlobalProtect requires valid non-interactive credentials"
            exit 1
        }
        -re {(^|[[:space:]])[Cc]onnected([[:space:].]|$)} {
            set connected 1
            exp_continue -continue_timer
        }
        eof {
            set result [wait]
            if {[lindex $result 2] != 0 || [lindex $result 3] != 0 || !$connected} {
                puts stderr "GlobalProtect connection failed"
                exit 1
            }
        }
        timeout {
            puts stderr "GlobalProtect connection timed out"
            exit 1
        }
    }
}
connect [list globalprotect connect --portal $env(NKP_VPN_PORTAL) --username $env(NKP_VPN_USER)] $password
connect [list globalprotect connect --gateway $env(NKP_VPN_GATEWAY)] $password
EXPECT

globalprotect show --status |
    grep -Eq '(^|[[:space:]])[Cc]onnected([[:space:].]|$)'