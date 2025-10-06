import logging
import datetime
import boto3
import os
import re
from botocore.exceptions import ClientError

# Set logging level to INFO
logger = logging.getLogger()
logger.setLevel("INFO")

# Get environment variable for EKS cluster skip pattern
EKS_SKIP_PATTERN = os.environ.get("EKS_SKIP_PATTERN", "pe-.*")
logger.info(f"EKS_SKIP_PATTERN: {EKS_SKIP_PATTERN}")

# Track EKS clusters marked for deletion per region
eks_clusters_to_delete = {}


def convert_tags_to_dict(tags):
    return {tag["Key"]: tag["Value"] for tag in tags} if tags else {}


def get_eks_cluster_name(tags_dict):
    """Extract EKS cluster name from instance tags"""
    # Check multiple possible tag keys for cluster name
    cluster_keys = ["aws:eks:cluster-name", "eks:eks-cluster-name"]

    for key in cluster_keys:
        if key in tags_dict:
            return tags_dict[key]

    # Check for kubernetes.io/cluster/* tags
    for key in tags_dict.keys():
        if key.startswith("kubernetes.io/cluster/"):
            return key.replace("kubernetes.io/cluster/", "")

    return None


def has_valid_billing_tag(tags_dict, instance_launch_time):
    """
    Check if instance has a valid iit-billing-tag.

    For regular instances: any non-empty value is valid
    For timestamp-based tags: check if Unix timestamp is in the future
    """
    if "iit-billing-tag" not in tags_dict:
        return False

    tag_value = tags_dict["iit-billing-tag"]

    # Empty tag is invalid
    if not tag_value:
        return False

    # Try to parse as Unix timestamp (for EKS auto-expiration)
    try:
        expiration_timestamp = int(tag_value)
        current_timestamp = int(
            datetime.datetime.now(datetime.timezone.utc).timestamp()
        )

        # If it's a valid future timestamp, check if it's expired
        if expiration_timestamp > current_timestamp:
            logger.info(
                f"Instance has valid billing tag with expiration {expiration_timestamp} "
                f"(expires in {expiration_timestamp - current_timestamp} seconds)"
            )
            return True
        else:
            logger.info(
                f"Instance billing tag expired: {expiration_timestamp} < {current_timestamp} "
                f"(expired {current_timestamp - expiration_timestamp} seconds ago)"
            )
            return False
    except ValueError:
        # Not a timestamp, treat as category string (e.g., "pmm-staging", "jenkins-pmm-slave")
        # Any non-empty category string is valid
        logger.info(f"Instance has valid billing tag category: {tag_value}")
        return True


def is_eks_managed_instance(instance, region):
    """Check if instance is managed by EKS and if it should be skipped"""
    tags_dict = convert_tags_to_dict(instance.tags)

    # Check for EKS-related tags
    eks_indicators = [
        "kubernetes.io/cluster/",
        "aws:eks:cluster-name",
        "eks:eks-cluster-name",
        "eks:kubernetes-node-pool-name",
        "aws:ec2:managed-launch",
    ]

    is_eks = False
    for key in tags_dict.keys():
        for indicator in eks_indicators:
            if indicator in key:
                is_eks = True
                break
        if is_eks:
            break

    if not is_eks:
        return False

    # It's an EKS instance, now check billing tag and skip pattern
    cluster_name = get_eks_cluster_name(tags_dict)
    has_billing_tag = has_valid_billing_tag(tags_dict, instance.launch_time)

    # If has valid billing tag, always skip (it's legitimate)
    if has_billing_tag:
        logger.info(
            f"Instance {instance.id} is EKS-managed (cluster: {cluster_name}), "
            f"has valid iit-billing-tag, skipping"
        )
        return True

    # No billing tag - check skip pattern
    if cluster_name and EKS_SKIP_PATTERN:
        try:
            if re.match(EKS_SKIP_PATTERN, cluster_name):
                logger.info(
                    f"Instance {instance.id} is EKS-managed (cluster: {cluster_name}), "
                    f"matches skip pattern '{EKS_SKIP_PATTERN}', skipping"
                )
                return True
            else:
                logger.info(
                    f"Instance {instance.id} is EKS-managed (cluster: {cluster_name}), "
                    f"does NOT match skip pattern '{EKS_SKIP_PATTERN}' and has no valid billing tag, "
                    f"marking cluster for deletion"
                )
                # Track this cluster for deletion
                if region not in eks_clusters_to_delete:
                    eks_clusters_to_delete[region] = set()
                eks_clusters_to_delete[region].add(cluster_name)
                return True  # Skip individual instance termination, we'll delete the whole cluster
        except re.error as e:
            logger.error(
                f"Invalid regex pattern '{EKS_SKIP_PATTERN}': {e}, skipping all EKS instances"
            )
            return True

    # If no cluster name found, skip the instance
    logger.info(
        f"Instance {instance.id} is EKS-managed (cluster: {cluster_name or 'unknown'}), skipping"
    )
    return True


