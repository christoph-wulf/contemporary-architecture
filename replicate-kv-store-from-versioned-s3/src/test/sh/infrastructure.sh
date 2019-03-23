#!/bin/bash

# Delete S3 bucket if exists (ignoring error if it does not exist)
aws s3api delete-bucket --bucket important-data-bucket --endpoint-url http://localhost:4572 2> /dev/null

# Create the bucket
aws s3api create-bucket --bucket important-data-bucket --endpoint-url http://localhost:4572
# Enable the versioning
aws s3api put-bucket-versioning --bucket important-data-bucket --versioning-configuration Status=Enabled --endpoint-url http://localhost:4572
# Configure lifecycle of previous versions
aws s3api put-bucket-lifecycle --bucket important-data-bucket --lifecycle-configuration file://versions-lifecycle.json --endpoint-url http://localhost:4572

# List buckets
echo "Existing buckets:"
aws s3api list-buckets --endpoint-url http://localhost:4572 --output text --query 'Buckets[*].{Name:Name,CreationDate:CreationDate}'

aws s3api get-bucket-versioning --bucket important-data-bucket --endpoint-url http://localhost:4572
aws s3api get-bucket-lifecycle-configuration --bucket important-data-bucket --endpoint-url http://localhost:4572