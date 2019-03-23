name := "contemporary-architecture"

version := "0.1"

scalaVersion := "2.12.8"

// Required to download the data from S3
libraryDependencies += "com.lightbend.akka" %% "akka-stream-alpakka-s3" % "1.0-M3"

// Required for local state management
libraryDependencies += "com.typesafe.akka" %% "akka-actor-typed" % "2.5.20"

// Logging API
libraryDependencies += "de.heikoseeberger"        %% "akka-log4j"      % "1.6.1"
libraryDependencies += "org.apache.logging.log4j" % "log4j-api"        % "2.11.1"
libraryDependencies += "org.apache.logging.log4j" %% "log4j-api-scala" % "11.0"

// Test scope
libraryDependencies += "org.apache.logging.log4j" % "log4j-core"       % "2.11.1" % "test"
libraryDependencies += "org.mockito"              %% "mockito-scala"   % "1.1.4"  % "test"
libraryDependencies += "org.scalatest"            %% "scalatest"       % "3.0.5"  % "test"
libraryDependencies += "com.amazonaws"            %  "aws-java-sdk-s3" % "1.11.517" % "test"