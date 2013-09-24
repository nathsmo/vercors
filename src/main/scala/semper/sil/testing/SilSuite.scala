package semper.sil.testing

import java.nio.file._
import java.net.{URL, URI}
import java.util.regex.Pattern
import org.scalatest._
import scala.collection.JavaConversions._
import semper.sil.verifier._
import semper.sil.ast.{Position, TranslatedPosition, SourcePosition}
import semper.sil.frontend.Frontend

/** A test suite for verification toolchains that use SIL as the intermediate language.
  *
  * Note that tests are named by the first file for a given test, e.g. `basic/functions.sil`.
  * The tests are also tagged by their name and by the file name (with and without extension);
  * in the example the tags would be `basic/functions.sil`, `functions.sil` and `functions`.
  * These tags can be used to execute just a single test:
  * `test-only * -- -n functions`
  *
  * @author Stefan Heule
  */
abstract class SilSuite extends FunSuite with TestAnnotationParser {

  /** The config map passed to ScalaTest. */
  protected var configMap: Map[String, Any] = Map[String, Any]()

  private var _testsRegistered = false

  val fileListRegex = """(.*)_file\d*.*""".r

  /**
   * The test directories where tests can be found.
   * The directories must be relative because they are resolved via [[java.lang.ClassLoader.getResource]],
   * see http://stackoverflow.com/a/7098501/491216.
   * @return A sequence of test directories.
   */
  def testDirectories: Seq[String]

  /**
   * Returns a class loader that can be used to access resources such as test files
   * via [[java.lang.ClassLoader.getResource]].
   *
   * @return A class loader for accessing resources.
   */
  def classLoader: ClassLoader = getClass.getClassLoader

  private var openFileSystems: Seq[FileSystem] = Seq()
  addShutdownHookForOpenFileSystems()

  /** The frontend to be used. */
  def frontend(verifier: Verifier, files: Seq[Path]): Frontend

  /** The list of verifiers to be used. */
  def verifiers: Seq[Verifier]

  /**
   * Returns the name of a test. If it consists of only one file, it is the name of that file.
   * If it consists of several files of the form foo_file*.ext, the name will be foo. */
  def testName(prefix: String, f: Path) = f.getFileName.toString match {
    case fileListRegex(name) => prefix + name
    case name => prefix + name
  }

  /**
   * Get the list of files which belong to the same test as the given file. For most files, this will be just a list
   * consisting of the file itself. It is also possible to create a single with multiple files by naming the files
   * foo_file1.ext foo_file2.ext etc. and putting them into the same directory.
   * If a path of this form is handed to this method, a list of all files that belong to the same test are returned.
   * They are ordered according to their number.
   */
  def fileList(file: Path): Seq[Path] = file.toString match {
    case fileListRegex(name) => {
      // Create a regex for files that belong to the same test.
      val regex = (Pattern.quote(name) + """_file(\d*).*""").r
      // Collect all files that match this regex and their numbers.
      var files = List[(Path, Int)]()
      val dirStream = Files.newDirectoryStream(file.getParent)
      dirStream foreach { f =>
        f.toString match {
          case regex(index) => files = (f, index.toInt) :: files
          case _ =>
        }
      }
      // Sort the file by their numbers and return the files only. (We only needed the numbers for ordering)
      files.sortBy(_._2).map(_._1)
    }
    case _ => List(file)
  }