def is_instance_to_terminate(instance):
    # Check if the instance has valid 'iit-billing-tag'
    tags_dict = convert_tags_to_dict(instance.tags)
    has_billing_tag = has_valid_billing_tag(tags_dict, instance.launch_time)

    # Calculate the running time of the instance
    current_time = datetime.datetime.now(datetime.timezone.utc)
    launch_time = instance.launch_time
    running_time = current_time - launch_time

    # Terminate instances without valid 'iit-billing-tag' running for more than 10 minutes
    if not has_billing_tag and running_time.total_seconds() > 600:
        return True
    return False


def cleanup_failed_stack_resources(stack_name, region):
    """Manually clean up resources that prevent stack deletion"""
    try:
        cfn = boto3.client("cloudformation", region_name=region)
        ec2 = boto3.client("ec2", region_name=region)

        # Get failed resources from stack events
        events = cfn.describe_stack_events(StackName=stack_name)
        failed_resources = {}

        for event in events["StackEvents"]:
            if event.get("ResourceStatus") == "DELETE_FAILED":
                logical_id = event["LogicalResourceId"]
                if logical_id not in failed_resources:  # Only keep first occurrence
                    failed_resources[logical_id] = {
                        "Type": event["ResourceType"],
                        "PhysicalId": event.get("PhysicalResourceId"),
                    }

        if not failed_resources:
            return True

        logger.info(
            f"Attempting to clean up {len(failed_resources)} failed resources for stack {stack_name}"
        )

        # Process each failed resource type
        for logical_id, resource in failed_resources.items():
            resource_type = resource["Type"]
            physical_id = resource["PhysicalId"]

            try:
                # Clean up security group ingress rules
                if resource_type == "AWS::EC2::SecurityGroupIngress" and physical_id:
                    sg_id = physical_id.split("|")[0] if "|" in physical_id else None
                    if sg_id and sg_id.startswith("sg-"):
                        response = ec2.describe_security_groups(GroupIds=[sg_id])
                        if response["SecurityGroups"]:
                            sg = response["SecurityGroups"][0]
                            if sg["IpPermissions"]:
                                ec2.revoke_security_group_ingress(
                                    GroupId=sg_id, IpPermissions=sg["IpPermissions"]
                                )
                                logger.info(f"Cleaned up ingress rules for {sg_id}")

                # Clean up route table associations
                elif (
                    resource_type == "AWS::EC2::SubnetRouteTableAssociation"
                    and physical_id
                ):
                    # PhysicalId is the association ID
                    if physical_id.startswith("rtbassoc-"):
                        ec2.disassociate_route_table(AssociationId=physical_id)
                        logger.info(f"Disassociated route table {physical_id}")

                # Clean up routes
                elif resource_type == "AWS::EC2::Route" and physical_id:
                    # PhysicalId format: rtb-xxx_destination
                    parts = physical_id.split("_")
                    if len(parts) == 2 and parts[0].startswith("rtb-"):
                        rtb_id = parts[0]
                        dest_cidr = parts[1]
                        ec2.delete_route(
                            RouteTableId=rtb_id, DestinationCidrBlock=dest_cidr
                        )
                        logger.info(f"Deleted route {dest_cidr} from {rtb_id}")

            except ClientError as e:
                error_code = e.response.get("Error", {}).get("Code", "")
                # Ignore if resource already deleted
                if error_code not in [
                    "InvalidGroup.NotFound",
                    "InvalidAssociationID.NotFound",
                    "InvalidRoute.NotFound",
                ]:
                    logger.warning(
                        f"Could not clean up {resource_type} {physical_id}: {e}"
                    )
            except Exception as e:
                logger.warning(
                    f"Unexpected error cleaning up {resource_type} {physical_id}: {e}"
                )

        return True

    except Exception as e:
        logger.error(f"Error cleaning up failed resources for stack {stack_name}: {e}")
        return False


