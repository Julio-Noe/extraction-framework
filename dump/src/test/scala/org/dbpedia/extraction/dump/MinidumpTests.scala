package org.dbpedia.extraction.dump

import java.io.{File, FileInputStream}
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.concurrent.ConcurrentLinkedQueue

import org.aksw.rdfunit.RDFUnit
import org.aksw.rdfunit.enums.TestCaseExecutionType
import org.aksw.rdfunit.io.reader.RdfModelReader
import org.aksw.rdfunit.model.interfaces.{TestCase, TestSuite}
import org.aksw.rdfunit.sources.{SchemaSourceFactory, TestSource, TestSourceBuilder}
import org.aksw.rdfunit.tests.generators.{ShaclTestGenerator, TestGeneratorFactory}
import org.aksw.rdfunit.validate.wrappers.RDFUnitStaticValidator
import org.apache.commons.io.FileUtils
import org.apache.jena.rdf.model.{Model, ModelFactory}
import org.apache.jena.riot.{RDFDataMgr, RDFLanguages}
import org.apache.spark.sql.{SQLContext, SparkSession}
import org.dbpedia.extraction.config.Config
import org.dbpedia.extraction.dump.extract.ConfigLoader
import org.dbpedia.validation.{TestSuiteFactory, ValidationExecutor}
import org.scalatest.{BeforeAndAfterAll, FunSuite}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

class MinidumpTests extends FunSuite with BeforeAndAfterAll {

  /**
    * in src/test/resources/
    */
  val date = new SimpleDateFormat("yyyyMMdd").format(Calendar.getInstance().getTime)

  //Workaround to get resource files in Scala 2.11
  val classLoader =   getClass.getClassLoader
  val mappingsConfig = new Config(classLoader.getResource("mappings.extraction.minidump.properties").getFile)
  val genericConfig = new Config(classLoader.getResource("generic-spark.extraction.minidump.properties").getFile)
  val nifAbstractConfig = new Config(classLoader.getResource("extraction.nif.abstracts.properties").getFile)
  val minidumpDir = new File(classLoader.getResource("minidumps").getFile)



  val minidumpURL = classLoader.getResource("mini-enwiki.xml.bz2")
  val ciTestFile = classLoader.getResource("dbpedia-specific-ci-tests.ttl").getFile
  val ciTestModel: Model = ModelFactory.createDefaultModel()


  /**
    * NEEDED for SHACL
    */
  val dumpDirectory =     new File(mappingsConfig.dumpDir, s"enwiki/$date/")
  val dbpedia_ontologyFile = classLoader.getResource("dbpedia.owl").getFile
  val custom_SHACL_testFile = classLoader.getResource("custom-shacl-tests.ttl").getFile


  override def beforeAll() {

    /**
      * check ttl file for CI here
      */

    println("Loading triggers and validators")

    RDFDataMgr.read(ciTestModel, new FileInputStream(ciTestFile),RDFLanguages.TURTLE)



    println("Extracting Minidump")

    // copy dumps

    minidumpDir.listFiles().foreach(f=>{
      val wikiMasque =  f.getName+"wiki"
      val targetDir =     new File(mappingsConfig.dumpDir, s"${wikiMasque}/$date/")
      // create directories
      targetDir.mkdirs()
      FileUtils.copyFile(
        new File(f+"/wiki.xml.bz2"),
        new File(targetDir, s"${wikiMasque}-$date-pages-articles-multistream.xml.bz2")
      )
  })



    /**
      * mappings extraction
       */
    val jobsRunning = new ConcurrentLinkedQueue[Future[Unit]]()

    extract(mappingsConfig,jobsRunning)
    extract(genericConfig,jobsRunning)
    extract (nifAbstractConfig, jobsRunning)

    def extract (config: Config, jobsRunning:ConcurrentLinkedQueue[Future[Unit]]) = {
      val configLoader = new ConfigLoader(config)
      val parallelProcesses = if(config.runJobsInParallel) config.parallelProcesses else 1

      //Execute the extraction jobs one by one
      for (job <- configLoader.getExtractionJobs) {
        while(jobsRunning.size() >= parallelProcesses){
          Thread.sleep(1000)
        }

        val future = Future{job.run()}
        jobsRunning.add(future)
        future.onComplete {
          case Failure(f) => throw f
          case Success(_) => jobsRunning.remove(future)
        }
      }


    }
    while(jobsRunning.size() > 0) {
      Thread.sleep(1000)
    }



  }




  test("IRI Coverage Tests") {


    val hadoopHomeDir = new File("./.hadoop/")
    hadoopHomeDir.mkdirs()
    System.setProperty("hadoop.home.dir", hadoopHomeDir.getAbsolutePath)

    val sparkSession = SparkSession.builder()
      .config("hadoop.home.dir", "./.hadoop")
      .config("spark.local.dir", "./.spark")
      .appName("Test Iris").master("local[*]").getOrCreate()
    sparkSession.sparkContext.setLogLevel("WARN")

    val sqlContext: SQLContext = sparkSession.sqlContext

    val testSuite = TestSuiteFactory.loadTestSuite(Array[String](ciTestFile))

    val testReports = ValidationExecutor.testIris(
      pathToFlatTurtleFile =  s"${mappingsConfig.dumpDir.getAbsolutePath}/*/$date/*.ttl.bz2",
      testSuite = testSuite
    )(sqlContext)

    import org.dbpedia.validation.buildTableReport

    val partLabels = Array[String]("SUBJECT TEST CASES","PREDICATE TEST CASES","OBJECT TEST CASES")

    Array.tabulate(testReports.length){

      i => buildTableReport(partLabels(i),testReports(i),testSuite.triggerCollection,testSuite.testApproachCollection)
    }
  }

  test("RDFUnit SHACL"){
    val filesToBeValidated = dumpDirectory.listFiles.filter(_.isFile).filter(_.toString.endsWith(".ttl.bz2")).toList
    println("FILES, FILES, FILES\n"+filesToBeValidated)

    val dbpedia_ont: Model = ModelFactory.createDefaultModel()
    RDFDataMgr.read(dbpedia_ont, new FileInputStream(dbpedia_ontologyFile),RDFLanguages.RDFXML)

    val custom_SHACL_tests: Model = ModelFactory.createDefaultModel()
    RDFDataMgr.read(custom_SHACL_tests, new FileInputStream(custom_SHACL_testFile),RDFLanguages.TURTLE)

    assert(dbpedia_ont.size()>0,"size not 0")
    assert(custom_SHACL_tests.size()>0, "size not 0")

    val schema = SchemaSourceFactory.createSchemaSourceSimple("http://dbpedia.org/shacl", new RdfModelReader(custom_SHACL_tests))

    val rdfUnit = RDFUnit.createWithOwlAndShacl
    rdfUnit.init

    val shaclTestGenerator = new ShaclTestGenerator()
    val shaclTests: java.util.Collection[TestCase] = shaclTestGenerator.generate(schema)
    val shaclTestSuite = new TestSuite(shaclTests)

    val testSource = new TestSourceBuilder()
      .setPrefixUri("minidump", "http://dbpedia.org/minidump")
      .setInMemReader(new RdfModelReader((ModelFactory.createDefaultModel()))) // here pass a model
      //.setInMemFromCustomText(fileContentAsString, "TURTLE") // or pass the file content as String
      .setReferenceSchemata(schema)
      .build()
    val results = RDFUnitStaticValidator.validate(TestCaseExecutionType.shaclTestCaseResult, testSource, shaclTestSuite)

    assert(results.getTestCaseResults.isEmpty)


  }

  override def afterAll() {
    println("Cleaning Extraction")
  }
}