  /** Registers a tests that runs the translator for all given verifiers. */
  def registerSilTest(file: Path, prefix: String) {
    require(file != null)
    require(verifiers != null)

    // The files that belong to the same test. Files with ignore-annotations will be filtered later on.
    var files = fileList(file)
    if (files.head != file) {
      // Only register the multi file test once, not for every file it contains.
      return
    }

    val baseTestName = testName(prefix, file)
    val relativeFileName = prefix + "/" + file.getName(file.getNameCount - 1)
    val testAnnotations = parseAnnotations(files)
    val fileName = file.getFileName.toString
    val fileNameWithoutExt = fileName.substring(0, fileName.lastIndexOf("."))

    val tags = List(
      Tag(relativeFileName),
      Tag(file.toString),
      Tag(fileName),
      Tag(fileNameWithoutExt))

    var testErrors: List[String] = Nil

    // error for parse failures of test annotations
    if (testAnnotations.hasErrors) {
      testErrors ::= "Encountered the following errors while parsing the test-annotations (these annotations will be ignored):\n" + testAnnotations.errors.map("  " + _.errorMessage).mkString("\n")
    }

    // one test per verifier
    for (verifier <- verifiers) {
      // ignore files if necessary
      files = files filter { f => !testAnnotations.isFileIgnored(f, verifier.name) }

      if (files.isEmpty)
        ignore(baseTestName, tags: _*) {}
      else {
        test(baseTestName + " [" + verifier.name + "]", (Tag(verifier.name) :: tags) :_*) {
          val fe = frontend(verifier, files)
          val tPhases = fe.phases.map { p =>
            time(p.action)._2 + " (" + p.name + ")"
          }.mkString(", ")
          val result = fe.result
          assert(result != null)

          // postprocessing of errors: match up expected errors with actual errors and report inconsistencies
          var expectedButMissingErrors: List[ExpectedError] = Nil
          var unexpectedButMissingErrors: List[UnexpectedError] = Nil
          var missingButPresentErrors: List[MissingError] = Nil
          var additionalErrors: List[AbstractError] = Nil
          result match {
            case Success =>
              var missingErrors: List[MissingError] = Nil
              var expectedErrors: List[ExpectedError] = Nil

              // no actual errors, thus there should not be any expected ones
              testAnnotations.annotations foreach {
                case e: ExpectedError => expectedErrors ::= e
                case u: UnexpectedError =>
                  if (u.project.toLowerCase == verifier.name.toLowerCase) unexpectedButMissingErrors ::= u
                case m: MissingError => missingErrors ::= m
                case _: IgnoreOthers =>
                case _: IgnoreFileList => sys.error("the test should not have run in the first place")
                case _: IgnoreFile => () // Could be that some files of this test, but not all of them are ignored.
              }

              /* Collect errors that were expected by the current verifier but are missing */
              expectedButMissingErrors =
                expectedErrors filterNot (expectedToBeMissing(_, missingErrors, verifier))

            case Failure(actualErrors) => {
              var expectedErrors = testAnnotations.errorAnnotations

              def sameLine(file: Path, lineNr: Int, pos: Position) = pos match {
                case p: SourcePosition => lineNr == p.line
                case p: TranslatedPosition => file == p.file && lineNr == p.line
                case _ => sys.error("Position is neither a source position nor a translated position even though we checked this before.")
              }

              val findError: AbstractError => Option[TestAnnotation] = (actual: AbstractError) => {
                if (!actual.pos.isInstanceOf[SourcePosition] && !actual.pos.isInstanceOf[TranslatedPosition]) None
                else expectedErrors.filter({
                  case ErrorAnnotation(id, file, lineNr) => id.matches(actual.fullId) && sameLine(file, lineNr, actual.pos)
                  case IgnoreOthers(file, lineNr, _) => sameLine(file, lineNr, actual.pos)
                }) match {
                  case Nil => None
                  case l => l.find(_.isInstanceOf[ErrorAnnotation]) match {
                    case Some(x) => {
                      // remove the error from the list of expected errors (i.e. only match once)
                      expectedErrors = expectedErrors.filter(y => !y.eq(x))
                      Some(x)
                    }
                    case None => Some(l.head) // IgnoreOthers should not be removed
                  }
                }
              }

              // go through all actual errors and try to match them up with the expected ones
              actualErrors foreach (a => findError(a) match {
                case Some(m: MissingError) =>
                  if (m.project.toLowerCase == verifier.name.toLowerCase) missingButPresentErrors ::= m
                case Some(_) => // expected this error
                case None =>
                  additionalErrors ::= a
              })

              /* Partition remaining annotations into missing error annotations and all others */
              val missingErrors: Seq[MissingError] = expectedErrors collect {case m: MissingError => m}
              val remainingErrors: Seq[LocatedAnnotation] = expectedErrors filterNot (missingErrors contains _)

              // process remaining errors that have not been matched
              remainingErrors.foreach {
                case e: ExpectedError =>
                  if (!expectedToBeMissing(e, missingErrors, verifier)) expectedButMissingErrors ::= e
                case u: UnexpectedError =>
                  if (u.project.toLowerCase == verifier.name.toLowerCase) unexpectedButMissingErrors ::= u
                case _: IgnoreOthers =>
                case _: MissingError => ??? /* Should not occur because they were previously filtered out */
              }
            }
          }

          if (!additionalErrors.isEmpty) {
            testErrors ::= "The following errors occurred during verification, but should not have according to the test annotations:\n" +
              additionalErrors.reverse.map("  " + _.toString).mkString("\n")
          }

          if (!expectedButMissingErrors.isEmpty) {
            testErrors ::= "The following errors were expected according to the test annotations, but did not occur during verification:\n" +
              expectedButMissingErrors.reverse.map("  " + _.toString).mkString("\n")
          }

          if (!unexpectedButMissingErrors.isEmpty) {
            testErrors ::= "The following errors were specified to occur erroneously (UnexpectedError) according to the test annotations, but did not occur during verification (this might be cause by invalid test annotations:\n" +
              unexpectedButMissingErrors.reverse.map("  " + _.toString).mkString("\n")
          }

          if (!missingButPresentErrors.isEmpty) {
            testErrors ::= "The following errors were specified to be missing erroneously (MissingError) according to the test annotations, but did occur during verification (this might be cause by invalid test annotations):\n" +
              missingButPresentErrors.reverse.map("  " + _.toString).mkString("\n")
          }

          // report some other useful information
          info(s"Verifier used: ${verifier.name} ${verifier.version}.")
          verifier.dependencies foreach {
            dep =>
              info(s"  using ${dep.name} ${dep.version} located at ${dep.location}")
          }
          info(s"Time required: $tPhases.")

          // if there were any errors that could not be matched up (or other problems), make the test fail
          if (!testErrors.isEmpty) {
            val n = Seq(additionalErrors.size, expectedButMissingErrors.size, unexpectedButMissingErrors.size, missingButPresentErrors.size)
            val title = s"${n.sum} errors (${n.mkString(",")}})"
            fail((Seq(title) ++ testErrors).mkString("\n\n") + "\n\n")
          }
        }
      }
    }
  }

