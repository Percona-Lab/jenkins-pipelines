# Remove expired eks clusters.
import logging
import datetime
import boto3
from time import sleep
from boto3.exceptions import Boto3Error
from utils import get_regions_list


def is_cluster_to_terminate(cluster, eks_client):
    cluster = eks_client.describe_cluster(name=cluster)
    if 'team' not in cluster['cluster']['tags'].keys() or ('team' in cluster['cluster']['tags'].keys() and cluster['cluster']['tags']['team'] != 'cloud'):
        return False

    if 'delete-cluster-after-hours' not in cluster['cluster']['tags'].keys():
        return True

    cluster_lifetime = float(cluster['cluster']['tags']['delete-cluster-after-hours'])
    current_time = datetime.datetime.now().timestamp()
    creation_time = datetime.datetime.strptime(str(cluster['cluster']['createdAt']),
                                               "%Y-%m-%d %H:%M:%S.%f%z").timestamp()
    if (current_time - creation_time) / 3600 > cluster_lifetime:
        return True

    return False

def get_clusters_to_terminate(aws_region):
    clusters_for_deletion = []
    eks_client = boto3.client('eks', region_name=aws_region)
    clusters = eks_client.list_clusters()['clusters']
    if not clusters:
        logging.info(f"There are no clusters in cloud")

    for cluster in clusters:
        if is_cluster_to_terminate(cluster, eks_client):
            clusters_for_deletion.append(cluster)

    if not clusters_for_deletion:
        logging.info(f"There are no clusters for deletion")
    return clusters_for_deletion

def wait_for_node_group_delete(autoscaling_client, autoscaling_group_name):
    timeout = 300  # 5 min
    attempt = 0
    sleep_time = 10
    attempts = timeout // sleep_time

    while attempt < attempts:
        try:
            status_info = autoscaling_client.describe_auto_scaling_groups(AutoScalingGroupNames=[autoscaling_group_name])['AutoScalingGroups']
        except IndexError as e:
            logging.info(f"Node group {autoscaling_group_name} was successfully deleted.")
            break
        logging.info(f"Node group {autoscaling_group_name} deletion. "
                     f"Attempt {attempt}/{attempts}. Sleeping {sleep_time} seconds.")
        sleep(sleep_time)
        attempt += 1
    else:
        logging.error(f"Node group {autoscaling_group_name} was not deleted in {timeout} seconds.")

def delete_nodegroup(aws_region, cluster_name):
    autoscaling_client = boto3.client('autoscaling', region_name=aws_region)
    ec2 = boto3.resource('ec2')

    autoscaling_group_name = ""
    for instance in ec2.instances.all():
        tags = instance.tags
        tags_dict = {item['Key']: item['Value'] for item in tags}
        if cluster_name not in tags_dict['Name']:
            continue
        state = instance.state['Name']
        if tags_dict['alpha.eksctl.io/cluster-name'] and tags_dict[
            'alpha.eksctl.io/cluster-name'] == cluster_name and state == 'running':
            autoscaling_group_name = tags_dict['aws:autoscaling:groupName']
            break
        else:
            continue

    try:
        autoscaling_client.delete_auto_scaling_group(AutoScalingGroupName=autoscaling_group_name, ForceDelete=True)
        wait_for_node_group_delete(autoscaling_client, autoscaling_group_name)
    except Boto3Error as e:
        logging.error(f"Deleting autoscaling group {autoscaling_group_name} failed with error: {e}")


def delete_cluster(aws_region, cluster_name):
    eks_client = boto3.client('eks', region_name=aws_region)
    eks_client.delete_cluster(name=cluster_name)
    wait_for_cluster_delete(eks_client, cluster_name)

def wait_for_cluster_delete(eks_client, cluster_name):
    timeout = 300  # 5 min
    attempt = 0
    sleep_time = 10
    attempts = timeout // sleep_time

    while attempt < attempts:
        try:
            status = eks_client.describe_cluster(name=cluster_name)['cluster']['status']
        except eks_client.exceptions.ResourceNotFoundException:
            logging.info(f"Cluster {cluster_name} was successfully deleted.")
            break
        logging.info(f"Cluster {cluster_name} status is {status}. "
                     f"Attempt {attempt}/{attempts}. Sleeping {sleep_time} seconds.")
        sleep(sleep_time)
        attempt += 1
    else:
        logging.error(f"Cluster {cluster_name} was not deleted in {timeout} seconds.")

def terminate_cluster(cluster_name, aws_region):
    delete_nodegroup(aws_region, cluster_name)
    delete_cluster(aws_region, cluster_name)


def lambda_handler(event, context):
    aws_regions = get_regions_list()

    for aws_region in aws_regions:
        logging.info(f"Searching for resources to remove in {aws_region}.")
        clusters = get_clusters_to_terminate(aws_region)

        for cluster in clusters:
            logging.info(f"Terminating {cluster}")
            terminate_cluster(cluster_name=cluster, aws_region=aws_region)