"""EKS CloudFormation stack operations."""

from __future__ import annotations
import boto3
from botocore.exceptions import ClientError
from ..models.config import DRY_RUN
from ..utils import get_logger

logger = get_logger()


def get_eks_cloudformation_billing_tag(cluster_name: str, region: str) -> str | None:
    """Check CloudFormation stack for iit-billing-tag."""
    try:
        cfn = boto3.client("cloudformation", region_name=region)
        stack_name = f"eksctl-{cluster_name}-cluster"

        response = cfn.describe_stacks(StackName=stack_name)
        stack_tags = {
            tag["Key"]: tag["Value"] for tag in response["Stacks"][0].get("Tags", [])
        }

        return stack_tags.get("iit-billing-tag")
    except ClientError as e:
        if "does not exist" in str(e):
            logger.warning(f"CloudFormation stack {stack_name} not found in {region}")
            return None
        logger.error(f"Error checking CloudFormation stack tags: {e}")
        return None
    except Exception as e:
        logger.error(f"Unexpected error checking CloudFormation stack: {e}")
        return None


def cleanup_failed_stack_resources(stack_name: str, region: str) -> bool:
    """Manually clean up resources that prevent stack deletion."""
    try:
        cfn = boto3.client("cloudformation", region_name=region)
        ec2 = boto3.client("ec2", region_name=region)

        # Get failed resources from stack events
        events = cfn.describe_stack_events(StackName=stack_name)
        failed_resources = {}

        for event in events["StackEvents"]:
            if event.get("ResourceStatus") == "DELETE_FAILED":
                logical_id = event["LogicalResourceId"]
                if logical_id not in failed_resources:
                    failed_resources[logical_id] = {
                        "Type": event["ResourceType"],
                        "PhysicalId": event.get("PhysicalResourceId"),
                    }

        if not failed_resources:
            return True

        logger.info(
            f"Attempting to clean up {len(failed_resources)} failed resources "
            f"for stack {stack_name}"
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
                    if physical_id.startswith("rtbassoc-"):
                        ec2.disassociate_route_table(AssociationId=physical_id)
                        logger.info(f"Disassociated route table {physical_id}")

                # Clean up routes
                elif resource_type == "AWS::EC2::Route" and physical_id:
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


def delete_eks_cluster_stack(cluster_name: str, region: str) -> bool:
    """Delete EKS cluster by removing its CloudFormation stack."""
    try:
        cfn = boto3.client("cloudformation", region_name=region)
        stack_name = f"eksctl-{cluster_name}-cluster"

        # Check if stack exists and its current status
        try:
            response = cfn.describe_stacks(StackName=stack_name)
            stack_status = response["Stacks"][0]["StackStatus"]
        except ClientError as e:
            if "does not exist" in str(e):
                logger.warning(
                    f"CloudFormation stack {stack_name} not found in {region}"
                )
                return False
            raise

        # Handle DELETE_FAILED status - retry after cleanup
        if stack_status == "DELETE_FAILED":
            if DRY_RUN:
                logger.info(
                    f"[DRY-RUN] Would retry deletion of failed stack {stack_name}"
                )
            else:
                logger.info(
                    f"Stack {stack_name} previously failed deletion, "
                    f"attempting cleanup and retry"
                )
                cleanup_failed_stack_resources(stack_name, region)
                cfn.delete_stack(StackName=stack_name)
                logger.info(f"Retrying deletion of stack {stack_name} after cleanup")
            return True

        # Handle already deleting
        if "DELETE" in stack_status and stack_status != "DELETE_COMPLETE":
            logger.info(f"Stack {stack_name} already deleting (status: {stack_status})")
            return True

        # Initiate deletion for new stacks
        if DRY_RUN:
            logger.info(
                f"[DRY-RUN] Would delete CloudFormation stack {stack_name} "
                f"for EKS cluster {cluster_name} in {region}"
            )
        else:
            logger.info(
                f"Deleting CloudFormation stack {stack_name} "
                f"for EKS cluster {cluster_name} in {region}"
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
