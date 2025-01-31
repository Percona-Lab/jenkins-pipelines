import boto3
import json

iam = boto3.client('iam')

def get_oidc_providers():
    response = iam.list_open_id_connect_providers()
    return [provider['Arn'] for provider in response['OpenIDConnectProviderList']]

def is_provider_used(provider_arn):
    """
    Check if an OIDC provider is referenced in the trust policy of any IAM role.
    """
    paginator = iam.get_paginator('list_roles')
    for page in paginator.paginate():
        for role in page['Roles']:
            # Fetch the trust policy of the role
            trust_policy = iam.get_role(RoleName=role['RoleName'])['Role']['AssumeRolePolicyDocument']
            trust_policy_statements = trust_policy.get('Statement', [])

            # Check if the provider ARN is in the trust policy
            for statement in trust_policy_statements:
                if 'Federated' in statement.get('Principal', {}):
                    if provider_arn == statement['Principal']['Federated']:
                        return True
    return False

def delete_unused_providers():
    """
    Delete OIDC providers that are not used in any IAM role.
    """
    providers = get_oidc_providers()
    for provider in providers:
        if not is_provider_used(provider):
            print(f"Deleting unused provider: {provider}")
            iam.delete_open_id_connect_provider(OpenIDConnectProviderArn=provider)
        else:
            print(f"Provider is in use: {provider}")

def lambda_handler(event, context):
    try:
        delete_unused_providers()
        return {
            "statusCode": 200,
            "body": json.dumps("Unused OIDC providers deleted successfully.")
        }
    except Exception as e:
        print(f"Error: {str(e)}")
        return {
            "statusCode": 500,
            "body": json.dumps(f"Error: {str(e)}")
        }