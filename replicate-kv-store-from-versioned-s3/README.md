# Make versioned data in S3 available in a service

In this example we combine

 * a [S3 Bucket](https://docs.aws.amazon.com/AmazonS3/latest/dev/Welcome.html) with [automatic versioning](https://docs.aws.amazon.com/de_de/AmazonS3/latest/dev/ObjectVersioning.html) and [lifecyle management](https://docs.aws.amazon.com/AmazonS3/latest/dev/lifecycle-configuration-examples.html#lifecycle-config-conceptual-ex6) to get rid of old versions,
 * the [Alpakka S3](https://doc.akka.io/docs/alpakka/current/s3.html) connector based on [Akka Streams](https://doc.akka.io/docs/akka/2.5/stream/) to load S3 objects and
 * a [Typed Actor](https://doc.akka.io/docs/akka/2.5/typed/actors.html) for state management to

make versioned data in S3 available in a service in a resilient way.

## The Problem

Assume you have a batch job that produces an output. The job runs on code changes and periodically.
How do you make that output available to a downstream service? You could store that output in the database.
But as described by Martin Kleppmann in [The Output of Batch Workflows, p. 411](https://dataintensive.net) it is a good practice to produce a key value store as result of a batch job.
For the services that use it that key value store would be immutable and sometimes replaced by the batch job.

## The Idea

The key value stores are placed in a S3 bucket with versioning and a lifecycle that delete old versions automatically after seven days.
Clients downloads the latest file, periodically check for a new version, then try to load and parse them and in case of success swap the current version with the new one.

The approach provides a very low latency for the data because it is always immediately available on the service.
It provides a high availability even in case of short term network partitions.
It is highly resilient regarding corrupt data and allows an auditability which version of the data is used when by which service.  

This example assumes the output is small enough for the memory of the downstream services. But that is not necessary, see [Write a read-only versioned key value store Spark output to S3](#).   
To keep it simple we will use a plain CSV file as _key value store_ that is parsed as a `Map[String, String]` in this example.  
The approach also works for other batch outputs that fit into memory or disk space of a service like compressed neural nets or decision trees.
Also it does not have to be a batch output. The data could also be a set of business parameters that should be made available by another service.

## The Solution

The base for the implementation is a S3 bucket that has versioning enabled and a lifecyle that deletes old versions after seven days.

```yaml
AWSTemplateFormatVersion: 2010-09-09
Parameters:
  BucketName:
    Type: String
  PrevVersionExpirationDays:
    Type: Integer
Resources:
  VersionedBucket:
    Type: AWS::S3::Bucket
    Properties: 
      BucketName: !Ref BucketName
      VersioningConfiguration: 
        Status: Enabled
      LifecycleConfiguration:
        Rules:
         - Id: !Sub Delete previous version in ${BucketName} after ${PrevVersionExpirationDays} days
           Status: Enabled
           NoncurrentVersionExpirationInDays: !Ref PrevVersionExpirationDays
```

The CloudFormation template [`cfn-versioned-bucket.yml`](src/main/cfn/cfn-versioned-bucket.yml) sets up a bucket with the necessary configuration.
If you want to keep old versions more than 30 days you could also think about moving them to the cheaper [infrequent access storage](https://aws.amazon.com/s3/storage-classes/?nc1=h_ls).   

The client implementation is distributed over three files.

### [`VersionedS3File`](src/main/scala/embeds3data/VersionedS3File.scala)

* _check metadata, future_
* _download version, source_

### [`StoreParser`](src/main/scala/embeds3data/StoreParser.scala)

* _Akka Streams flow for the source of the `VersionedS3File`_
* _Parses the upstream as CSV into a `Map[String, String]`_
* _Considers errors within the flow as `CompatibilityError`_

### [`EmbeddedStoreActor`](src/main/scala/embeds3data/EmbeddedStoreActor.scala)

* _Manages the state_
* _Checks for updates and loads changed file_
* _State diagram_
* _Messages and Behaviors_

# Testing

The solution can be tried out locally using [localstack](https://github.com/localstack/localstack).

```
src/test/sh> TMPDIR=/private$TMPDIR docker-compose up
src/test/sh> ./infrastructure.sh
```

The [`EmbeddedStoreActorTestApp`](src/test/scala/embeds3data/EmbeddedStoreActorTestApp.scala#L25-L35) runs the `EmbeddedStoreActor` and prints the current store periodically.  
You can upload files to the bucket using the program [`S3UploadValidFile`](src/test/scala/embeds3data/S3UploadValidFile.scala#L51-L66) and modify it to create an invalid file.

[Restore an old version](https://docs.aws.amazon.com/AmazonS3/latest/dev/RestoringPreviousVersions.html) to see if the service updates its data.  
Interrupt the connection to S3 temporarily using `src/test/sh> docker-compose down` to check if the service keeps its current data.

## Where to go from here

### Increment towards compatibility

* _Ensure compatibility with AVRO_
* _Add schema identifier as S3 metadata_
* _Verify schema compatibility before downloading_
* _If latest version is incompatible, list the previous versions (not directly supported by Alpakka S3)_

### Increment towards notifications

* _If you consider the periodic checking for changes lame you should enabled S3 update notifications_
* _Price for checking once a minute 0.019 USD per month, checking every ten seconds 0.115 USD_
* _Option: SNS with Webhooks - hard to setup networking_ 
* _Option: SQS updates - easy for single consumer, tricky but possible for multiple consumers_