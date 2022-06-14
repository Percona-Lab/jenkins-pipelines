#!/bin/bash

JENKINS_HOST=${JHostName}
JENKINS_EIP=${MasterIP_AllocationId}
JENKINS_VOLUME_ID=${JDataVolume}
INSTANCE_ID=$(curl -s http://169.254.169.254/latest/meta-data/instance-id)
INSTANCE_REGION=$(curl -s http://169.254.169.254/latest/meta-data/placement/availability-zone | sed -e 's/[a-z]$//')

set -o xtrace
set -o errexit

setup_aws() {
    aws ec2 associate-address \
        --region $INSTANCE_REGION \
        --instance-id $INSTANCE_ID \
        --allocation-id $JENKINS_EIP \
        --allow-reassociation
}

install_software() {
    wget -O /etc/yum.repos.d/jenkins.repo https://pkg.jenkins.io/redhat-stable/jenkins.repo
    rpm --import https://pkg.jenkins.io/redhat-stable/jenkins.io.key

    until yum makecache; do
        sleep 1
        echo try again
    done

    yum -y update --security
    amazon-linux-extras install -y nginx1.12
    amazon-linux-extras install -y epel
    yum -y install java-1.8.0-openjdk jenkins-2.289.2 certbot git yum-cron aws-cli xfsprogs

    sed -i 's/update_cmd = default/update_cmd = security/' /etc/yum/yum-cron.conf
    sed -i 's/apply_updates = no/apply_updates = yes/'     /etc/yum/yum-cron.conf
    echo "exclude=java*" >> /etc/yum/yum-cron.conf
    chkconfig yum-cron on
    service yum-cron start
}

volume_state() {
    aws ec2 describe-volumes \
        --volume-id $JENKINS_VOLUME_ID \
        --region $INSTANCE_REGION \
        --query 'Volumes[].State' \
        --output text
}

mount_data_partition() {
    aws ec2 detach-volume \
        --region $INSTANCE_REGION \
        --volume-id $JENKINS_VOLUME_ID \
        || :
    while true; do
        [ "x$(volume_state)" = "xavailable" ] && break || sleep 1
    done

    aws ec2 attach-volume \
        --region $INSTANCE_REGION \
        --device /dev/xvdj \
        --instance-id $INSTANCE_ID \
        --volume-id $JENKINS_VOLUME_ID
    while true; do
        [ "x$(volume_state)" = "xin-use" ] && break || sleep 1
    done
    while true; do
        [ -e /dev/xvdj ] && break || sleep 1
    done

    mkfs.xfs -L DATA /dev/xvdj || :
    echo "/dev/xvdj /mnt xfs defaults,noatime,nofail 0 0" | tee -a /etc/fstab
    mount /mnt
}

start_jenkins() {
    sysctl net.ipv4.tcp_fin_timeout=15
    sysctl net.ipv4.tcp_tw_reuse=1
    sysctl net.ipv6.conf.all.disable_ipv6=1
    sysctl net.ipv6.conf.default.disable_ipv6=1
    cat <<-EOF | tee /etc/security/limits.d/jenkins.conf
		jenkins    soft    core      unlimited
		jenkins    hard    core      unlimited
		jenkins    soft    fsize     unlimited
		jenkins    hard    fsize     unlimited
		jenkins    soft    nofile    4096
		jenkins    hard    nofile    8192
		jenkins    soft    nproc     30654
		jenkins    hard    nproc     30654
	EOF

    install -o jenkins -g jenkins -d /mnt/$JENKINS_HOST
    install -o jenkins -g jenkins -d /mnt/$JENKINS_HOST/init.groovy.d
    chown -R jenkins:jenkins /mnt/$JENKINS_HOST

    printf "127.0.0.1 $(hostname) $(hostname -A)\n10.30.6.220 vbox-01.ci.percona.com\n10.30.6.9 repo.ci.percona.com\n" \
        | tee -a /etc/hosts
    sed -i 's^"-Djava.awt.headless=true"^"-Djava.awt.headless=true -Xms2048m -Xmx4096m -server -XX:+AlwaysPreTouch -verbose:gc -Xloggc:$JENKINS_HOME/gc-%t.log -XX:NumberOfGCLogFiles=5 -XX:+UseGCLogFileRotation -XX:GCLogFileSize=20m -XX:+PrintGC -XX:+PrintGCDateStamps -XX:+PrintGCDetails -XX:+PrintHeapAtGC -XX:+PrintGCCause -XX:+PrintTenuringDistribution -XX:+PrintReferenceGC -XX:+PrintAdaptiveSizePolicy -XX:+UseConcMarkSweepGC -XX:+ExplicitGCInvokesConcurrentAndUnloadsClasses -XX:+CMSParallelRemarkEnabled -XX:+ParallelRefProcEnabled -XX:+CMSClassUnloadingEnabled -XX:+ScavengeBeforeFullGC -XX:+CMSScavengeBeforeRemark -XX:NewSize=512m -XX:MaxNewSize=3g -XX:NewRatio=2 -Dorg.jenkinsci.plugins.durabletask.BourneShellScript.HEARTBEAT_CHECK_INTERVAL=600"^' /etc/sysconfig/jenkins
    echo JENKINS_HOME=/mnt/$JENKINS_HOST \
        | tee -a /etc/sysconfig/jenkins
    chkconfig jenkins on
    systemctl start jenkins

    #echo "/usr/bin/aws s3 sync --sse-kms-key-id alias/jenkins-pmm-backup --sse aws:kms --exclude '*/caches/*' --exclude '*/config-history/nodes/*' --exclude '*/secretFiles/*' --delete /mnt/ s3://backup.cd.percona.com/" > /etc/cron.daily/jenkins-backup
    #chmod 755 /etc/cron.daily/jenkins-backup

    printf "* * * * * root bash -c 'curl -s http://169.254.169.254/latest/meta-data/spot/instance-action | grep action && sh -c \"service jenkins stop; cp /var/log/jenkins/jenkins.log /mnt/jenkins-latest.log; umount /mnt\" || :'\n* * * * * root sleep 30; bash -c 'curl -s http://169.254.169.254/latest/meta-data/spot/instance-action | grep action && sh -c \"service jenkins stop; cp /var/log/jenkins/jenkins.log /mnt/jenkins-latest.log; umount /mnt\" || :'\n" > /etc/cron.d/terminate-check
}

create_fake_ssl_cert() {
    mkdir -p /etc/nginx/ssl
    mkdir -p /mnt/$JENKINS_HOST/ssl
    if [ ! -f /mnt/$JENKINS_HOST/ssl/certificate.key -o ! -f /mnt/$JENKINS_HOST/ssl/certificate.crt ]; then
        echo "
            [ req ]
            distinguished_name = req_distinguished_name
            prompt             = no
            [ req_distinguished_name ]
            O                  = Main Org.
        " | tee /mnt/$JENKINS_HOST/ssl/certificate.conf
        openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
                      -keyout /mnt/$JENKINS_HOST/ssl/certificate.key \
                      -out    /mnt/$JENKINS_HOST/ssl/certificate.crt \
                      -config /mnt/$JENKINS_HOST/ssl/certificate.conf
    fi
    cp /mnt/$JENKINS_HOST/ssl/certificate.key /etc/nginx/ssl/certificate.key
    cp /mnt/$JENKINS_HOST/ssl/certificate.crt /etc/nginx/ssl/certificate.crt
    if [ ! -f /mnt/$JENKINS_HOST/ssl/dhparam-1024.pem ]; then
        openssl dhparam -out /mnt/$JENKINS_HOST/ssl/dhparam-1024.pem 1024
    fi
    cp /mnt/$JENKINS_HOST/ssl/dhparam-1024.pem /etc/nginx/ssl/dhparam.pem
    curl https://letsencrypt.org/certs/isrgrootx1.pem                          > /etc/nginx/ssl/ca-certs.pem
    curl https://letsencrypt.org/certs/lets-encrypt-x1-cross-signed.pem       >> /etc/nginx/ssl/ca-certs.pem
    curl https://letsencrypt.org/certs/letsencryptauthorityx1.pem             >> /etc/nginx/ssl/ca-certs.pem
    curl https://www.identrust.com/certificates/trustid/root-download-x3.html >> /etc/nginx/ssl/ca-certs.pem
}

setup_nginx() {
    sed -i'' -e 's/listen/#listen/' /etc/nginx/nginx.conf
    cat <<-EOF | tee /etc/nginx/conf.d/jenkins.conf
		upstream jenkins {
		  server 127.0.0.1:8080 fail_timeout=0;
		}

		server {
		  listen 80;
		  server_name $JENKINS_HOST;

		  # letsencrypt certificates validation
		  location /.well-known {
		    alias /usr/share/nginx/html/.well-known;
		  }

		  # or redirect to https
		  if (\$uri !~* ^/.well-known) {
		    return 301 https://\$host\$request_uri;
		  }
		}

		server {
		  listen 443 ssl;
		  server_name $JENKINS_HOST;

		  ssl_certificate /etc/nginx/ssl/certificate.crt;
		  ssl_certificate_key /etc/nginx/ssl/certificate.key;
		  ssl_trusted_certificate /etc/nginx/ssl/ca-certs.pem;
		  ssl_dhparam     /etc/nginx/ssl/dhparam.pem;
                 include         /etc/nginx/conf.d/*-list.conf;
                 satisfy         any;

		  location / {
		    proxy_set_header        Host \$host:\$server_port;
		    proxy_set_header        X-Real-IP \$remote_addr;
		    proxy_set_header        X-Forwarded-For \$proxy_add_x_forwarded_for;
		    proxy_set_header        X-Forwarded-Proto \$scheme;
		    proxy_redirect http:// https://;
		    proxy_pass              http://jenkins;
		    # Required for new HTTP-based CLI
		    proxy_http_version 1.1;
		    proxy_request_buffering off;
		    proxy_buffering off; # Required for HTTP-based CLI to work over SSL
		    # workaround for https://issues.jenkins-ci.org/browse/JENKINS-45651
		    add_header 'X-SSH-Endpoint' '$JENKINS_HOST:50022' always;
		  }
		}
	EOF
    chkconfig nginx on
    service nginx start
}

setup_letsencrypt() {
    if [[ -d /mnt/ssl_backup ]]; then
        rsync -aHSv --delete /mnt/ssl_backup/ /etc/letsencrypt/
        certbot renew
    else
        certbot --debug --non-interactive certonly --agree-tos --register-unsafely-without-email --webroot -w /usr/share/nginx/html --keep -d $JENKINS_HOST
    fi
    certbot --debug --non-interactive certonly --agree-tos --register-unsafely-without-email --webroot -w /usr/share/nginx/html --keep -d $JENKINS_HOST
    ln -f -s /etc/letsencrypt/live/$JENKINS_HOST/fullchain.pem /etc/nginx/ssl/certificate.crt
    ln -f -s /etc/letsencrypt/live/$JENKINS_HOST/privkey.pem   /etc/nginx/ssl/certificate.key
    printf '#!/bin/sh\ncertbot renew\nservice nginx restart\nrsync -aHSv --delete /etc/letsencrypt/ /mnt/ssl_backup/\n' > /etc/cron.daily/certbot
    chmod 755 /etc/cron.daily/certbot
    service nginx stop
    sleep 2
    service nginx start
}

setup_dhparam() {
    if [ ! -f /mnt/$JENKINS_HOST/ssl/dhparam-4096.pem ]; then
        openssl dhparam -out /mnt/$JENKINS_HOST/ssl/dhparam-4096.pem 4096
    fi
    cp /mnt/$JENKINS_HOST/ssl/dhparam-4096.pem /etc/nginx/ssl/dhparam.pem
    service nginx restart
}

setup_nginx_allow_list() {
    if [ ! -f /home/ec2-user/copy-nginx-allow-list.sh ]; then
        cat <<-EOF | tee /home/ec2-user/copy-nginx-allow-list.sh
            #!/bin/bash

            PATH_TO_BUILDS="/mnt/$JENKINS_HOST/jobs/manage-nginx-allow-list/builds"
            if [ -d "\$PATH_TO_BUILDS" ]; then
                lastSuccessfulBuildID=\$(grep 'lastSuccessfulBuild' \$PATH_TO_BUILDS/permalinks | awk '{print \$2}')

                PATH_TO_ALLOW_LIST="\$PATH_TO_BUILDS/\$lastSuccessfulBuildID/archive"
                if [ -n "\$PATH_TO_ALLOW_LIST/nginx-white-list.conf" ]; then
                    isChanged=\$(rsync -aHv \$PATH_TO_ALLOW_LIST/nginx-white-list.conf /etc/nginx/conf.d | grep nginx-white-list.conf)
                    if [ -n "\$isChanged" ]; then
                        nginx -s reload
                    fi
                fi
            fi
EOF
        chmod 755 /home/ec2-user/copy-nginx-allow-list.sh
        echo '* * * * * root sleep 30; /home/ec2-user/copy-nginx-allow-list.sh' > /etc/cron.d/sync-nginx-allow-list
    fi
}

setup_ssh_keys() {
    KEYS_LIST="evgeniy.patlan slava.sarzhan alex.miroshnychenko eduardo.casarero santiago.ruiz andrew.siemen serhii.stasiuk vadim.yalovets"

    for KEY in $KEYS_LIST; do
        RETRY="3"
        while [ $RETRY != "0" ]; do
            STATUS=$(curl -Is https://www.percona.com/get/engineer/KEY/$KEY.pub | head -n1 | awk '{print $2}')
            if [[ $STATUS -eq 200 ]]; then
                curl -s https://www.percona.com/get/engineer/KEY/$KEY.pub | tee -a /home/ec2-user/.ssh/authorized_keys
                RETRY="0"
            elif [[ $STATUS -eq 404 ]]; then
                echo "Skipping key $KEY"
                RETRY=0
            else
                echo "Got $STATUS, retrying"
                RETRY=$(($RETRY-1))
            fi
        done
    done
}

main() {
    setup_aws
    setup_ssh_keys
    install_software
    mount_data_partition
    create_fake_ssl_cert
    setup_nginx
    start_jenkins
    setup_dhparam
    setup_letsencrypt
    setup_nginx_allow_list
}

main
exit 0
