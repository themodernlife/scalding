/*
Copyright 2014 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.twitter.scalding.platform

import com.twitter.scalding._

import java.io.{
  BufferedInputStream,
  BufferedReader,
  BufferedWriter,
  DataInputStream,
  DataOutputStream,
  File,
  FileInputStream,
  FileOutputStream,
  FileReader,
  FileWriter
}
import java.util.UUID

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.filecache.DistributedCache
import org.apache.hadoop.fs.{FileSystem, FileUtil, Path}
import org.apache.hadoop.hdfs.MiniDFSCluster
import org.apache.hadoop.mapred.{JobConf, MiniMRCluster}
import org.slf4j.LoggerFactory

object LocalCluster {
  private final val HADOOP_CLASSPATH_DIR = new Path("/tmp/hadoop-classpath-lib")
  private final val MUTEX_DIR = new File("local_cluster_mutex")
  private final val MUTEX_FILE = new File(MUTEX_DIR, "local_cluster_uuid")

  def apply() = new LocalCluster()
}

class LocalCluster(mutex: Boolean = true) {
  private val LOG = LoggerFactory.getLogger(getClass)

  private var hadoop: Option[(MiniDFSCluster, MiniMRCluster, JobConf)] = None
  private def getHadoop = hadoop.getOrElse(throw new Exception("Hadoop has not been initialized"))

  private def dfs = getHadoop._1
  private def cluster = getHadoop._2
  private def jobConf = getHadoop._3
  private def fileSystem = dfs.getFileSystem

  private var classpath = Set[File]()

  // The Mini{DFS,MR}Cluster does not make it easy or clean to have two different processes
  // running without colliding. Thus we implement our own mutex. Mkdir should be atomic so
  // there should be no race. Just to be careful, however, we make sure that the file
  // is what we expected, or else we fail.
  private[this] def acquireMutex() {
    LOG.debug("Attempting to acquire mutex")
    val tmpMutex = File.createTempFile("local_cluster", "mutex")
    tmpMutex.deleteOnExit()
    val os = new DataOutputStream(new FileOutputStream(tmpMutex))
    val uuid = UUID.randomUUID.toString
    os.writeUTF(uuid)
    os.close()
    while (!LocalCluster.MUTEX_DIR.mkdir()) {
      LOG.debug("Mutex directory already exists. Sleeping.")
      Thread.sleep(5000)
    }
    if (!tmpMutex.renameTo(LocalCluster.MUTEX_FILE)) {
      throw new IllegalStateException("We made the mutex dir, but couldn't rename.")
    }
    val is = new DataInputStream(new FileInputStream(LocalCluster.MUTEX_FILE))
    if (is.readUTF() != uuid) {
      throw new IllegalStateException("Race condition. We make the mutex dir and renamed, but wrong uuid.")
    }
    is.close()
    LOG.debug("Mutex file acquired")
  }

  private[this] def releaseMutex() {
    LOG.debug("Releasing mutex")
    if (!LocalCluster.MUTEX_FILE.delete()) {
      throw new IllegalStateException("Unable to delete mutex file")
    }
    if (!LocalCluster.MUTEX_DIR.delete()) {
      throw new IllegalStateException("Unable to delete mutex directory")
    }
    LOG.debug("Mutex released")
  }

  def initialize(): this.type = {
    if (mutex) {
      acquireMutex()
    }

    if (Option(System.getProperty("hadoop.log.dir")).isEmpty) {
      System.setProperty("hadoop.log.dir", "build/test/logs")
    }
    new File(System.getProperty("hadoop.log.dir")).mkdirs()

    val conf = new Configuration
    val dfs = new MiniDFSCluster(conf, 4, true, null)
    val fileSystem = dfs.getFileSystem
    val cluster = new MiniMRCluster(4, fileSystem.getUri.toString, 1, null, null, new JobConf(conf))
    val mrJobConf = cluster.createJobConf()
    mrJobConf.setInt("mapred.submit.replication", 2);
    mrJobConf.set("mapred.map.max.attempts", "2");
    mrJobConf.set("mapred.reduce.max.attempts", "2");
    mrJobConf.set("mapred.child.java.opts", "-Xmx512m")
    mrJobConf.setInt("mapred.job.reuse.jvm.num.tasks", -1)
    mrJobConf.setInt("jobclient.completion.poll.interval", 50)
    mrJobConf.setInt("jobclient.progress.monitor.poll.interval", 50)
    mrJobConf.setMapSpeculativeExecution(false)
    mrJobConf.setReduceSpeculativeExecution(false)
    mrJobConf.set("mapreduce.user.classpath.first", "true")

    LOG.debug("Creating directory to store jars on classpath: " + LocalCluster.HADOOP_CLASSPATH_DIR)
    fileSystem.mkdirs(LocalCluster.HADOOP_CLASSPATH_DIR)

    hadoop = Some(dfs, cluster, mrJobConf)

    //TODO I desperately want there to be a better way to do this. I'd love to be able to run ./sbt assembly and depend
    // on that, but I couldn't figure out how to make that work.
    val baseClassPath = List(
      getClass,
      classOf[JobConf],
      classOf[LoggerFactory],
      classOf[scala.ScalaObject],
      classOf[com.twitter.scalding.Args],
      classOf[org.apache.log4j.LogManager],
      classOf[com.twitter.scalding.RichDate],
      classOf[cascading.tuple.TupleException],
      classOf[com.twitter.chill.Externalizer[_]],
      classOf[com.twitter.algebird.Semigroup[_]],
      classOf[com.twitter.chill.KryoInstantiator],
      classOf[org.jgrapht.ext.EdgeNameProvider[_]],
      classOf[org.apache.commons.lang.StringUtils],
      classOf[cascading.scheme.local.TextDelimited],
      classOf[org.apache.commons.logging.LogFactory],
      classOf[org.apache.commons.codec.binary.Base64],
      classOf[com.twitter.scalding.IntegralComparator],
      classOf[org.apache.commons.collections.Predicate],
      classOf[com.esotericsoftware.kryo.KryoSerializable],
      classOf[com.twitter.chill.hadoop.KryoSerialization],
      classOf[org.apache.commons.configuration.Configuration]
    ).foreach { addClassSourceToClassPath(_) }
    this
  }

  def addClassSourceToClassPath[T](clazz: Class[T]) {
    addFileToHadoopClassPath(getFileForClass(clazz))
  }

  def addFileToHadoopClassPath(resourceDir: File): Boolean =
    if (classpath.contains(resourceDir)) {
      LOG.debug("Already on Hadoop classpath: " + resourceDir)
      false
    } else {
      LOG.debug("Not yet on Hadoop classpath: " + resourceDir)
      val localJarFile = if (resourceDir.isDirectory) MakeJar(resourceDir) else resourceDir
      val hdfsJarPath = new Path(LocalCluster.HADOOP_CLASSPATH_DIR, localJarFile.getName)
      fileSystem.copyFromLocalFile(new Path("file://%s".format(localJarFile.getAbsolutePath)), hdfsJarPath)
      DistributedCache.addFileToClassPath(hdfsJarPath, jobConf, fileSystem)
      LOG.debug("Added to Hadoop classpath: " + localJarFile)
      classpath += resourceDir
      true
    }

  private def getFileForClass[T](clazz: Class[T]): File =
    new File(clazz.getProtectionDomain.getCodeSource.getLocation.toURI)

  def mode: Mode = Hdfs(true, jobConf)

  def putFile(file: File, location: String): Boolean = {
    val hdfsLocation = new Path(location)
    val exists = fileSystem.exists(hdfsLocation)
    if (!exists) FileUtil.copy(file, fileSystem, hdfsLocation, false, jobConf)
    exists
  }

  //TODO is there a way to know if we need to wait on anything to shut down, etc?
  def shutdown() {
    fileSystem.close()
    dfs.shutdown()
    cluster.shutdown()
    hadoop = None
    if (mutex) {
      releaseMutex()
    }
  }
}