def delete_eks_cluster_stack(cluster_name, region):
    """Delete EKS cluster by removing its CloudFormation stack"""
    try:
        cfn = boto3.client("cloudformation", region_name=region)

        # Find CloudFormation stack for this cluster
        stack_name = f"eksctl-{cluster_name}-cluster"

        # Check if stack exists and its current status
        try:
            response = cfn.describe_stacks(StackName=stack_name)
            stack_status = response["Stacks"][0]["StackStatus"]
        except ClientError as e:
            if "does not exist" in str(e):
                logger.warning(
                    f"CloudFormation stack {stack_name} not found in {region}, cannot delete cluster {cluster_name}"
                )
                return False
            raise

        # Handle DELETE_FAILED status - retry after cleanup
        if stack_status == "DELETE_FAILED":
            logger.info(
                f"Stack {stack_name} previously failed deletion, attempting cleanup and retry"
            )
            cleanup_failed_stack_resources(stack_name, region)
            # Retry deletion
            cfn.delete_stack(StackName=stack_name)
            logger.info(f"Retrying deletion of stack {stack_name} after cleanup")
            return True

        # Handle already deleting
        if "DELETE" in stack_status and stack_status != "DELETE_COMPLETE":
            logger.info(f"Stack {stack_name} already deleting (status: {stack_status})")
            return True

        # Initiate deletion for new stacks
        logger.info(
            f"Deleting CloudFormation stack {stack_name} for EKS cluster {cluster_name} in {region}"
        )
        cfn.delete_stack(StackName=stack_name)
        logger.info(
            f"Successfully initiated deletion of stack {stack_name} for cluster {cluster_name}"
        )
        return True

    except ClientError as e:
        logger.error(
            f"Failed to delete CloudFormation stack for cluster {cluster_name} in {region}: {e}"
        )
        return False
    except Exception as e:
        logger.error(
            f"Unexpected error deleting cluster {cluster_name} in {region}: {e}"
        )
        return False


def cirrus_ci_add_iit_billing_tag(instance):
    # Convert tags to a dictionary for easier access
    tags_dict = convert_tags_to_dict(instance.tags)

    # Check if the instance has 'CIRRUS_CI' tag set to 'true' and 'iit-billing-tag' is not set
    has_cirrus_ci_tag = tags_dict.get("CIRRUS_CI", "").lower() == "true"
    has_iit_billing_tag = "iit-billing-tag" in tags_dict

    # Extract additional tag values
    instance_name = tags_dict.get("Name")
    cirrus_repo_full_name = tags_dict.get("CIRRUS_REPO_FULL_NAME")
    cirrus_task_id = tags_dict.get("CIRRUS_TASK_ID")

    # If 'CIRRUS_CI' tag is set to 'true' and 'iit-billing-tag' is not set, add 'iit-billing-tag' set to 'CirrusCI'
    if has_cirrus_ci_tag and not has_iit_billing_tag:
        try:
            instance.create_tags(Tags=[{"Key": "iit-billing-tag", "Value": "CirrusCI"}])
            logger.info(
                f"Instance {instance.id} ({instance_name}) tagged with 'iit-billing-tag: CirrusCI'. "
                f"CIRRUS_REPO_FULL_NAME: {cirrus_repo_full_name}, CIRRUS_TASK_ID: {cirrus_task_id}"
            )
        except ClientError as e:
            logger.error(f"Error tagging instance {instance.id}: {e}")


