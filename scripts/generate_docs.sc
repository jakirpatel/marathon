#!/usr/bin/env amm

import java.time.Instant
import ammonite.ops._
import ammonite.ops.ImplicitWd._

/**
  * Docs generation process:
  *
  * 1. copy docs/docs and docs/_layouts/docs.html part from 1.3, 1.4 and 1.5 branches to the temp dirs
  * 2. copy the latest release (1.5_ branch doc to the temp dir
  * 3. generate the latest release docs
  * 4. replace docs/docs with docs/docs from 1.3, 1.4, 1.5 and generate docs for the _sites/docs_version
  */

@main
def main(tracked_by_git: Boolean = true) = {
  if(tracked_by_git) {
    //If the script is being tracked by git, we should create and run an untracked copy of it in order
    // to avoid errors during branch checkout
    cp.over(pwd/"generate_docs.sc", pwd/"generate_docs.temp")
    %('amm, "generate_docs.temp", "--tracked_by_git", "false")
    rm! pwd/"generate_docs.temp"
  } else {
    //check that repo is clean
    val possiblyChangedLines: Vector[String] = %%('git, "diff-files", "--name-only").out.lines
    if (possiblyChangedLines.nonEmpty) {
      val msg = s"Git repository isn't clean, aborting docs generation. Changed files:\n${possiblyChangedLines.mkString("\n")}"
      println(msg)
      exit()
    }

    val buildDir = makeTmpDir()
    val docsDir = pwd/up/"docs"

    buildDocs(buildDir, docsDir)
    %('git, 'checkout, currentGitBranch)
    println(s"Success! Docs were generated at ${buildDir/'docs/'_site}")
  }
}

def makeTmpDir(): Path = {
  val timestamp = Instant.now().toString.replace(':', '-')
  val path = root/"tmp"/s"marathon-docs-build-$timestamp"
  mkdir! path
  path
}

val ignoredReleaseBranchesVersions = Set("1.6")

val currentGitBranch = %%('git, "rev-parse", "--abbrev-ref", "HEAD").out.lines.head

def listAllTagsInOrder: Vector[String] = %%('git, "tag", "-l", "--sort=version:refname").out.lines

def isReleaseTag(tag: String) = tag.matches("""v[1-9]+\.\d+\.\d+""")

/**
  * get the major.minor version from a [v]major.minor.patch
  *
  * v1.4.11 -> 1.4
  * 1.45.983 -> 1.45
  *
  * @param tag
  * @return
  */
def releaseBranchVersion(tag: String) = tag.dropWhile(_.isLetter).takeWhile {
  var reachedMinorVersion = false
  elem => elem match {
    case c if c.isDigit => true
    case c if c == '.' && !reachedMinorVersion =>
      reachedMinorVersion = true
      true
    case _ => false
  }
}

def notIgnoredBranch(branchVersion: String) = !ignoredReleaseBranchesVersions.contains(branchVersion)

def getTheLastTagVersion(tags: Seq[String]) = tags.last.drop(1)

/**
  * getting 3 latest release versions and corresponding latest tags
  *
  * example: List[(String, String)] = List(("1.3", "1.3.14"), ("1.4", "1.4.11"), ("1.5", "1.5.6"))
  */
val docsTargetVersions = listAllTagsInOrder
  .filter(isReleaseTag)
  .groupBy(releaseBranchVersion)
  .filterKeys(notIgnoredBranch)
  .mapValues(getTheLastTagVersion)
  .toList.sortBy(_._1)
  .takeRight(3)

val (latestReleaseBranch, latestReleaseVersion) = docsTargetVersions.last

def generateDocsForVersion(docsPath: Path, version: String, outputPath: String = "_site"): Unit = {
  %('bundle, "exec", s"jekyll build --config _config.yml,_config.$version.yml -d $outputPath/$version/")(docsPath)
}

def branchForTag(version: String) = {
  s"tags/v$version"
}

// step 1: copy docs/docs to the respective dirs

def checkoutDocsToTempFolder(buildDir: Path, docsDir: Path) = {
  docsTargetVersions foreach { case (releaseBranchVersion, tagVersion) =>
    val tagName = branchForTag(tagVersion)
    %git('checkout, s"$tagName")
    val tagBuildDir = buildDir / releaseBranchVersion
    mkdir! tagBuildDir
    println(s"Copying $tagVersion docs to: $tagBuildDir")
    cp.into(docsDir / 'docs, tagBuildDir)
    cp.into(docsDir / '_layouts / "docs.html", tagBuildDir)
    println(s"Docs folder for $releaseBranchVersion is copied to ${tagBuildDir / "docs"}")
  }
}

// step 2: generate the default version (latest release tag)

def generateTopLevelDocs(buildDir: Path, docsDir: Path) = {
  val rootDocsDir = buildDir / 'docs


  %git('checkout, branchForTag(latestReleaseVersion))

  println(s"Copying docs for $latestReleaseVersion into $buildDir")
  cp.into(docsDir, buildDir)

  println("Cleaning previously generated docs")
  rm! rootDocsDir / '_site

  println(s"Generating root docs for $latestReleaseVersion")
  %("bundle", "install", "--path", s"vendor/bundle")(rootDocsDir)
  %('bundle, "exec", s"jekyll build --config _config.yml -d _site")(rootDocsDir)
}
// step 3: generate docs for other versions
def generateVersionedDocs(buildDir: Path) {
  val rootDocsDir = buildDir / 'docs
  println(s"Generating docs for versions ${docsTargetVersions.map(_._2).mkString(", ")}")
  docsTargetVersions foreach { case (releaseBranchVersion, tagVersion) =>
    println("Cleaning docs/docs")
    rm! rootDocsDir / 'docs
    println(s"Copying docs for $tagVersion to the docs/docs folder")
    cp.into(buildDir / releaseBranchVersion / 'docs, rootDocsDir)
    cp.over(buildDir / releaseBranchVersion / "docs.html", rootDocsDir / '_layouts / "docs.html")
    println(s"Generation docs for $tagVersion")
    write.over(rootDocsDir / s"_config.$releaseBranchVersion.yml", s"baseurl : /marathon/$releaseBranchVersion")
    generateDocsForVersion(rootDocsDir, releaseBranchVersion)
  }
}

def buildDocs(buildDir: Path, docsDir: Path) = {
  checkoutDocsToTempFolder(buildDir, docsDir)
  generateTopLevelDocs(buildDir, docsDir)
  generateVersionedDocs(buildDir)
}
