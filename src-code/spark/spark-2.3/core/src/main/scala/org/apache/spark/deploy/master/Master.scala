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

package org.apache.spark.deploy.master

import java.text.SimpleDateFormat
import java.util.{Date, Locale}
import java.util.concurrent.{ScheduledFuture, TimeUnit}

import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}
import scala.util.Random

import org.apache.spark.{SecurityManager, SparkConf, SparkException}
import org.apache.spark.deploy.{ApplicationDescription, DriverDescription,
  ExecutorState, SparkHadoopUtil}
import org.apache.spark.deploy.DeployMessages._
import org.apache.spark.deploy.master.DriverState.DriverState
import org.apache.spark.deploy.master.MasterMessages._
import org.apache.spark.deploy.master.ui.MasterWebUI
import org.apache.spark.deploy.rest.StandaloneRestServer
import org.apache.spark.internal.Logging
import org.apache.spark.metrics.MetricsSystem
import org.apache.spark.rpc._
import org.apache.spark.serializer.{JavaSerializer, Serializer}
import org.apache.spark.util.{SparkUncaughtExceptionHandler, ThreadUtils, Utils}

private[deploy] class Master(
    override val rpcEnv: RpcEnv,
    address: RpcAddress,
    webUiPort: Int,
    val securityMgr: SecurityManager,
    val conf: SparkConf)
  extends ThreadSafeRpcEndpoint with Logging with LeaderElectable {

  private val forwardMessageThread =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("master-forward-message-thread")

  private val hadoopConf = SparkHadoopUtil.get.newConfiguration(conf)

  // For application IDs
  private def createDateFormat = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US)

  private val WORKER_TIMEOUT_MS = conf.getLong("spark.worker.timeout", 60) * 1000
  private val RETAINED_APPLICATIONS = conf.getInt("spark.deploy.retainedApplications", 200)
  private val RETAINED_DRIVERS = conf.getInt("spark.deploy.retainedDrivers", 200)
  private val REAPER_ITERATIONS = conf.getInt("spark.dead.worker.persistence", 15)
  private val RECOVERY_MODE = conf.get("spark.deploy.recoveryMode", "NONE")
  private val MAX_EXECUTOR_RETRIES = conf.getInt("spark.deploy.maxExecutorRetries", 10)

  val workers = new HashSet[WorkerInfo]
  val idToApp = new HashMap[String, ApplicationInfo]
  private val waitingApps = new ArrayBuffer[ApplicationInfo]
  val apps = new HashSet[ApplicationInfo]

  private val idToWorker = new HashMap[String, WorkerInfo]
  private val addressToWorker = new HashMap[RpcAddress, WorkerInfo]

  private val endpointToApp = new HashMap[RpcEndpointRef, ApplicationInfo]
  private val addressToApp = new HashMap[RpcAddress, ApplicationInfo]
  private val completedApps = new ArrayBuffer[ApplicationInfo]
  private var nextAppNumber = 0

  private val drivers = new HashSet[DriverInfo]
  private val completedDrivers = new ArrayBuffer[DriverInfo]
  // Drivers currently spooled for scheduling
  private val waitingDrivers = new ArrayBuffer[DriverInfo]
  private var nextDriverNumber = 0

  Utils.checkHost(address.host)

  private val masterMetricsSystem = MetricsSystem.createMetricsSystem("master", conf, securityMgr)
  private val applicationMetricsSystem = MetricsSystem.createMetricsSystem("applications", conf,
    securityMgr)
  private val masterSource = new MasterSource(this)

  // After onStart, webUi will be set
  private var webUi: MasterWebUI = null

  private val masterPublicAddress = {
    val envVar = conf.getenv("SPARK_PUBLIC_DNS")
    if (envVar != null) envVar else address.host
  }

  private val masterUrl = address.toSparkURL
  private var masterWebUiUrl: String = _

  private var state = RecoveryState.STANDBY

  private var persistenceEngine: PersistenceEngine = _

  private var leaderElectionAgent: LeaderElectionAgent = _

  private var recoveryCompletionTask: ScheduledFuture[_] = _

  private var checkForWorkerTimeOutTask: ScheduledFuture[_] = _

  // As a temporary workaround before better ways of configuring memory, we allow users to set
  // a flag that will perform round-robin scheduling across the nodes (spreading out each app
  // among all the nodes) instead of trying to consolidate each app onto a small # of nodes.
  private val spreadOutApps = conf.getBoolean("spark.deploy.spreadOut", true)

  // Default maxCores for applications that don't specify it (i.e. pass Int.MaxValue)
  private val defaultCores = conf.getInt("spark.deploy.defaultCores", Int.MaxValue)
  val reverseProxy = conf.getBoolean("spark.ui.reverseProxy", false)
  if (defaultCores < 1) {
    throw new SparkException("spark.deploy.defaultCores must be positive")
  }

  // Alternative application submission gateway that is stable across Spark versions
  private val restServerEnabled = conf.getBoolean("spark.master.rest.enabled", true)
  private var restServer: Option[StandaloneRestServer] = None
  private var restServerBoundPort: Option[Int] = None

  // master是一个Endpoint，这里是其一个生命周期方法
  override def onStart(): Unit = {
    logInfo("Starting Spark master at " + masterUrl)
    logInfo(s"Running Spark version ${org.apache.spark.SPARK_VERSION}")
    webUi = new MasterWebUI(this, webUiPort)
    webUi.bind()
    masterWebUiUrl = "http://" + masterPublicAddress + ":" + webUi.boundPort
    if (reverseProxy) {
      masterWebUiUrl = conf.get("spark.ui.reverseProxyUrl", masterWebUiUrl)
      webUi.addProxy()
      logInfo(s"Spark Master is acting as a reverse proxy. Master, Workers and " +
       s"Applications UIs are available at $masterWebUiUrl")
    }
    checkForWorkerTimeOutTask = forwardMessageThread.scheduleAtFixedRate(new Runnable {
      override def run(): Unit = Utils.tryLogNonFatalError {
        self.send(CheckForWorkerTimeOut)
      }
    }, 0, WORKER_TIMEOUT_MS, TimeUnit.MILLISECONDS)

    if (restServerEnabled) {
      val port = conf.getInt("spark.master.rest.port", 6066)
      restServer = Some(new StandaloneRestServer(address.host, port, conf, self, masterUrl))
    }
    restServerBoundPort = restServer.map(_.start())

    masterMetricsSystem.registerSource(masterSource)
    masterMetricsSystem.start()
    applicationMetricsSystem.start()
    // Attach the master and app metrics servlet handler to the web ui after the metrics systems are
    // started.
    masterMetricsSystem.getServletHandlers.foreach(webUi.attachHandler)
    applicationMetricsSystem.getServletHandlers.foreach(webUi.attachHandler)

    val serializer = new JavaSerializer(conf)
    val (persistenceEngine_, leaderElectionAgent_) = RECOVERY_MODE match {
      case "ZOOKEEPER" =>
        logInfo("Persisting recovery state to ZooKeeper")
        val zkFactory =
          new ZooKeeperRecoveryModeFactory(conf, serializer)
        (zkFactory.createPersistenceEngine(), zkFactory.createLeaderElectionAgent(this))
      case "FILESYSTEM" =>
        val fsFactory =
          new FileSystemRecoveryModeFactory(conf, serializer)
        (fsFactory.createPersistenceEngine(), fsFactory.createLeaderElectionAgent(this))
      case "CUSTOM" =>
        val clazz = Utils.classForName(conf.get("spark.deploy.recoveryMode.factory"))
        val factory = clazz.getConstructor(classOf[SparkConf], classOf[Serializer])
          .newInstance(conf, serializer)
          .asInstanceOf[StandaloneRecoveryModeFactory]
        (factory.createPersistenceEngine(), factory.createLeaderElectionAgent(this))
      case _ =>
        (new BlackHolePersistenceEngine(), new MonarchyLeaderAgent(this))
    }
    persistenceEngine = persistenceEngine_
    leaderElectionAgent = leaderElectionAgent_
  }

  override def onStop() {
    masterMetricsSystem.report()
    applicationMetricsSystem.report()
    // prevent the CompleteRecovery message sending to restarted master
    if (recoveryCompletionTask != null) {
      recoveryCompletionTask.cancel(true)
    }
    if (checkForWorkerTimeOutTask != null) {
      checkForWorkerTimeOutTask.cancel(true)
    }
    forwardMessageThread.shutdownNow()
    webUi.stop()
    restServer.foreach(_.stop())
    masterMetricsSystem.stop()
    applicationMetricsSystem.stop()
    persistenceEngine.close()
    leaderElectionAgent.stop()
  }

  override def electedLeader() {
    self.send(ElectedLeader)
  }

  override def revokedLeadership() {
    self.send(RevokedLeadership)
  }

  // rpc 消息接收处理
  override def receive: PartialFunction[Any, Unit] = {
    //选举leader
    case ElectedLeader =>
      val (storedApps, storedDrivers, storedWorkers) = persistenceEngine.readPersistedData(rpcEnv)
      // 这里Master因为有HA，所以会有两种状态，alive和standby，还有可能是recovering(恢复中)
      state = if (storedApps.isEmpty && storedDrivers.isEmpty && storedWorkers.isEmpty) {
        RecoveryState.ALIVE
      } else {
        RecoveryState.RECOVERING
      }
      logInfo("I have been elected leader! New state: " + state)
      if (state == RecoveryState.RECOVERING) {
        beginRecovery(storedApps, storedDrivers, storedWorkers)
        recoveryCompletionTask = forwardMessageThread.schedule(new Runnable {
          override def run(): Unit = Utils.tryLogNonFatalError {
            self.send(CompleteRecovery)
          }
        }, WORKER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      }

    case CompleteRecovery => completeRecovery()

    case RevokedLeadership =>
      logError("Leadership has been revoked -- master shutting down.")
      System.exit(0)

      // Worker向Master注册
    case RegisterWorker(id, workerHost, workerPort, workerRef, cores, memory, workerWebUiUrl, masterAddress) =>
      logInfo("Registering worker %s:%d with %d cores, %s RAM".format(
        workerHost, workerPort, cores, Utils.megabytesToString(memory)))
      if (state == RecoveryState.STANDBY) {
        workerRef.send(MasterInStandby)
      } else if (idToWorker.contains(id)) {
        workerRef.send(RegisterWorkerFailed("Duplicate worker ID"))
      } else {
        val worker = new WorkerInfo(id, workerHost, workerPort, cores, memory,
          workerRef, workerWebUiUrl)

        // idToWorker(worker.id) = worker 即：会向idToWorker这个map中添加当前worker
        if (registerWorker(worker)) {
          persistenceEngine.addWorker(worker)
          workerRef.send(RegisteredWorker(self, masterWebUiUrl, masterAddress))
          schedule()
        } else {
          val workerAddress = worker.endpoint.address
          logWarning("Worker registration failed. Attempted to re-register worker at same " +
            "address: " + workerAddress)
          workerRef.send(RegisterWorkerFailed("Attempted to re-register worker at same address: "
            + workerAddress))
        }
      }

      // standaloneAppClient 请求注册应用
    case RegisterApplication(description, driver) =>
      // TODO Prevent repeated registrations from some driver
      if (state == RecoveryState.STANDBY) {
        // 如果请求发送到了状态为standby的Master，那么do nothing
        // ignore, don't send response
      } else {
        logInfo("Registering app " + description.name)
        // 拿到application的封装信息
        val app = createApplication(description, driver)

        // 这里其实就是将app加入到缓存中，将app加入到等待的buffer中，然后在schedule方法中取出等待的app，开始为这些APP去启动Executor
        registerApplication(app)
        logInfo("Registered app " + description.name + " with ID " + app.id)

        // 使用持久化引擎，将application持久化（文件系统的持久化引擎或者zookeeper的持久化引擎）
        persistenceEngine.addApplication(app)

        // 向standaloneSchedulerbankend 中的APPClient发送APP已经注册的消息
        driver.send(RegisteredApplication(app.id, self))

        // 在worker上会去启动Executor
        schedule()
      }

      // worker发送过来的Executor状态改变
    case ExecutorStateChanged(appId, execId, state, message, exitStatus) =>
      val execOption = idToApp.get(appId).flatMap(app => app.executors.get(execId))

      execOption match {
        case Some(exec) =>
          // 拿到application 信息
          val appInfo = idToApp(appId)
          val oldState = exec.state
          exec.state = state

          if (state == ExecutorState.RUNNING) {
            assert(oldState == ExecutorState.LAUNCHING,
              s"executor $execId state transfer from $oldState to RUNNING is illegal")
            appInfo.resetRetryCount()
          }

          // 向driver同步发送ExecutorUpdated
          exec.application.driver.send(ExecutorUpdated(execId, state, message, exitStatus, false))

          // 判断：如果Executor完成了
          if (ExecutorState.isFinished(state)) {//KILLED, FAILED, LOST, EXITED
            // Remove this executor from the worker and app
            // 从worker和Executor中移除Executor
            logInfo(s"Removing executor ${exec.fullId} because it is $state")
            // If an application has already finished, preserve its
            // state to display its information properly on the UI
            if (!appInfo.isFinished) {
              // 如果application还没有完成，那么将Executor从application中移除
              appInfo.removeExecutor(exec)
            }
            // 将Executor从worker中移除（内存缓存的移除）
            exec.worker.removeExecutor(exec)

            //  如果Executor的退出状态是否正常的, 那么在重试之后无果，还会移除application
            val normalExit = exitStatus == Some(0)
            // Only retry certain number of times so we don't go into an infinite loop.
            // Important note: this code path is not exercised by tests, so be very careful when
            // changing this `if` condition.
            // 如果Executor反复调度失败，那么认为application也就失败了，所以此时要将application remove
            if (!normalExit// 当前的退出状态是非正常的
                && appInfo.incrementRetryCount() >= MAX_EXECUTOR_RETRIES  //超过重试次数，将remove APP，最大重试次数是10
                && MAX_EXECUTOR_RETRIES >= 0) { // < 0 disables this application-killing path
              val execs = appInfo.executors.values
              if (!execs.exists(_.state == ExecutorState.RUNNING)) {
                logError(s"Application ${appInfo.desc.name} with ID ${appInfo.id} failed " +
                  s"${appInfo.retryCount} times; removing it")
                // 移除当前还在运行的 application
                removeApplication(appInfo, ApplicationState.FAILED)
              }
            }
          }

          // 再次进行资源的调度，给等待的application分配Driver和启动Executor
          schedule()
        case None =>
          logWarning(s"Got status update for unknown executor $appId/$execId")
      }

      // 接收到Worker发送的 Driver状态改变 的信息
    case DriverStateChanged(driverId, state, exception) =>
      state match {
        case DriverState.ERROR | DriverState.FINISHED | DriverState.KILLED | DriverState.FAILED =>
          // 从Master的内存缓存中移除当前driver的信息，最后会调用schedule方法（由于移除Driver释放了一些资源，所以可以再次调度，给其他等待的application分配资源）
          removeDriver(driverId, state, exception)
        case _ =>
          throw new Exception(s"Received unexpected state update for driver $driverId: $state")
      }

      // 接收来自Worker的心跳
    case Heartbeat(workerId, worker) =>
      idToWorker.get(workerId) match {
        case Some(workerInfo) =>
          workerInfo.lastHeartbeat = System.currentTimeMillis()//需要更新上次心跳的时间
        case None =>
          if (workers.map(_.id).contains(workerId)) {
            logWarning(s"Got heartbeat from unregistered worker $workerId." +
              " Asking it to re-register.")
            worker.send(ReconnectWorker(masterUrl))
          } else {
            logWarning(s"Got heartbeat from unregistered worker $workerId." +
              " This worker was never registered, so ignoring the heartbeat.")
          }
      }

    case MasterChangeAcknowledged(appId) =>
      idToApp.get(appId) match {
        case Some(app) =>
          logInfo("Application has been re-registered: " + appId)
          app.state = ApplicationState.WAITING
        case None =>
          logWarning("Master change ack from unknown app: " + appId)
      }

      if (canCompleteRecovery) { completeRecovery() }

    case WorkerSchedulerStateResponse(workerId, executors, driverIds) =>
      idToWorker.get(workerId) match {
        case Some(worker) =>
          logInfo("Worker has been re-registered: " + workerId)
          worker.state = WorkerState.ALIVE

          val validExecutors = executors.filter(exec => idToApp.get(exec.appId).isDefined)
          for (exec <- validExecutors) {
            val app = idToApp.get(exec.appId).get
            val execInfo = app.addExecutor(worker, exec.cores, Some(exec.execId))
            worker.addExecutor(execInfo)
            execInfo.copyState(exec)
          }

          for (driverId <- driverIds) {
            drivers.find(_.id == driverId).foreach { driver =>
              driver.worker = Some(worker)
              driver.state = DriverState.RUNNING
              worker.addDriver(driver)
            }
          }
        case None =>
          logWarning("Scheduler state from unknown worker: " + workerId)
      }

      if (canCompleteRecovery) { completeRecovery() }

      // 对于Master不能识别的Executor和driver，Master会发送命令给Worker，要求kill掉
    case WorkerLatestState(workerId, executors, driverIds) =>
      idToWorker.get(workerId) match {
        case Some(worker) =>
          for (exec <- executors) {
            val executorMatches = worker.executors.exists {
              case (_, e) => e.application.id == exec.appId && e.id == exec.execId
            }
            if (!executorMatches) {
              // master doesn't recognize this executor. So just tell worker to kill it.
              worker.endpoint.send(KillExecutor(masterUrl, exec.appId, exec.execId))
            }
          }

          for (driverId <- driverIds) {
            val driverMatches = worker.drivers.exists { case (id, _) => id == driverId }
            if (!driverMatches) {
              // master doesn't recognize this driver. So just tell worker to kill it.
              worker.endpoint.send(KillDriver(driverId))
            }
          }
        case None =>
          logWarning("Worker state from unknown worker: " + workerId)
      }

    case UnregisterApplication(applicationId) =>
      logInfo(s"Received unregister request from application $applicationId")
      idToApp.get(applicationId).foreach(finishApplication)

    case CheckForWorkerTimeOut =>
      timeOutDeadWorkers()

  }

  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    case RequestSubmitDriver(description) =>
      if (state != RecoveryState.ALIVE) {
        val msg = s"${Utils.BACKUP_STANDALONE_MASTER_PREFIX}: $state. " +
          "Can only accept driver submissions in ALIVE state."
        context.reply(SubmitDriverResponse(self, false, None, msg))
      } else {
        logInfo("Driver submitted " + description.command.mainClass)
        val driver = createDriver(description)
        persistenceEngine.addDriver(driver)
        waitingDrivers += driver
        drivers.add(driver)
        schedule()

        // TODO: It might be good to instead have the submission client poll the master to determine
        //       the current status of the driver. For now it's simply "fire and forget".

        context.reply(SubmitDriverResponse(self, true, Some(driver.id),
          s"Driver successfully submitted as ${driver.id}"))
      }

    case RequestKillDriver(driverId) =>
      if (state != RecoveryState.ALIVE) {
        val msg = s"${Utils.BACKUP_STANDALONE_MASTER_PREFIX}: $state. " +
          s"Can only kill drivers in ALIVE state."
        context.reply(KillDriverResponse(self, driverId, success = false, msg))
      } else {
        logInfo("Asked to kill driver " + driverId)
        val driver = drivers.find(_.id == driverId)
        driver match {
          case Some(d) =>
            if (waitingDrivers.contains(d)) {
              waitingDrivers -= d
              self.send(DriverStateChanged(driverId, DriverState.KILLED, None))
            } else {
              // We just notify the worker to kill the driver here. The final bookkeeping occurs
              // on the return path when the worker submits a state change back to the master
              // to notify it that the driver was successfully killed.
              d.worker.foreach { w =>
                w.endpoint.send(KillDriver(driverId))
              }
            }
            // TODO: It would be nice for this to be a synchronous response
            val msg = s"Kill request for $driverId submitted"
            logInfo(msg)
            context.reply(KillDriverResponse(self, driverId, success = true, msg))
          case None =>
            val msg = s"Driver $driverId has already finished or does not exist"
            logWarning(msg)
            context.reply(KillDriverResponse(self, driverId, success = false, msg))
        }
      }

    case RequestDriverStatus(driverId) =>
      if (state != RecoveryState.ALIVE) {
        val msg = s"${Utils.BACKUP_STANDALONE_MASTER_PREFIX}: $state. " +
          "Can only request driver status in ALIVE state."
        context.reply(
          DriverStatusResponse(found = false, None, None, None, Some(new Exception(msg))))
      } else {
        (drivers ++ completedDrivers).find(_.id == driverId) match {
          case Some(driver) =>
            context.reply(DriverStatusResponse(found = true, Some(driver.state),
              driver.worker.map(_.id), driver.worker.map(_.hostPort), driver.exception))
          case None =>
            context.reply(DriverStatusResponse(found = false, None, None, None, None))
        }
      }

    case RequestMasterState =>
      context.reply(MasterStateResponse(
        address.host, address.port, restServerBoundPort,
        workers.toArray, apps.toArray, completedApps.toArray,
        drivers.toArray, completedDrivers.toArray, state))

    case BoundPortsRequest =>
      context.reply(BoundPortsResponse(address.port, webUi.boundPort, restServerBoundPort))

    case RequestExecutors(appId, requestedTotal) =>
      context.reply(handleRequestExecutors(appId, requestedTotal))

    case KillExecutors(appId, executorIds) =>
      val formattedExecutorIds = formatExecutorIds(executorIds)
      context.reply(handleKillExecutors(appId, formattedExecutorIds))
  }

  override def onDisconnected(address: RpcAddress): Unit = {
    // The disconnected client could've been either a worker or an app; remove whichever it was
    logInfo(s"$address got disassociated, removing it.")
    addressToWorker.get(address).foreach(removeWorker(_, s"${address} got disassociated"))
    addressToApp.get(address).foreach(finishApplication)
    if (state == RecoveryState.RECOVERING && canCompleteRecovery) { completeRecovery() }
  }

  private def canCompleteRecovery =
    workers.count(_.state == WorkerState.UNKNOWN) == 0 &&
      apps.count(_.state == ApplicationState.UNKNOWN) == 0

  private def beginRecovery(storedApps: Seq[ApplicationInfo], storedDrivers: Seq[DriverInfo],
      storedWorkers: Seq[WorkerInfo]) {
    for (app <- storedApps) {
      logInfo("Trying to recover app: " + app.id)
      try {
        registerApplication(app)
        app.state = ApplicationState.UNKNOWN
        app.driver.send(MasterChanged(self, masterWebUiUrl))
      } catch {
        case e: Exception => logInfo("App " + app.id + " had exception on reconnect")
      }
    }

    for (driver <- storedDrivers) {
      // Here we just read in the list of drivers. Any drivers associated with now-lost workers
      // will be re-launched when we detect that the worker is missing.
      drivers += driver
    }

    for (worker <- storedWorkers) {
      logInfo("Trying to recover worker: " + worker.id)
      try {
        registerWorker(worker)
        worker.state = WorkerState.UNKNOWN
        worker.endpoint.send(MasterChanged(self, masterWebUiUrl))
      } catch {
        case e: Exception => logInfo("Worker " + worker.id + " had exception on reconnect")
      }
    }
  }

  // 1.从内存缓存中移除；2.从相关的组件的内存缓存中移除；3.从持久化存储中移除
  private def completeRecovery() {
    // Ensure "only-once" recovery semantics using a short synchronization period.
    if (state != RecoveryState.RECOVERING) { return }
    state = RecoveryState.COMPLETING_RECOVERY

    // Kill off any workers and apps that didn't respond to us.
    // 对于没有响应的worker和application，将他们kill掉
    workers.filter(_.state == WorkerState.UNKNOWN).foreach(
      removeWorker(_, "Not responding for recovery"))

    apps.filter(_.state == ApplicationState.UNKNOWN).foreach(finishApplication)

    // Update the state of recovered apps to RUNNING
    apps.filter(_.state == ApplicationState.WAITING).foreach(_.state = ApplicationState.RUNNING)

    // Reschedule drivers which were not claimed by any workers
    drivers.filter(_.worker.isEmpty).foreach { d =>
      logWarning(s"Driver ${d.id} was not found after master recovery")
      if (d.desc.supervise) {
        logWarning(s"Re-launching ${d.id}")
        relaunchDriver(d)
      } else {
        removeDriver(d.id, DriverState.ERROR, None)
        logWarning(s"Did not re-launch ${d.id} because it was not supervised")
      }
    }

    state = RecoveryState.ALIVE
    schedule()
    logInfo("Recovery complete - resuming operations!")
  }

  /**
   * Schedule executors to be launched on the workers.
   * Returns an array containing number of cores assigned to each worker.
   *
   * There are two modes of launching executors. The first attempts to spread out an application's
   * executors on as many workers as possible, while the second does the opposite (i.e. launch them
   * on as few workers as possible). The former is usually better for data locality purposes and is
   * the default.
   *
   * The number of cores assigned to each executor is configurable. When this is explicitly set,
   * multiple executors from the same application may be launched on the same worker if the worker
   * has enough cores and memory. Otherwise, each executor grabs all the cores available on the
   * worker by default, in which case only one executor per application may be launched on each
   * worker during one single schedule iteration.
   * Note that when `spark.executor.cores` is not set, we may still launch multiple executors from
   * the same application on the same worker. Consider appA and appB both have one executor running
   * on worker1, and appA.coresLeft > 0, then appB is finished and release all its cores on worker1,
   * thus for the next schedule iteration, appA launches a new executor that grabs all the free
   * cores on worker1, therefore we get multiple executors from appA running on worker1.
   *
   * It is important to allocate coresPerExecutor on each worker at a time (instead of 1 core
   * at a time). Consider the following example: cluster has 4 workers with 16 cores each.
   * User requests 3 executors (spark.cores.max = 48, spark.executor.cores = 16). If 1 core is
   * allocated at a time, 12 cores from each worker would be assigned to each executor.
   * Since 12 < 16, no executors would launch [SPARK-8881].
   */
  private def scheduleExecutorsOnWorkers(
      app: ApplicationInfo,
      usableWorkers: Array[WorkerInfo],
      spreadOutApps: Boolean): Array[Int] = {
    // 在app中设定的为每个Executor分配的core
    val coresPerExecutor = app.desc.coresPerExecutor
    // 如果用户没有指定，那么默认是为每个Executor分配1个core
    val minCoresPerExecutor = coresPerExecutor.getOrElse(1)

    // 如果没有指定每个Executor的CPU 的core的数量，那么就是说，每个Executor是一个core，然后又却确定每个worker上一个Executor
    val oneExecutorPerWorker = coresPerExecutor.isEmpty

    // 为每个Executor分配的memory
    val memoryPerExecutor = app.desc.memoryPerExecutorMB

    val numUsable = usableWorkers.length
    // 给定的List<worker>上的 可用的core的数量 ===> List< Int<cores> >
    // 创建一个空数组，存储了要分配给每个worker的cpu core的数量
    val assignedCores = new Array[Int](numUsable) // Number of cores to give to each worker
    // 每个worker上即将启动的Executor的数量
    val assignedExecutors = new Array[Int](numUsable) // Number of new executors on each worker

    // 总共有多少个core去 用于分配（获取到底要分配多少CPU core， 取app剩余要分配的CPU core的数量和worker总共可用对的CPU core的数量的最小值）
    var coresToAssign = math.min(app.coresLeft, usableWorkers.map(_.coresFree).sum)

    /** Return whether the specified worker can launch an executor for this app. */
    def canLaunchExecutor(pos: Int): Boolean = {
      val keepScheduling = coresToAssign >= minCoresPerExecutor
      val enoughCores = usableWorkers(pos).coresFree - assignedCores(pos) >= minCoresPerExecutor

      // If we allow multiple executors per worker, then we can always launch new executors.
      // Otherwise, if there is already an executor on this worker, just give it more cores.
      val launchingNewExecutor = !oneExecutorPerWorker || assignedExecutors(pos) == 0
      if (launchingNewExecutor) {
        val assignedMemory = assignedExecutors(pos) * memoryPerExecutor
        val enoughMemory = usableWorkers(pos).memoryFree - assignedMemory >= memoryPerExecutor
        val underLimit = assignedExecutors.sum + app.executors.size < app.executorLimit
        keepScheduling && enoughCores && enoughMemory && underLimit
      } else {
        // We're adding cores to an existing executor, so no need
        // to check memory and executor limits
        keepScheduling && enoughCores
      }
    }

    // Keep launching executors until no more workers can accommodate any
    // more executors, or if we have reached this application's limits
    var freeWorkers = (0 until numUsable).filter(canLaunchExecutor)
    while (freeWorkers.nonEmpty) {
      freeWorkers.foreach { pos =>
        var keepScheduling = true
        while (keepScheduling && canLaunchExecutor(pos)) {
          coresToAssign -= minCoresPerExecutor
          // 当前worker上，加上此次需要添加的core
          assignedCores(pos) += minCoresPerExecutor

          // If we are launching one executor per worker, then every iteration assigns 1 core
          // to the executor. Otherwise, every iteration assigns cores to a new executor.
          if (oneExecutorPerWorker) {
            // 如果是每个worker上是一个Executor，那么就指定，这个worker上的Executor数量是1
            assignedExecutors(pos) = 1
          } else {
            // 如果每个worker上设置的Executor的数量不是1，那么就在原来的基础上加1，此时的数量就是当前这个worker上Executor的数量
            assignedExecutors(pos) += 1
          }

          // Spreading out an application means spreading out its executors across as
          // many workers as possible. If we are not spreading out, then we should keep
          // scheduling executors on this worker until we use all of its resources.
          // Otherwise, just move on to the next worker.
          // 如果没有设置 spreadOutApps=true，那么将会在一个worker上循环多遍，直到用尽该Worker上的CPU core
          // 但是如果设置的spreadOutApps=true，那么在一个Worker上的循环，就只会一次，那么总的来说，就会将core分配到尽可能多的Worker上去
          if (spreadOutApps) {
            keepScheduling = false
          }
        }
      }
      // 这里需要再次过滤一遍，看剩余那些worker能够使用
      freeWorkers = freeWorkers.filter(canLaunchExecutor)
    }
    // 返回每个worker上的给当前application分配的CPU core
    assignedCores
  }

  /**
   * Schedule and launch executors on workers
    * 调度和启动executor，在worker上
   */
  private def startExecutorsOnWorkers(): Unit = {
    // Right now this is a very simple FIFO scheduler. We keep trying to fit in the first app in the queue, then the second app, etc.
    // 这里是采用FIFO的调度策略，为每一个APP分配对应的executor
    for (app <- waitingApps) {// 这里为每个正在waiting的application启动Executor
      // 默认每个executor是分配一个core
      val coresPerExecutor = app.desc.coresPerExecutor.getOrElse(1)
      // If the cores left is less than the coresPerExecutor,the cores left will not be allocated
      if (app.coresLeft >= coresPerExecutor) {
        // Filter out workers that don't have enough resources to launch an executor
        // 拿到那些可用的Worker
        val usableWorkers = workers.toArray
          .filter(_.state == WorkerState.ALIVE)// 过滤出是alive的Executor
          .filter(worker => worker.memoryFree >= app.desc.memoryPerExecutorMB && worker.coresFree >= coresPerExecutor)// memory 和 core能过满足启动Executor的条件
          .sortBy(_.coresFree).reverse// 按照core的大小倒叙排列


        // spreadOutApps：让app分布到尽可能多的worker上去
        // 这里拿到的是 usableWorkers 的 可以使用的cores ,他是一个list
        val assignedCores = scheduleExecutorsOnWorkers(app, usableWorkers, spreadOutApps)

        // Now that we've decided how many cores to allocate on each worker, let's allocate them
        for (pos <- 0 until usableWorkers.length if assignedCores(pos) > 0) {// 这里循环遍历所有可用的worker，assignedCores(pos) > 0 是当前worker上的分配到了CPU core
          // 分配资源到executor上，然后调用 rpc 去worker上启动executor
          // 这个app，在这个Worker上（usableWorkers(pos)）， 分配的总的core数量（assignedCores(pos)），
          // 这个worker上每个Executor分配的core的数量（app.desc.coresPerExecutor） ，这个就可以得出 对于这个app，在这个worker上启动的Executor的数量
          allocateWorkerResourceToExecutors(
            app, // 这个application
            assignedCores(pos),         // 分配了多少个core
            app.desc.coresPerExecutor, // 每个Executor上分配多少个core
            usableWorkers(pos)        // 在哪个worker上
          )

        }
      }
    }
  }

  /**
   * Allocate a worker's resources to one or more executors.
   * @param app the info of the application which the executors belong to
   * @param assignedCores number of cores on this worker for this application
   * @param coresPerExecutor number of cores per executor
   * @param worker the worker info
   */
  private def allocateWorkerResourceToExecutors(
      app: ApplicationInfo,
      assignedCores: Int,
      coresPerExecutor: Option[Int],
      worker: WorkerInfo): Unit = {
    // If the number of cores per executor is specified, we divide the cores assigned
    // to this worker evenly among the executors with no remainder.
    // Otherwise, we launch a single executor that grabs all the assignedCores on this worker.

    // 通过分配的总的core，每个Executor的core，算出有多少个Executor
    val numExecutors = coresPerExecutor.map { assignedCores / _ }.getOrElse(1)
    /*
    在spark-submit脚本中，可以指定多少个executor，每个executor有多少个core，每个executor的内存，这是我们在启动的时候的配置？
    但是Executor的实际的数量，以及每个Executor的core，可能与配置的不一致，因为在代码中，我们可以看到，spark是基于总的core，
    和每个Executor的core 去计算需要启动的Executor的数量，
    */

    //每个executor的cores
    val coresToAssign = coresPerExecutor.getOrElse(assignedCores)
    for (i <- 1 to numExecutors) {
      // 这个worker上启动一个Executor，并指定这个Executor上的分配的core的数量
      val exec = app.addExecutor(worker, coresToAssign)

      // 启动Executor
      launchExecutor(worker, exec)

      // 改变application的状态为running
      app.state = ApplicationState.RUNNING
    }
  }

  /**
   * Schedule the currently available resources among waiting apps. This method will be called
   * every time a new app joins or resource availability changes.
    * 这里首先只会根据worker的CPU cores进行schedule, 而不会考虑其他的资源, 可用考虑让app尽可能分布在更多或更少的workers上,
    * 最后向worker actor发送LaunchExecutor, 真正启动ExecutorBackend
   */
  /*
  schedule方法首先会启动driver，然后启动对应的Executor
   */
  private def schedule(): Unit = {
    if (state != RecoveryState.ALIVE) {
      // 如果是standby模式的Master，那么直接返回
      return
    }
    // Drivers take strict precedence over executors
    // 将活跃的worker随机打乱，放在一个List中,
    // 对Worker节点进行随机排序，能够使Driver更加均衡分布在集群中，这里的workers是在worker注册的时候，会加入到workers中
    val shuffledAliveWorkers = Random.shuffle(
        workers.toSeq.filter(_.state == WorkerState.ALIVE)// 过滤出状态为ALIVE的worker
    )// 然后随机的打乱

    // 当前可用worker的数量
    val numWorkersAlive = shuffledAliveWorkers.size

    var curPos = 0
    // 循环遍历所有等待提交的driver
    /*
    这里为什么要调度driver呢？
    什么情况下，会注册driver，并且会导致driver被调度呢？
    其实，只有用yarn-cluster模式提交的时候，才会注册driver，因为standalone和yarn-client模式，都会在本地直接启动driver
    ，而不会来注册driver，就更不可能让Master来调度driver了
     */
    for (driver <- waitingDrivers.toList) { // iterate over a copy of waitingDrivers
      // We assign workers to each waiting driver in a round-robin fashion. For each driver, we
      // start from the last worker that was assigned a driver, and continue onwards until we have
      // explored all alive workers.
      var launched = false
      var numWorkersVisited = 0

      // 只要还有alive的worker没有遍历到，那么就接着遍历  && 当前driver没有启动
      while (numWorkersVisited < numWorkersAlive && !launched) {

        val worker = shuffledAliveWorkers(curPos)
        numWorkersVisited += 1 // 对于已经遍历的worker数加1

        // 如果当前worker的内存和core的数量 满足 当前启动driver的条件，那么就去启动driver
        if (worker.memoryFree >= driver.desc.mem && worker.coresFree >= driver.desc.cores) {
          // 在指定的worker上启动Driver
          launchDriver(worker, driver)

          waitingDrivers -= driver  //从缓存中去掉该driver
          launched = true
        }
        //下一次需要启动的executor所在的worker(  将指针指向下一个worker)
        curPos = (curPos + 1) % numWorkersAlive
      }
    }

    //在work上启动executor（其实为在等待中的application去启动Executor），这里也可以说成是application的调度机制
    startExecutorsOnWorkers()
  }

  //通过rpc去worker上启动executor
  private def launchExecutor(worker: WorkerInfo, exec: ExecutorDesc): Unit = {

    logInfo("Launching executor " + exec.fullId + " on worker " + worker.id)
    // 将Executor加入Worker内部的缓存，（在worker内部，会有coresUsed，memoryUsed 会相应加上该Executor的消耗的部分）
    worker.addExecutor(exec)

    // 发送请求，告诉worker启动Executor
    worker.endpoint.send(LaunchExecutor(masterUrl,
      exec.application.id, exec.id, exec.application.desc, exec.cores, exec.memory))

    // 告诉driver，已经启动了一个Executor
    exec.application.driver.send(
      ExecutorAdded(exec.id, worker.id, worker.hostPort, exec.cores, exec.memory))

  }

  private def registerWorker(worker: WorkerInfo): Boolean = {
    // There may be one or more refs to dead workers on this same node (w/ different ID's),
    // remove them.
    workers.filter { w =>
      (w.host == worker.host && w.port == worker.port) && (w.state == WorkerState.DEAD)
    }.foreach { w =>
      workers -= w
    }

    val workerAddress = worker.endpoint.address
    if (addressToWorker.contains(workerAddress)) {
      val oldWorker = addressToWorker(workerAddress)
      if (oldWorker.state == WorkerState.UNKNOWN) {
        // A worker registering from UNKNOWN implies that the worker was restarted during recovery.
        // The old worker must thus be dead, so we will remove it and accept the new worker.
        removeWorker(oldWorker, "Worker replaced by a new worker with same address")
      } else {
        logInfo("Attempted to re-register worker at same address: " + workerAddress)
        return false
      }
    }

    //将worker加入到内存中
    workers += worker
    idToWorker(worker.id) = worker
    addressToWorker(workerAddress) = worker
    true
  }

  private def removeWorker(worker: WorkerInfo, msg: String) {
    logInfo("Removing worker " + worker.id + " on " + worker.host + ":" + worker.port)
    worker.setState(WorkerState.DEAD)
    idToWorker -= worker.id
    addressToWorker -= worker.endpoint.address

    for (exec <- worker.executors.values) {
      // 向application发送消息，告诉application，有些Executor已经lost
      logInfo("Telling app of lost executor: " + exec.id)
      exec.application.driver.send(ExecutorUpdated(
        exec.id, ExecutorState.LOST, Some("worker lost"), None, workerLost = true))
      exec.state = ExecutorState.LOST
      exec.application.removeExecutor(exec)
    }
    for (driver <- worker.drivers.values) {
      if (driver.desc.supervise) {// 如果配置了这个属性supervise，那么会重新启动driver
        logInfo(s"Re-launching ${driver.id}")
        relaunchDriver(driver)
      } else {
        logInfo(s"Not re-launching ${driver.id} because it was not supervised")
        removeDriver(driver.id, DriverState.ERROR, None)
      }
    }
    logInfo(s"Telling app of lost worker: " + worker.id)
    apps.filterNot(completedApps.contains(_)).foreach { app =>
      app.driver.send(WorkerRemoved(worker.id, worker.host, msg))
    }
    persistenceEngine.removeWorker(worker)
  }

  private def relaunchDriver(driver: DriverInfo) {
    // We must setup a new driver with a new driver id here, because the original driver may
    // be still running. Consider this scenario: a worker is network partitioned with master,
    // the master then relaunches driver driverID1 with a driver id driverID2, then the worker
    // reconnects to master. From this point on, if driverID2 is equal to driverID1, then master
    // can not distinguish the statusUpdate of the original driver and the newly relaunched one,
    // for example, when DriverStateChanged(driverID1, KILLED) arrives at master, master will
    // remove driverID1, so the newly relaunched driver disappears too. See SPARK-19900 for details.
    removeDriver(driver.id, DriverState.RELAUNCHING, None)
    val newDriver = createDriver(driver.desc)
    persistenceEngine.addDriver(newDriver)
    drivers.add(newDriver)
    waitingDrivers += newDriver

    schedule()
  }

  private def createApplication(desc: ApplicationDescription, driver: RpcEndpointRef):
      ApplicationInfo = {
    val now = System.currentTimeMillis()
    val date = new Date(now)
    // app-yyyyMMddHHmmss-1111
    val appId = newApplicationId(date)
    // 这里的desc是对application的描述，这是在driver的backend注册application之前就需要封装好的
    new ApplicationInfo(now, appId, desc, date, driver, defaultCores)
  }

  private def registerApplication(app: ApplicationInfo): Unit = {
    val appAddress = app.driver.address
    // 如果driver的地址存在，那么就返回
    if (addressToApp.contains(appAddress)) {
      logInfo("Attempted to re-register application at same address: " + appAddress)
      return
    }

    applicationMetricsSystem.registerSource(app.appSource)
    apps += app
    // app.id和app的映射关系
    idToApp(app.id) = app
    // driver 和app的映射关系
    endpointToApp(app.driver) = app
    // appAddress和app的映射关系
    addressToApp(appAddress) = app

    // 将APP加入等待的buffer中，在schedule()方法调用的时候，就会启动等待的app需要的Executor
    waitingApps += app
  }

  private def finishApplication(app: ApplicationInfo) {
    removeApplication(app, ApplicationState.FINISHED)
  }

  def removeApplication(app: ApplicationInfo, state: ApplicationState.Value) {
    if (apps.contains(app)) {
      logInfo("Removing app " + app.id)
      apps -= app
      idToApp -= app.id
      endpointToApp -= app.driver
      addressToApp -= app.driver.address

      if (completedApps.size >= RETAINED_APPLICATIONS) {
        val toRemove = math.max(RETAINED_APPLICATIONS / 10, 1)
        completedApps.take(toRemove).foreach { a =>
          applicationMetricsSystem.removeSource(a.appSource)
        }
        completedApps.trimStart(toRemove)
      }
      completedApps += app // Remember it in our history
      waitingApps -= app

      for (exec <- app.executors.values) {
        killExecutor(exec)
      }
      app.markFinished(state)
      if (state != ApplicationState.FINISHED) {
        app.driver.send(ApplicationRemoved(state.toString))
      }
      persistenceEngine.removeApplication(app)
      schedule()

      // Tell all workers that the application has finished, so they can clean up any app state.
      workers.foreach { w =>
        w.endpoint.send(ApplicationFinished(app.id))
      }
    }
  }

  /**
   * Handle a request to set the target number of executors for this application.
   *
   * If the executor limit is adjusted upwards, new executors will be launched provided
   * that there are workers with sufficient resources. If it is adjusted downwards, however,
   * we do not kill existing executors until we explicitly receive a kill request.
   *
   * @return whether the application has previously registered with this Master.
   */
  private def handleRequestExecutors(appId: String, requestedTotal: Int): Boolean = {
    idToApp.get(appId) match {
      case Some(appInfo) =>
        logInfo(s"Application $appId requested to set total executors to $requestedTotal.")
        appInfo.executorLimit = requestedTotal
        schedule()
        true
      case None =>
        logWarning(s"Unknown application $appId requested $requestedTotal total executors.")
        false
    }
  }

  /**
   * Handle a kill request from the given application.
   *
   * This method assumes the executor limit has already been adjusted downwards through
   * a separate [[RequestExecutors]] message, such that we do not launch new executors
   * immediately after the old ones are removed.
   *
   * @return whether the application has previously registered with this Master.
   */
  private def handleKillExecutors(appId: String, executorIds: Seq[Int]): Boolean = {
    idToApp.get(appId) match {
      case Some(appInfo) =>
        logInfo(s"Application $appId requests to kill executors: " + executorIds.mkString(", "))
        val (known, unknown) = executorIds.partition(appInfo.executors.contains)
        known.foreach { executorId =>
          val desc = appInfo.executors(executorId)
          appInfo.removeExecutor(desc)
          killExecutor(desc)
        }
        if (unknown.nonEmpty) {
          logWarning(s"Application $appId attempted to kill non-existent executors: "
            + unknown.mkString(", "))
        }
        schedule()
        true
      case None =>
        logWarning(s"Unregistered application $appId requested us to kill executors!")
        false
    }
  }

  /**
   * Cast the given executor IDs to integers and filter out the ones that fail.
   *
   * All executors IDs should be integers since we launched these executors. However,
   * the kill interface on the driver side accepts arbitrary strings, so we need to
   * handle non-integer executor IDs just to be safe.
   */
  private def formatExecutorIds(executorIds: Seq[String]): Seq[Int] = {
    executorIds.flatMap { executorId =>
      try {
        Some(executorId.toInt)
      } catch {
        case e: NumberFormatException =>
          logError(s"Encountered executor with a non-integer ID: $executorId. Ignoring")
          None
      }
    }
  }

  /**
   * Ask the worker on which the specified executor is launched to kill the executor.
   */
  private def killExecutor(exec: ExecutorDesc): Unit = {
    exec.worker.removeExecutor(exec)
    exec.worker.endpoint.send(KillExecutor(masterUrl, exec.application.id, exec.id))
    exec.state = ExecutorState.KILLED
  }

  /** Generate a new app ID given an app's submission date */
  private def newApplicationId(submitDate: Date): String = {
    val appId = "app-%s-%04d".format(createDateFormat.format(submitDate), nextAppNumber)
    nextAppNumber += 1
    appId
  }

  /** Check for, and remove, any timed-out workers */
  private def timeOutDeadWorkers() {
    // Copy the workers into an array so we don't modify the hashset while iterating through it
    val currentTime = System.currentTimeMillis()
    val toRemove = workers.filter(_.lastHeartbeat < currentTime - WORKER_TIMEOUT_MS).toArray
    for (worker <- toRemove) {
      if (worker.state != WorkerState.DEAD) {
        logWarning("Removing %s because we got no heartbeat in %d seconds".format(
          worker.id, WORKER_TIMEOUT_MS / 1000))
        removeWorker(worker, s"Not receiving heartbeat for ${WORKER_TIMEOUT_MS / 1000} seconds")
      } else {
        if (worker.lastHeartbeat < currentTime - ((REAPER_ITERATIONS + 1) * WORKER_TIMEOUT_MS)) {
          workers -= worker // we've seen this DEAD worker in the UI, etc. for long enough; cull it
        }
      }
    }
  }

  private def newDriverId(submitDate: Date): String = {
    val appId = "driver-%s-%04d".format(createDateFormat.format(submitDate), nextDriverNumber)
    nextDriverNumber += 1
    appId
  }

  private def createDriver(desc: DriverDescription): DriverInfo = {
    val now = System.currentTimeMillis()
    val date = new Date(now)
    new DriverInfo(now, newDriverId(date), desc, date)
  }

  // 在某一个worker上启动Driver
  private def launchDriver(worker: WorkerInfo, driver: DriverInfo) {
    logInfo("Launching driver " + driver.id + " on worker " + worker.id)

    // worker内部会维护一个driver的Map<driver.id, driver>
    worker.addDriver(driver)// 这个方法还会做的事情是：加上内存和CPU的core的数量

    // 在driver内部也会维护一个worker，表示这个driver从属于哪一个worker
    driver.worker = Some(worker)
    // 向这个worker发送启动Driver的请求
    worker.endpoint.send(LaunchDriver(driver.id, driver.desc))

    // 设置driver的状态设置为running
    driver.state = DriverState.RUNNING
  }

  private def removeDriver(
      driverId: String,
      finalState: DriverState,
      exception: Option[Exception]) {
    // 从drivers中找到对应的driver，然后
    drivers.find(d => d.id == driverId) match {
      case Some(driver) =>
        logInfo(s"Removing driver: $driverId")
        // 从内存中移除
        drivers -= driver

        // 如果已经完成的driver的数量是>= RETAINED_DRIVERS(保留的driver数量的大小)
        if (completedDrivers.size >= RETAINED_DRIVERS) {// ====>200
          val toRemove = math.max(RETAINED_DRIVERS / 10, 1)// ====> 20
          // 这里应该是：当completedDrivers的大小（size)超过200，就移除其中的前20个，trimStart应该就是这个意思，待验证
          completedDrivers.trimStart(toRemove)
        }
        // 添加到已经完成的内存中
        completedDrivers += driver
        // 持久化引擎中移除
        persistenceEngine.removeDriver(driver)
        driver.state = finalState
        driver.exception = exception
        // 从worker中移除driver
        driver.worker.foreach(w => w.removeDriver(driver))
        // 再次调度， 由于移除Driver释放了一些资源，所以可以再次调度，给其他等待的application分配资源
        schedule()
      case None =>
        logWarning(s"Asked to remove unknown driver: $driverId")
    }
  }
}

