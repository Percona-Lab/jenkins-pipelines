"""OpenShift S3 state storage cleanup."""

import boto3
from botocore.exceptions import ClientError
from ..models.config import DRY_RUN
from ..utils import get_logger

logger = get_logger()


def cleanup_s3_state(cluster_name: str, region: str):
    """Clean up S3 state bucket for OpenShift cluster."""
    try:
        s3 = boto3.client("s3", region_name=region)
        sts = boto3.client("sts")

        # Determine S3 bucket name (standard naming convention)
        account_id = sts.get_caller_identity()["Account"]
        bucket_name = f"openshift-clusters-{account_id}-{region}"

        try:
            # List objects with cluster name prefix
            objects = s3.list_objects_v2(Bucket=bucket_name, Prefix=f"{cluster_name}/")

            if "Contents" in objects:
                if DRY_RUN:
                    logger.info(
                        f"[DRY-RUN] Would delete {len(objects['Contents'])} "
                        f"S3 objects for {cluster_name}"
                    )
                else:
                    for obj in objects["Contents"]:
                        s3.delete_object(Bucket=bucket_name, Key=obj["Key"])
                    logger.info(f"Deleted S3 state for {cluster_name}")
        except ClientError as e:
            if "NoSuchBucket" in str(e):
                logger.info(f"S3 bucket {bucket_name} does not exist")
            else:
                raise

    except Exception as e:
        logger.error(f"Error cleaning up S3 state: {e}")
