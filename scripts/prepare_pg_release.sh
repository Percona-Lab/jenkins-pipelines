#!/bin/bash
PG_VER=$1

if [ -z "$PG_VER" ]; then
    echo "Please provide postgresql version like pg-15.3 or pg-15"
    exit 1
fi

if [[ $PG_VER =~ pg-[0-9]+\.[0-9]+$ ]]; then
  PG_MAJ=0
else
  PG_MAJ=1
fi

mkdir -p /srv/UPLOAD/POSTGRESQL_SYNC/$PG_VER
for dir in $(cat ./ppg_packages.txt); do
    if [[ $PG_MAJ -eq 1 ]] && [[ $dir == *"experimental/BUILDS/postgresql_deps"* ]]; then
        continue
    else
        cp -r /srv/UPLOAD/$dir/* /srv/UPLOAD/POSTGRESQL_SYNC/$PG_VER/    
    fi
done

echo "Synced $PG_VER"
echo "PATH_TO_SYNC=/srv/UPLOAD/POSTGRESQL_SYNC/$PG_VER" >> pg.properties