private[deploy] object Master extends Logging {
  val SYSTEM_NAME = "sparkMaster"
  val ENDPOINT_NAME = "Master"

  def main(argStrings: Array[String]) {
    Thread.setDefaultUncaughtExceptionHandler(new SparkUncaughtExceptionHandler(
      exitOnUncaughtException = false))
    Utils.initDaemon(log)
    val conf = new SparkConf
    val args = new MasterArguments(argStrings, conf)
    val (rpcEnv, _, _) = startRpcEnvAndEndpoint(args.host, args.port, args.webUiPort, conf)
    rpcEnv.awaitTermination()
  }

  /**
   * Start the Master and return a three tuple of:
   *   (1) The Master RpcEnv
   *   (2) The web UI bound port
   *   (3) The REST server bound port, if any
   */
  def startRpcEnvAndEndpoint(
      host: String,
      port: Int,
      webUiPort: Int,
      conf: SparkConf): (RpcEnv, Int, Option[Int]) = {
    val securityMgr = new SecurityManager(conf)
    val rpcEnv = RpcEnv.create(SYSTEM_NAME, host, port, conf, securityMgr)
    val masterEndpoint = rpcEnv.setupEndpoint(ENDPOINT_NAME,
      new Master(rpcEnv, rpcEnv.address, webUiPort, securityMgr, conf))
    val portsResponse = masterEndpoint.askSync[BoundPortsResponse](BoundPortsRequest)
    (rpcEnv, portsResponse.webUIPort, portsResponse.restPort)
  }
}
