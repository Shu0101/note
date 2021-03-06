/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.deploy.worker

import java.io._
import java.net.URI
import java.nio.charset.StandardCharsets

import scala.collection.JavaConverters._

import com.google.common.io.Files

import org.apache.spark.{SecurityManager, SparkConf}
import org.apache.spark.deploy.{DriverDescription, SparkHadoopUtil}
import org.apache.spark.deploy.DeployMessages.DriverStateChanged
import org.apache.spark.deploy.master.DriverState
import org.apache.spark.deploy.master.DriverState.DriverState
import org.apache.spark.internal.Logging
import org.apache.spark.rpc.RpcEndpointRef
import org.apache.spark.util.{Clock, ShutdownHookManager, SystemClock, Utils}

/**
 * Manages the execution of one driver, including automatically restarting the driver on failure.
 * This is currently only used in standalone cluster deploy mode.
 */
private[deploy] class DriverRunner(
    conf: SparkConf,
    val driverId: String,
    val workDir: File,
    val sparkHome: File,
    val driverDesc: DriverDescription,
    val worker: RpcEndpointRef,
    val workerUrl: String,
    val securityManager: SecurityManager)
  extends Logging {

  @volatile private var process: Option[Process] = None
  @volatile private var killed = false

  // Populated once finished
  @volatile private[worker] var finalState: Option[DriverState] = None
  @volatile private[worker] var finalException: Option[Exception] = None

  // Timeout to wait for when trying to terminate a driver.
  private val DRIVER_TERMINATE_TIMEOUT_MS =
    conf.getTimeAsMs("spark.worker.driverTerminateTimeout", "10s")

  // Decoupled for testing
  def setClock(_clock: Clock): Unit = {
    clock = _clock
  }

  def setSleeper(_sleeper: Sleeper): Unit = {
    sleeper = _sleeper
  }

  private var clock: Clock = new SystemClock()
  private var sleeper = new Sleeper {
    def sleep(seconds: Int): Unit = (0 until seconds).takeWhile { _ =>
      Thread.sleep(1000)
      !killed
    }
  }

  /** Starts a thread to run and manage the driver. */
  // 开启一个线程去运行和管理driver
  private[worker] def start() = {

    new Thread("DriverRunner for " + driverId) {
      override def run() {
        var shutdownHook: AnyRef = null
        try {
          shutdownHook = ShutdownHookManager.addShutdownHook { () =>
            logInfo(s"Worker shutting down, killing driver $driverId")
            kill()
          }

          // prepare driver jars and run driver
          // 1.创建driver的工作目录；2.下载application的jar到工作目录中；3.启动driver
          val exitCode = prepareAndRunDriver()
          // 这个方法是去启动driver，但是启动之后，会有一个状态

          // set final state depending on if forcibly killed and process exit code
          // finalState表示driver的最终的状态，他是由：killed和driver的退出状态码 决定的
          finalState = if (exitCode == 0) {
            Some(DriverState.FINISHED)
          } else if (killed) {
            Some(DriverState.KILLED)
          } else {
            Some(DriverState.FAILED)
          }
        } catch {
          case e: Exception =>
            kill()
            finalState = Some(DriverState.ERROR)
            finalException = Some(e)
        } finally {
          if (shutdownHook != null) {
            ShutdownHookManager.removeShutdownHook(shutdownHook)
          }
        }

        // notify worker of final driver state, possible exception
        // 向worker发送Driver的状态改变，这个状态可能是：正常退出，异常退出的，或者是被kill的,在worker中会对这个状态进行进一步的处理
        worker.send(DriverStateChanged(driverId, finalState.get, finalException))
      }
    }.start()
  }

  /** Terminate this driver (or prevent it from ever starting if not yet started) */
  private[worker] def kill(): Unit = {
    logInfo("Killing driver process!")
    killed = true
    synchronized {
      process.foreach { p =>
        val exitCode = Utils.terminateProcess(p, DRIVER_TERMINATE_TIMEOUT_MS)
        if (exitCode.isEmpty) {
          logWarning("Failed to terminate driver process: " + p +
              ". This process will likely be orphaned.")
        }
      }
    }
  }

  /**
   * Creates the working directory for this driver.
   * Will throw an exception if there are errors preparing the directory.
   */
  private def createWorkingDirectory(): File = {
    // 创建driver需要的工作目录, 在spark-home/work/driverId/ 目录下面
    val driverDir = new File(workDir, driverId)
    if (!driverDir.exists() && !driverDir.mkdirs()) {
      throw new IOException("Failed to create directory " + driverDir)
    }
    driverDir
  }

  /**
   * Download the user jar into the supplied directory and return its local path.
   * Will throw an exception if there are errors downloading the jar.
   */
  private def downloadUserJar(driverDir: File): String = {

    // 获取jar的文件名
    val jarFileName = new URI(driverDesc.jarUrl).getPath.split("/").last

    val localJarFile = new File(driverDir, jarFileName)

    if (!localJarFile.exists()) { // May already exist if running multiple workers on one node
      logInfo(s"Copying user jar ${driverDesc.jarUrl} to $localJarFile")

      Utils.fetchFile(
        driverDesc.jarUrl,  // 源文件的路径
        driverDir,          // 目标目录
        conf,
        securityManager,
        SparkHadoopUtil.get.newConfiguration(conf),
        System.currentTimeMillis(),
        useCache = false)

      if (!localJarFile.exists()) { // Verify copy succeeded
        throw new IOException(
          s"Can not find expected jar $jarFileName which should have been loaded in $driverDir")
      }
    }
    localJarFile.getAbsolutePath
  }

  private[worker] def prepareAndRunDriver(): Int = {
    // 创建driver的工作目录
    val driverDir = createWorkingDirectory()

    // 下载用户上传的jar（application） 到工作目录中
    val localJarFilename = downloadUserJar(driverDir)

    def substituteVariables(argument: String): String = argument match {
      case "{{WORKER_URL}}" => workerUrl
      case "{{USER_JAR}}" => localJarFilename
      case other => other
    }

    // TODO: If we add ability to submit multiple jars they should also be added here
    // 构建ProcessBuiler , 用于driver的进程启动
    val builder = CommandUtils.buildProcessBuilder(
      driverDesc.command,     // drivr 的启动命令
      securityManager,
      driverDesc.mem,        // driver需要的内存大小
      sparkHome.getAbsolutePath,  // spark-home的路径
      substituteVariables)

    // 启动driver，并且会返回一个driver的退出状态，来表示driver是否正常退出
    runDriver(builder, driverDir, driverDesc.supervise)
  }

  private def runDriver(builder: ProcessBuilder, baseDir: File, supervise: Boolean): Int = {
    // baseDir 是driver的工作目录
    builder.directory(baseDir)

    // 重定向 stdout和stderr到文件中
    def initialize(process: Process): Unit = {
      // Redirect stdout and stderr to files
      val stdout = new File(baseDir, "stdout")// 也就是说，输出目录为：spark-home/work/driver/stdout
      CommandUtils.redirectStream(process.getInputStream, stdout)

      val stderr = new File(baseDir, "stderr")
      val formattedCommand = builder.command.asScala.mkString("\"", "\" \"", "\"")
      val header = "Launch Command: %s\n%s\n\n".format(formattedCommand, "=" * 40)
      Files.append(header, stderr, StandardCharsets.UTF_8)
      CommandUtils.redirectStream(process.getErrorStream, stderr)
    }

    // 正式启动driver，会返回一个driver的退出状态，来表示driver是否正常退出
    runCommandWithRetry(ProcessBuilderLike(builder), initialize, supervise)
  }

  private[worker] def runCommandWithRetry(
      command: ProcessBuilderLike, initialize: Process => Unit, supervise: Boolean): Int = {
    var exitCode = -1
    // Time to wait between submission retries.
    var waitSeconds = 1
    // A run of this many seconds resets the exponential back-off.
    val successfulRunDuration = 5
    var keepTrying = !killed

    while (keepTrying) {
      logInfo("Launch Command: " + command.command.mkString("\"", "\" \"", "\""))

      synchronized {
        if (killed) { return exitCode }
        // 启动builder
        process = Some(command.start())

        // 将进程的输出日志（stdout,stderr)，重定向到文件中
        initialize(process.get)
      }

      val processStart = clock.getTimeMillis()
      // 等待进程的退出
      exitCode = process.get.waitFor()

      // check if attempting another run
      keepTrying = supervise && exitCode != 0 && !killed
      if (keepTrying) {
        if (clock.getTimeMillis() - processStart > successfulRunDuration * 1000) {
          waitSeconds = 1
        }
        logInfo(s"Command exited with status $exitCode, re-launching after $waitSeconds s.")
        sleeper.sleep(waitSeconds)
        waitSeconds = waitSeconds * 2 // exponential back-off
      }
    }

    exitCode
  }
}

private[deploy] trait Sleeper {
  def sleep(seconds: Int): Unit
}

// Needed because ProcessBuilder is a final class and cannot be mocked
private[deploy] trait ProcessBuilderLike {
  def start(): Process
  def command: Seq[String]
}

private[deploy] object ProcessBuilderLike {
  def apply(processBuilder: ProcessBuilder): ProcessBuilderLike = new ProcessBuilderLike {
    override def start(): Process = processBuilder.start()
    override def command: Seq[String] = processBuilder.command().asScala
  }
}
