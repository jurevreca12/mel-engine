// See README.md for license details.

ThisBuild / scalaVersion     := "2.13.8"
ThisBuild / version          := "0.1.0"
ThisBuild / organization     := "JSI"

val chiselVersion = "3.5.6"
val commonLibDepend = Seq(
  "edu.berkeley.cs" %% "chisel3"    	   % chiselVersion,
  "edu.berkeley.cs" %% "chiseltest"  	   % "0.5.6", // % "test",
	"edu.berkeley.cs" %% "dsptools"   	   % "1.5.6",
	"org.scalanlp"    %% "breeze"			 	   % "1.0",
	//"org.scalanlp" 		%% "breeze-viz" 	   % "1.3.0",
) 
val commonScalacOpt = Seq(
	"-language:reflectiveCalls",
	"-deprecation",
	"-feature",
	"-Xcheckinit",
	"-P:chiselplugin:genBundleElements",
)

lazy val commonSettings = Seq(
    libraryDependencies ++= commonLibDepend,
    scalacOptions ++= commonScalacOpt,
    addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % chiselVersion cross CrossVersion.full),
)


lazy val root = (project in file("."))
	.dependsOn(fftProj)
  .settings(
		commonSettings,
    name := "audio_features_extract",
  )

lazy val fftProj = (project in file("sdf-fft"))
	.settings(
		commonSettings,
		name := "fft",
	)

