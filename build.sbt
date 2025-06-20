// Scala versions
val scala3Version = "3.3.5"
val scala2Version = "2.13.16"
val javaVersion = "11"

val catsEffectVersion = "3.6.0"
val munitVersion = "1.0.4"
val munitCatsEffectVersion = "2.1.0"

inThisBuild(
  List(
    scalaVersion := scala3Version,
    crossScalaVersions := Seq(scala3Version, scala2Version),
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    organization := "io.github.nivox",
    homepage := Some(url("https://github.com/nivox/cats-effect-treesync")),
    licenses := List(License.MIT),
    versionScheme := Some("early-semver"),
    developers := List(
      Developer(
        id = "nivox",
        name = "Andrea Zito",
        email = "zito.andrea@gmail.com",
        url = url("https://nivox.github.io")
      )
    )
  )
)

lazy val root = project
  .in(file("."))
  .settings(
    name := "cats-effect-treesync",
    scalaVersion := scala3Version,
    crossScalaVersions := Seq(scala3Version, scala2Version),

    // Common settings for all Scala versions
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.scalameta" %% "munit" % munitVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test,
      "org.typelevel" %% "cats-effect-testkit" % catsEffectVersion % Test
    ),

    javacOptions ++= Seq("-source", javaVersion, "-target", javaVersion),
    scalacOptions ++= Seq(
      s"-release:${javaVersion}",
      "-Wunused:imports"
    ),

    // Scala version specific settings
    scalacOptions ++= (if (scalaVersion.value.startsWith("2."))
                         Seq(
                           "-Xsource:3",
                           "-Ymacro-annotations"
                         )
                       else Seq())
  )