  private def expectedToBeMissing(e: LocatedAnnotation, ms: Seq[MissingError], verifier: Verifier) =
    ms exists (m =>
         m.project.toLowerCase == verifier.name.toLowerCase
      && m.sameSource(e))

  /** Formats a time in milliseconds. */
  def formatTime(millis: Long): String = {
    if (millis > 1000) "%.2f sec".format(millis * 1.0 / 1000)
    else "%s msec".format(millis.toString)
  }

  /** Measures the time it takes to execute `f` and returns the result of `f` as well as the required time. */
  def time[T](f: () => T): (T, String) = {
    val start = System.currentTimeMillis()
    val r = f.apply()

    (r, formatTime(System.currentTimeMillis() - start))
  }

  /** Recursively registers all files found in the given directory as a test.
    *
    * The prefix is used for naming and tagging the ScalaTest test case that is eventually
    * generated for each test file found. Subdirectories of `dir` will be appended to the
    * initial prefix.
    *
    * It is thus reasonable to make the initial prefix the (relative) root test directory.
    * For example, given the following directories and files
    *   .../issues/test1.scala
    *              test2.scala
    *   .../all/basic/test1.scala
    *                 test2.scala
    *                 test3.scala
    * it would be reasonable to make the calls
    *   registerTestDirectory(path_of(".../issues"), "issues")
    *   registerTestDirectory(path_of(".../all/basic"), "all/basic")
    * or
    *   registerTestDirectory(path_of(".../issues"), "issues")
    *   registerTestDirectory(path_of(".../all"), "all")
    * to - in both cases - get ScalaTest test cases that can be identified by
    *   issues/test1.scala
    *   issues/test2.scala
    *   all/basic/test1.scala
    *   all/basic/test2.scala
    *   all/basic/test3.scala
    *
    * @param dir The directory to recursively search for files. Every file in the directory is
    *            assumed to be a test file.
    * @param prefix The initial prefix used for naming and tagging the resulting ScalaTest tests.
    */
  def registerTestDirectory(dir: Path, prefix: String) {
    assert(dir != null, "Directory must not be null")
    assert(Files.isDirectory(dir), "Path must represent a directory")

    val directoryStream = Files.newDirectoryStream(dir)
    val dirContent = directoryStream.toList
    val namePattern = configMap.getOrElse("includeTests", ".*").toString

    for (f: Path <- dirContent
         if Files.isDirectory(f)) {

      val newPrefix = prefix + "/" + f.getName(f.getNameCount - 1)
      registerTestDirectory(f, newPrefix)
    }

    for (f: Path <- dirContent
         if Files.isReadable(f) /* If a file is renamed while Sbt runs, AccessDeniedExceptions might be
                                 * thrown. Apparently, because the old file still exists in
                                 * target/.../test-classes, but it is somehow locked. Weird stuff.
                                 * Once the Sbt REPL is closed, the "ghost" file disappears.
                                 */
         if !Files.isDirectory(f)
         if f.toString.matches(namePattern)) {

      registerSilTest(f, prefix)
    }
  }

