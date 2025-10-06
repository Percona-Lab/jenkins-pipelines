"""
Email PMM staging instance owners about running instances.

This Lambda function sends daily email reminders to instance owners about
their running PMM staging instances, including accurate cost calculations
based on time-weighted spot pricing.
"""

from __future__ import print_function

import re
import boto3
import datetime
import collections


def lambda_handler(event, context):
    fullReportEmail = [
        "alexander.tymchuk@percona.com",
        "talha.rizwan@percona.com",
        "anderson.nogueira@percona.com",
        "alex.miroshnychenko@percona.com",
    ]
    region = "us-east-2"
    session = boto3.Session(region_name=region)
    resources = session.resource("ec2")
    ec2 = session.client("ec2")
    ses = session.client("ses", region_name="us-east-1")

    instances = resources.instances.filter(
        Filters=[
            {"Name": "instance-state-name", "Values": ["running"]},
            {"Name": "tag:iit-billing-tag", "Values": ["pmm-staging"]},
        ]
    )

    emails = collections.defaultdict(list)
    for instance in instances:
        # get instance Owner
        ownerFilter = list(filter(lambda x: "owner" == x["Key"], instance.tags))
        if len(ownerFilter) >= 1:
            owner = ownerFilter[0]["Value"] + "@percona.com"
        else:
            owner = "unknown"

        # get instance allowed days
        allowedDaysFilter = list(
            filter(lambda x: "stop-after-days" == x["Key"], instance.tags)
        )
        if len(allowedDaysFilter) >= 1 and int(allowedDaysFilter[0]["Value"]) > 0:
            allowedDays = allowedDaysFilter[0]["Value"] + " days"
        else:
            allowedDays = "unlimited"

        # get instance Name
        nameFilter = list(filter(lambda x: "Name" == x["Key"], instance.tags))
        if len(nameFilter) >= 1:
            name = nameFilter[0]["Value"]
        else:
            name = instance.id

        # get instance Uptime
        current_time = datetime.datetime.now(instance.launch_time.tzinfo)
        uptime = current_time - instance.launch_time

        # get price - calculate time-weighted cost
        is_spot = instance.instance_lifecycle == "spot"

        if is_spot:
            # Get spot price history for Linux/UNIX only
            priceHistory = ec2.describe_spot_price_history(
                InstanceTypes=[instance.instance_type],
                StartTime=instance.launch_time,
                EndTime=current_time,
                AvailabilityZone=instance.placement["AvailabilityZone"],
                ProductDescriptions=["Linux/UNIX"],
            )

            # Calculate time-weighted cost
            totalCost = 0.0
            sorted_prices = sorted(
                priceHistory["SpotPriceHistory"], key=lambda x: x["Timestamp"]
            )

            for i, entry in enumerate(sorted_prices):
                period_start = entry["Timestamp"]
                price = float(entry["SpotPrice"])

                # Determine when this price period ended
                if i < len(sorted_prices) - 1:
                    period_end = sorted_prices[i + 1]["Timestamp"]
                else:
                    period_end = current_time

                # Only count time after instance launched
                if period_end <= instance.launch_time:
                    continue
                if period_start < instance.launch_time:
                    period_start = instance.launch_time

                # Calculate hours at this price
                hours = (period_end - period_start).total_seconds() / 3600
                totalCost += hours * price
        else:
            # On-demand pricing (simplified - use current spot price as estimate)
            priceHistory = ec2.describe_spot_price_history(
                InstanceTypes=[instance.instance_type],
                AvailabilityZone=instance.placement["AvailabilityZone"],
                ProductDescriptions=["Linux/UNIX"],
                MaxResults=1,
            )
            if priceHistory["SpotPriceHistory"]:
                price = float(priceHistory["SpotPriceHistory"][0]["SpotPrice"])
            else:
                price = 0.05  # fallback
            hours = uptime.total_seconds() / 3600
            totalCost = hours * price * 2  # On-demand is ~2x spot price

        costStr = "%0.2f USD" % totalCost

        # prepare table for email
        if uptime.total_seconds() > 5 * 60 * 60:
            strUptime = re.match("^[^:]+:[^:]+", str(uptime)).group(0)
            lifecycle = "Spot" if is_spot else "On-Demand"
            instance_details = instance.instance_type + " (" + lifecycle + ")"
            region_az = region + "/" + instance.placement["AvailabilityZone"]

            row = (
                "<tr><td>"
                + name
                + "</td><td>"
                + instance_details
                + "</td><td>"
                + region_az
                + "</td><td>"
                + owner
                + "</td><td>"
                + strUptime
                + "</td><td>"
                + allowedDays
                + "</td><td>"
                + costStr
                + "</td></tr>"
            )

            emails[owner].append(row)
            for email in fullReportEmail:
                if owner != email:
                    emails[email].append(row)
        else:
            print("Skip: " + name)

    for ownerEmail in emails:
        if ownerEmail == "unknown":
            continue

        body = """
            <html>
              <head></head>
              <body>
                <h4>A friendly reminder - please don't forget to shutdown the following instances:</h4>
                <table border="1" cellpadding="5" cellspacing="0">
                  <tr><th>Name</th><th>Type</th><th>Region/AZ</th><th>Owner</th><th>Uptime</th><th>Expiry</th><th>Total Cost</th></tr>
                  %s
                </table>
                <p></p>
                <p><a href="https://pmm.cd.percona.com/blue/organizations/jenkins/aws-staging-stop/activity">Stop PMM Staging Link</a></p>
              </body>
            </html>
          """ % ("\n".join(emails[ownerEmail]))
        print("To: " + ownerEmail + body)

        ses.send_email(
            Source="lp-percona.script@percona.com",
            Destination={
                "ToAddresses": [ownerEmail],
            },
            Message={
                "Subject": {
                    "Data": "PMM Staging reminder",
                },
                "Body": {
                    "Html": {
                        "Data": body,
                    },
                },
            },
        )

    return "successful finish"