def terminate_instances_in_region(region):
    ec2 = boto3.resource("ec2", region_name=region)
    instances = ec2.instances.filter(
        Filters=[{"Name": "instance-state-name", "Values": ["running"]}]
    )
    terminated_instances = []
    skipped_instances = []

    for instance in instances:
        try:
            # First try to tag CirrusCI instances
            cirrus_ci_add_iit_billing_tag(instance)

            # Skip EKS-managed instances based on pattern and billing tag
            if is_eks_managed_instance(instance, region):
                tags_dict = convert_tags_to_dict(instance.tags)
                cluster_name = get_eks_cluster_name(tags_dict)
                skipped_instances.append(
                    {
                        "InstanceId": instance.id,
                        "Reason": f"EKS-managed (cluster: {cluster_name or 'unknown'})",
                    }
                )
                continue

            # Check if should terminate
            if is_instance_to_terminate(instance):
                instance_info = {
                    "InstanceId": instance.id,
                    "SSHKeyName": instance.key_name,
                    "NameTag": instance.tags[0]["Value"]
                    if instance.tags and "Name" in [tag["Key"] for tag in instance.tags]
                    else None,
                    "AvailabilityZone": instance.placement["AvailabilityZone"],
                }

                try:
                    instance.terminate()
                    terminated_instances.append(instance_info)
                    logger.info(
                        f"Successfully terminated instance {instance.id} in {region}"
                    )
                except ClientError as e:
                    logger.error(
                        f"Failed to terminate instance {instance.id} in {region}: {e}"
                    )
                    skipped_instances.append(
                        {
                            "InstanceId": instance.id,
                            "Reason": f"Permission denied: {str(e)}",
                        }
                    )
        except Exception as e:
            logger.error(f"Error processing instance {instance.id} in {region}: {e}")
            continue

    if skipped_instances:
        logger.info(f"Skipped {len(skipped_instances)} instances in {region}")
        for skipped in skipped_instances[:5]:  # Log first 5 only
            logger.info(f"  - {skipped['InstanceId']}: {skipped['Reason']}")

    return terminated_instances


def lambda_handler(event, context):
    global eks_clusters_to_delete
    eks_clusters_to_delete = {}  # Reset at start of each invocation

    regions = [
        region["RegionName"]
        for region in boto3.client("ec2").describe_regions()["Regions"]
    ]
    terminated_instances_all_regions = []
    deleted_clusters = []

    # Process all instances and identify EKS clusters to delete
    for region in regions:
        try:
            terminated_instances_region = terminate_instances_in_region(region)
            terminated_instances_all_regions.extend(terminated_instances_region)
        except Exception as e:
            logger.error(f"Error processing region {region}: {e}")
            continue

    # Delete EKS clusters that don't match skip pattern AND have no valid billing tag
    for region, clusters in eks_clusters_to_delete.items():
        for cluster_name in clusters:
            try:
                if delete_eks_cluster_stack(cluster_name, region):
                    deleted_clusters.append(f"{cluster_name} ({region})")
            except Exception as e:
                logger.error(f"Error deleting cluster {cluster_name} in {region}: {e}")
                continue

    # Log results
    if terminated_instances_all_regions:
        logger.info("Terminated instances:")
        for instance_info in terminated_instances_all_regions:
            logger.info(
                f"- Instance ID: {instance_info['InstanceId']}, SSH Key: {instance_info['SSHKeyName']}, Name Tag: {instance_info['NameTag']}, Availability Zone: {instance_info['AvailabilityZone']}"
            )
    else:
        logger.info("No instances were terminated.")

    if deleted_clusters:
        logger.info(f"Deleted {len(deleted_clusters)} EKS clusters:")
        for cluster in deleted_clusters:
            logger.info(f"- {cluster}")
    else:
        logger.info("No EKS clusters were deleted.")

    return {
        "statusCode": 200,
        "body": f"Terminated {len(terminated_instances_all_regions)} instances, deleted {len(deleted_clusters)} EKS clusters",
    }
