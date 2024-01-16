# Remove unsused users
import logging
import datetime
import boto3

def is_user_to_terminate(client,user):
    if 'openshift' not in user['UserName']:
        return False

    tags = client.list_user_tags(UserName=user['UserName'])['Tags']
    tags_dict = {item['Key']: item['Value'] for item in tags}

    for key in tags_dict.keys():
        if 'kubernetes.io/cluster/openshift' not in key:
            return False

    current_time = datetime.datetime.now().timestamp()

    creation_time = int(user['CreateDate'].timestamp())
    if (current_time - creation_time) / 3600 > 24:
        return True
    return False


def get_user_for_deletion(client):
    users_for_deletion = []

    users = client.list_users()
    for user in users['Users']:
        if is_user_to_terminate(client, user):
            users_for_deletion.append(user['UserName'])
    if not users_for_deletion:
        logging.info(f"There are no users for deletion")

    return users_for_deletion


def delete_user(client, user_name):
    try:
        client.delete_user(UserName=user_name)
    except client.exceptions.NoSuchEntityException as e:
        logging.error(f"Delete of user failed with error: {e}")

def delete_user_policies(client, user_name):
    user_policies = client.list_user_policies(UserName=user_name)['PolicyNames']
    if user_policies:
        for policy in user_policies:
            client.delete_user_policy(UserName=user_name, PolicyName=policy)

def delete_user_access_keys(client, user_name):
    user_access_keys = client.list_access_keys(UserName=user_name)['AccessKeyMetadata']
    if user_access_keys:
        for key in user_access_keys:
            client.delete_access_key(UserName=user_name, AccessKeyId=key['AccessKeyId'])


def lambda_handler(event, context):
    client = boto3.client('iam')
    user_names = get_user_for_deletion(client)
    for user_name in user_names:
        logging.info(f"Deleting user {user_name}.")
        delete_user_policies(client,user_name)
        delete_user_access_keys(client, user_name)
        delete_user(client, user_name)



