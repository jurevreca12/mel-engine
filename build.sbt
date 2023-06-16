// See README.md for license details.

ThisBuild / scalaVersion     := "2.13.8"
ThisBuild / version          := "0.1.0"
ThisBuild / organization     := "JSI"

val chiselVersion = "3.5.6"

lazy val root = (project in file("."))
  .settings(
    name := "audio_features_extract",
    libraryDependencies ++= Seq(
      "edu.berkeley.cs" %% "chisel3"    	 % chiselVersion,
      "edu.berkeley.cs" %% "chiseltest" 	 % "0.5.4", // % "test",
	  	"edu.berkeley.cs" %% "dsptools"   	 % "1.5.6",
	  	"org.scalanlp"    %% "breeze"			 	% "1.0"
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-P:chiselplugin:genBundleElements",
    ),
    addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % chiselVersion cross CrossVersion.full),
  )

