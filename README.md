![Blueprint](images/blueprint.svg)

## Streaming Product Data

TFN HTTP based JSONL Snapshots & Feeds history -> migrated to compacted (Kafka) topics.
This is a blueprint for a fast setup of such a product data infrastructure based on compacted topics.

![Blueprint: Streaming Product Data](images/blueprint-1.svg)

(1) A product data **[publishing service](publishing-service/)** produces AVRO messages into a compacted topic <sup>[[1]](#avro)</sup> <sup>[[2]](#compacted-topic)</sup> ... 

(2) An **[importer service](importer-service/)** of a consuming team stores the products into a (Redis) database for random access. Schema lookup to **calculate migrations at runtime**; the reader schema is reduced and therefore reduces the product data to the fields the team needs; one way to implement initial consumption of the topic as well as processing of deltas

![Blueprint: Streaming Product Data](images/blueprint-2.svg)

(3) **ensure compatibility at compile time**, #1 #2

![Blueprint: Streaming Product Data](images/blueprint-3.svg)

(4) Another example: transform into a compacted topic of product data with historized prices

(5) for special product data consumers with no convenient Kafka clients or AVRO libraries: generic HTTP JSONL feeds

### Source Code

docker-compose.yml
Source code in referenced sub directories

Please note: the used technologies Kafka and Scala because they are common in TFN projects.
Also works with Pulsar as streaming infrastructure as obviously with other programming languages like Kotlin or Rust.

### References

<a name="avro">1.</a> https://avro.apache.org/docs/current/  
<a name="compacted-topic">2.</a> https://kafka.apache.org/documentation/#compaction  
