#!/usr/bin/env python
import digitalocean
import datetime
import sys
import argparse

MAX_LIVETIME = 1
PMM_TAG = 'jenkins-pmm'

manager = digitalocean.Manager()

def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument('-o', '--only', help="Remove only VM with this name or IP")

    args = parser.parse_args()

    return args

def remove_old_droplets():
    for droplet in manager.get_all_droplets(tag_name=PMM_TAG):
        created = datetime.datetime.strptime(droplet.created_at,"%Y-%m-%dT%H:%M:%SZ")
        current = datetime.datetime.now()
        droplet_delta = current - created
        if droplet_delta.days > MAX_LIVETIME:
            print(f'It\'s time to delete droplet: {droplet.name}')
            remove_droplet(droplet)


def remove_droplet_by_name_or_ip(droplet_ident):
    for droplet in manager.get_all_droplets(tag_name=PMM_TAG):
        if droplet_ident == droplet.name or droplet_ident == droplet.ip_address:
            remove_droplet(droplet)

def remove_droplet(droplet):
    print(f'Delete droplet {droplet.name} with IP: {droplet.ip_address}')
    droplet.destroy()

def main():
    args = parse_args()
    if not args.only:
        remove_old_droplets()
    else:
        remove_droplet_by_name_or_ip(args.only)




if __name__ == "__main__":
   main()