  def registerTests() {
    if (_testsRegistered) return

    for (testDir <- testDirectories) {
      val resource = classLoader.getResource(testDir)
      assert(resource != null, s"Test directory $testDir couldn't be found")

      val path = pathFromResource(classLoader.getResource(testDir))
      registerTestDirectory(path, testDir)
    }

    _testsRegistered = true
  }

  /**
   * Creates a path from the given URL, which, for example, could have been obtained by
   * calling [[java.lang.Class.getResource]]. The current implementation can handle URLs that
   * point into the normal file system (file:...) or into a jar file (jar:...).
   *
   * Based on code taken from http://stackoverflow.com/a/15718001/491216.
   *
   * @param resource The URL to turn into a path.
   * @return The path obtained from the URL.
   */
  private def pathFromResource(resource: URL): Path = {
    assert(resource != null, "Resource URL must not be null")

    val uri = resource.toURI

    uri.getScheme match {
      case "file" => Paths.get(uri)

      case "jar" =>
        val uriStr = uri.toString()
        val separator = uriStr.indexOf("!/")
        val entryName = uriStr.substring(separator + 2)
        val fileURI = URI.create(uriStr.substring(0, separator))

        /* 2013-05-03 Malte:
         *   The following bit of code is quite nasty, but I wasn't able to get anything less
         *   nasty to work reliably. There are two main problems:
         *
         *   1. It is not obvious when to close the file system, because several components of
         *      our tool chain keep references to path objects that in turn have references
         *      to their underlying file system. If these path objects are used (not all usages
         *      seem dangerous, though) after the underlying file system has been closed, an
         *      exception is thrown.
         *
         *   2. If the test suite is executed from an Sbt prompt then the shutdown hook of
         *      the runtime environment doesn't seem to fire and the file systems don't seem
         *      to be closed. Thus, if the tests are executed again without existing the
         *      Sbt prompt, FileSystemAlreadyExistsExceptions can be thrown because some
         *      file systems are still open.
         *
         *   The list of open file systems (openFileSystems) unfortunately doesn't seem to
         *   survive, otherwise it might have been possible to maintain a map from file URI
         *   to file system and only create a new file system if there is no map entry for
         *   the given file URI yet.
         *
         *   I also tried to use a finalizer method instead of the shutdown hook, but the
         *   finalizer also doesn't seem to be called if the Sbt prompt is not existed.
         */

        var fs: FileSystem = null

        try {
          fs = FileSystems.newFileSystem(fileURI, Map[String, Object]())
          openFileSystems = fs +: openFileSystems
        } catch {
          case e: java.nio.file.FileSystemAlreadyExistsException =>
            fs = FileSystems.getFileSystem(fileURI)
            assert(fs.isOpen, "The reused file system is expected to still be open")
        } finally {
          assert(fs != null, s"Could not get hold of a file system for $fileURI (from $uriStr)")
        }

        fs.getPath(entryName)

      case other => sys.error(s"Resource $uri of scheme $other is not supported.")
    }
  }

  /**
   * Closes all open file systems stored in `openFileSystems`.
   */
  private def addShutdownHookForOpenFileSystems() {
    Runtime.getRuntime().addShutdownHook(new Thread {
      override def run() {
        if (openFileSystems != null) {
          openFileSystems foreach (fs => if (fs.isOpen) {fs.close()})
        }
      }
    })
  }

  override def testNames = {
    registerTests()
    super.testNames
  }

  protected override def runTest(testName: String, reporter: Reporter, stopper: Stopper, configMap: Map[String, Any], tracker: Tracker) {
    this.configMap = configMap
    registerTests()
    super.runTest(testName, reporter, stopper, configMap, tracker)
  }

  protected override def runTests(testName: Option[String], reporter: Reporter, stopper: Stopper, filter: Filter, configMap: Map[String, Any], distributor: Option[Distributor], tracker: Tracker) {
    this.configMap = configMap
    registerTests()

    super.runTests(testName, reporter, stopper, filter, configMap, distributor, tracker)
  }

  override def run(testName: Option[String], reporter: Reporter, stopper: Stopper, filter: Filter, configMap: Map[String, Any], distributor: Option[Distributor], tracker: Tracker) {
    this.configMap = configMap
    registerTests()
    super.run(testName, reporter, stopper, filter, configMap, distributor, tracker)
  }
}
