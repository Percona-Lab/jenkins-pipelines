# Remove expired AKS clusters (Azure, cluster-only)

import os
import math
import logging
import datetime
import azure.functions as func
from typing import List, Dict, Optional

from azure.identity import DefaultAzureCredential
from azure.mgmt.resource import ResourceManagementClient
from azure.mgmt.containerservice import ContainerServiceClient
from azure.core.exceptions import ResourceNotFoundError, HttpResponseError

DRY_RUN = os.getenv("DRY_RUN", "true").lower() == "true"
DELETE_TIMEOUT = int(os.getenv("DELETE_TIMEOUT", "600"))  # 10 minutes default

credential: Optional[DefaultAzureCredential] = None
resource_groups_client: Optional[ResourceManagementClient] = None
aks_client: Optional[ContainerServiceClient] = None

# Resolve RG for a cluster name
CLUSTER_RG_MAP: Dict[str, str] = {}


def parse_epoch_creation_time(tags: dict) -> Optional[datetime.datetime]:
    """Try parse tags['creation-time'] (epoch seconds) into aware datetime UTC."""
    raw = (tags or {}).get("creation-time")
    if not raw:
        return None
    try:
        ts = float(raw)
        return datetime.datetime.fromtimestamp(ts, tz=datetime.timezone.utc)
    except Exception:
        logging.warning("Invalid creation-time tag: %r", raw)
        return None


def is_cluster_to_terminate(cluster) -> bool:
    """
    Delete rules:
      - requires tag team=cloud (case-insensitive)
      - if TTL tag missing -> True (delete by policy)
      - else TTL must be an integer number of hours
      - delete when (now - creation-time[tag]) in hours > TTL
      - if TTL present but creation-time missing/invalid -> safe skip
    """
    tags = cluster.tags or {}
    name = getattr(cluster, "name", "<unknown>")
    logging.info("Cluster %s tags: %s", name, tags)

    if tags.get("team", "").lower() != "cloud":
        return False

    ttl_hours = tags.get("delete-cluster-after-hours")
    if ttl_hours is None:
        logging.info("Cluster %s has no TTL tag — marked for deletion by policy", name)
        return True

    created_at = parse_epoch_creation_time(tags)
    logging.info("Cluster %s created_at: %s", cluster.name, created_at)
    if created_at is None:
        logging.info("Cluster %s has TTL but no valid creation-time tag — skipping", name)
        return False
    now = datetime.datetime.now(datetime.timezone.utc)
    lifetime_hours = int(math.ceil((now - created_at).total_seconds() / 3600.0))

    return lifetime_hours > int(ttl_hours)


def get_clusters_to_terminate() -> List[str]:
    """
    Scan all resource groups, return cluster names to delete.
    Also populate CLUSTER_RG_MAP[name] = rg for later deletion.
    """
    clusters_for_deletion: List[str] = []
    CLUSTER_RG_MAP.clear()

    for rg in resource_groups_client.resource_groups.list():
        rg_name = rg.name
        try:
            for mc in aks_client.managed_clusters.list_by_resource_group(rg_name):
                if is_cluster_to_terminate(mc):
                    clusters_for_deletion.append(mc.name)
                    CLUSTER_RG_MAP[mc.name] = rg_name
        except HttpResponseError as e:
            logging.warning("Failed to list AKS in RG %s: %s", rg_name, e)

    if not clusters_for_deletion:
        logging.info("There are no clusters for deletion")
    return clusters_for_deletion


def terminate_cluster(cluster_name: str):
    """
    Resolve RG from CLUSTER_RG_MAP (or scan), then delete the AKS cluster.
    Uses .result() to wait for deletion completion.
    """
    rg_name = CLUSTER_RG_MAP.get(cluster_name)
    if not rg_name:
        # Slow path: try to resolve by scanning RGs
        for rg in resource_groups_client.resource_groups.list():
            try:
                _ = aks_client.managed_clusters.get(rg.name, cluster_name)
                rg_name = rg.name
                CLUSTER_RG_MAP[cluster_name] = rg_name
                break
            except Exception:
                continue

    if not rg_name:
        logging.info("Cluster %s not found — skipping", cluster_name)
        return

    if DRY_RUN:
        logging.info("[DRY-RUN] Would delete cluster %s/%s", rg_name, cluster_name)
        return

    try:
        logging.info("Starting deletion of cluster %s/%s", rg_name, cluster_name)

        aks_client.managed_clusters.begin_delete(rg_name, cluster_name).result(timeout=DELETE_TIMEOUT)

        logging.info("Cluster %s was successfully deleted", cluster_name)

    except TimeoutError:
        logging.error("Cluster %s deletion timed out after %d seconds", cluster_name, DELETE_TIMEOUT)
    except Exception as e:
        logging.error("Failed to delete cluster %s: %s", cluster_name, e)

def main(mytimer: func.TimerRequest) -> None:

    global credential, resource_groups_client, aks_client

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    subscription_id = os.getenv("AZURE_SUBSCRIPTION_ID")
    if not subscription_id:
        logging.error("AZURE_SUBSCRIPTION_ID is not set")
        return

    credential = DefaultAzureCredential()
    resource_groups_client = ResourceManagementClient(credential, subscription_id)
    aks_client = ContainerServiceClient(credential, subscription_id)

    logging.info("Searching for AKS clusters to terminate.")
    clusters = get_clusters_to_terminate()
    for cluster in clusters:
        logging.info("Terminating %s", cluster)
        terminate_cluster(cluster)