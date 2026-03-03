"""OpenShift Route53 DNS cleanup."""

import boto3
from ..models.config import DRY_RUN, OPENSHIFT_BASE_DOMAIN
from ..utils import get_logger

logger = get_logger()


def cleanup_route53_records(cluster_name: str, region: str):
    """Clean up Route53 DNS records for OpenShift cluster."""
    try:
        route53 = boto3.client("route53")

        # Find the hosted zone for the base domain
        zones = route53.list_hosted_zones()["HostedZones"]
        zone_id = None
        for zone in zones:
            if zone["Name"].rstrip(".") == OPENSHIFT_BASE_DOMAIN:
                zone_id = zone["Id"].split("/")[-1]
                break

        if not zone_id:
            logger.warning(f"Hosted zone for {OPENSHIFT_BASE_DOMAIN} not found")
            return

        # Get all DNS records for this zone
        records = route53.list_resource_record_sets(HostedZoneId=zone_id)[
            "ResourceRecordSets"
        ]

        # Find records for this cluster
        changes = []
        for record in records:
            name = record["Name"].rstrip(".")
            # Match api.cluster.domain or *.apps.cluster.domain
            if (
                f"api.{cluster_name}.{OPENSHIFT_BASE_DOMAIN}" in name
                or f"apps.{cluster_name}.{OPENSHIFT_BASE_DOMAIN}" in name
            ):
                changes.append({"Action": "DELETE", "ResourceRecordSet": record})

        # Log each DNS record being deleted
        for change in changes:
            record = change["ResourceRecordSet"]
            if DRY_RUN:
                logger.info(
                    "Would DELETE route53_record",
                    extra={
                        "dry_run": True,
                        "record_name": record["Name"].rstrip("."),
                        "record_type": record["Type"],
                        "cluster_name": cluster_name,
                        "hosted_zone_id": zone_id,
                    },
                )
            else:
                logger.info(
                    "DELETE route53_record",
                    extra={
                        "record_name": record["Name"].rstrip("."),
                        "record_type": record["Type"],
                        "cluster_name": cluster_name,
                        "hosted_zone_id": zone_id,
                    },
                )

        if changes and not DRY_RUN:
            route53.change_resource_record_sets(
                HostedZoneId=zone_id, ChangeBatch={"Changes": changes}
            )
            logger.info(
                f"Deleted {len(changes)} Route53 records for {cluster_name}",
                extra={
                    "hosted_zone_id": zone_id,
                    "records_deleted": len(changes),
                    "cluster_name": cluster_name,
                },
            )

    except Exception as e:
        logger.error(f"Error cleaning up Route53 records: {e}")
