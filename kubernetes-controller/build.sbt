enablePlugins(GitVersioning, JavaAppPackaging/*, GraalVMNativeImagePlugin*/, DockerPlugin)

name := "kubernetes-controller"

scalaVersion := "2.13.1"

dockerBaseImage := "amazoncorretto:8"

//graalVMNativeImageGraalVersion := Some("19.1.1")
//
//graalVMNativeImageOptions += "--static"

libraryDependencies += "io.kubernetes" % "client-java" % "5.0.0"