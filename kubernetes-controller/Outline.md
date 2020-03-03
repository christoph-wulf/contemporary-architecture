## Idea
* Resilient (best-effort data availability), fast (ASAP data updates)
* Data could be structured JSON (better AVRO with schema), CSV (better Parquet or PalDB if lookup table) or neural net weights

## Loading resilient from S3

* Load file from S3, expose it locally and bind readiness check to its availability:
data.txt

* Load AVRO file with static reader/writer schema from S3:
data.avro

* Load versioned AVRO file and its versioned AVRO writer schema file (referenced in S3 file meta data) from versioned S3:  
data.avro <VERSION>  
(Writer) schema.avsc <VERSION>

* Fall back to (search for) newest compatible version:  
data.avro 1 -> 1  
data.avro 2 -> 1  
data.avro 3 -> 1  
data.avro 4 -> 2  
data.avro 5 -> 2 LATEST  
(Writer) schema.avsc 1  
(Writer) schema.avsc 2 LATEST

## React on updates from S3

* Periodically check for update:  
  `Stream.every(1.minute)`
* Replace with SQS queue from S3 updates
* Somehow fall back to periodic checks if SQS access fails
* Identify pods that should have S3 update SQS queue through Kubernetes controller
* Manage SQS queues using Kubernetes controller to support deployments with multiple pods