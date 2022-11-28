package com.xpansiv.wf.assets_transfer

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.Worker
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.awaitility.Awaitility
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.util.*

class CounterpartsInfoSupplierTestImpl : CounterpartsInfoSupplier {

    var _orgAutoApprovesTransfers = true

    override fun orgAutoApprovesTransfers(orgId: String): Boolean {
        return _orgAutoApprovesTransfers
    }


}

class WFAssetsTransferTest {

    var logger: Logger = LoggerFactory.getLogger(WFAssetsTransferTest::class.java)

    @Rule  @JvmField
    public var watchman: TestWatcher = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            if (testEnv != null) {
                System.err.println(testEnv!!.diagnostics)
                testEnv!!.close()
            }
        }
    }


    private lateinit var testEnv: TestWorkflowEnvironment
    private lateinit var wfWorker: Worker
    private lateinit var activitiesWorker: Worker
    private val cpInfoSupplierTest: CounterpartsInfoSupplierTestImpl = CounterpartsInfoSupplierTestImpl()
    private lateinit var wfClient: WorkflowClient
    private lateinit var wfService: WorkflowServiceStubs
    private lateinit var wfNamespace: String

    val senderID = "s123"
    val recipientID = "r321"
    val ein = "E9988"

    @Before
    fun setUp() {
        testEnv = TestWorkflowEnvironment.newInstance()
        wfWorker = testEnv.newWorker(WFAssetsTransferConst.WF_TASKS_QUEUE)
        wfWorker.registerWorkflowImplementationTypes(WFAssetsTransferImpl::class.java)
        activitiesWorker = testEnv.newWorker(WFAssetsTransferConst.TASKS_QUEUE)
        activitiesWorker.registerActivitiesImplementations(cpInfoSupplierTest)
        testEnv.start()
        wfNamespace = "default"
        wfService = testEnv.getWorkflowServiceStubs()
        wfClient = testEnv.getWorkflowClient()
    }

    @After
    fun tearDown() {
        testEnv.close()
    }

    @Test
    fun testWfWithoutApproval() {

        val searchAttributes = createSA("" + System.nanoTime())
        val workflowOptions = WorkflowOptions.newBuilder()
            .setTaskQueue(WFAssetsTransferConst.WF_TASKS_QUEUE)
            .setSearchAttributes(searchAttributes)
            .setMemo(searchAttributes)
            .build()
        val workflowStarterStub: WFAssetsTransfer = wfClient.newWorkflowStub(WFAssetsTransfer::class.java, workflowOptions)
        val wfExecution = WorkflowClient.start(workflowStarterStub::transferAssets, senderID, recipientID, ein, 200L)
        val wfID = wfExecution.workflowId
        val wfRuntimeStub = wfClient.newWorkflowStub(WFAssetsTransfer::class.java, wfID, Optional.empty())
        val dotDescription = wfRuntimeStub.describeWorkflowInDot()
        FileUtils.writeStringToFile( File("target/wf-description.dot"), dotDescription )
        val smDescription = wfRuntimeStub.getFSMDefinition()
        FileUtils.writeStringToFile( File("target/wf-description.sm"), smDescription )
        var counter = 1
        cpInfoSupplierTest._orgAutoApprovesTransfers = true
        awaitAndVisualizeState( wfRuntimeStub, "Main.STARTED", "without-approval-${counter++}")
        wfRuntimeStub.LockSenderFunds()
        awaitAndVisualizeState( wfRuntimeStub, "Main.SENDER_FUNDS_LOCKED", "without-approval-${counter++}")
        wfRuntimeStub.InitiateTransfer()
        awaitAndVisualizeState( wfRuntimeStub, "Main.PLACED_TO_CP", "without-approval-${counter++}")
        wfRuntimeStub.ReceivedByRecepient()
        awaitAndVisualizeState( wfRuntimeStub, "Main.DONE", "without-approval-${counter++}")
    }

    @Test
    fun testWfApprovalRequired() {

        val searchAttributes = createSA("" + System.nanoTime())
        val workflowOptions = WorkflowOptions.newBuilder()
            .setTaskQueue(WFAssetsTransferConst.WF_TASKS_QUEUE)
            .setSearchAttributes(searchAttributes)
            .setMemo(searchAttributes)
            .build()
        val workflowStarterStub: WFAssetsTransfer = wfClient.newWorkflowStub(WFAssetsTransfer::class.java, workflowOptions)
        val wfExecution = WorkflowClient.start(workflowStarterStub::transferAssets, senderID, recipientID, ein, 200L)
        val wfID = wfExecution.workflowId
        val wfRuntimeStub = wfClient.newWorkflowStub(WFAssetsTransfer::class.java, wfID, Optional.empty())
        val dotDescription = wfRuntimeStub.describeWorkflowInDot()
        FileUtils.writeStringToFile( File("target/wf-description.dot"), dotDescription )
        var counter = 1
        cpInfoSupplierTest._orgAutoApprovesTransfers = false
        awaitAndVisualizeState( wfRuntimeStub, "Main.STARTED", "approval-required-${counter++}")
        wfRuntimeStub.LockSenderFunds()
        awaitAndVisualizeState( wfRuntimeStub, "Main.SENDER_FUNDS_LOCKED", "approval-required-${counter++}")
        wfRuntimeStub.InitiateTransfer()
        awaitAndVisualizeState( wfRuntimeStub, "Main.WAITING_CP_DECISION", "approval-required-${counter++}")
        wfRuntimeStub.ApprovedByCP()
        awaitAndVisualizeState( wfRuntimeStub, "Main.PLACED_TO_CP", "approval-required-${counter++}")
        wfRuntimeStub.ReceivedByRecepient()
        awaitAndVisualizeState( wfRuntimeStub, "Main.DONE", "approval-required-${counter++}")
    }

    private fun awaitAndVisualizeState(wfStub4q: WFAssetsTransfer, stateName: String, prefix:String) {
        Awaitility.await("workflow state $stateName").atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofSeconds(1))
            .until {
                try {
                    val currentStateName = wfStub4q.currentStateName()
                    println(currentStateName)
                    return@until stateName == currentStateName
                } catch (e: Exception) {
                    //ignoring
                    e.printStackTrace()
                }
                false
            }
        visualizeState( stateName, "wf-description.dot", "$prefix.png")
    }

    private fun createSA(correlationId: String): Map<String, Any?> {
        val res = HashMap<String, Any?>()
        res.put("CustomTextField", correlationId)
        return res
    }

    @Throws(java.lang.Exception::class)
    private fun visualizeState(fullWfStateName: String, dotFileName:String, pngFile: String) {
        val stateName = fullWfStateName.split("[.]".toRegex()).dropLastWhile { it.isEmpty() }
            .toTypedArray()[1]
        val p = Runtime.getRuntime().exec(
            arrayOf(
                "docker", "run", "--rm",
                "-v", System.getProperty("user.dir") + "/target:/dot/data",
                "xpansiv.jfrog.io/default-docker-virtual/xpansiv-smcdot:1",
                "/usr/bin/dot_file2png.py",
                "--in", "data/$dotFileName",
                "--out", "data/$pngFile",
                "--state", stateName
            )
        )
        p.waitFor()
        val output = IOUtils.toString(p.inputStream)
        val errorOutput = IOUtils.toString(p.errorStream)
        println(output)
        System.err.println(errorOutput)
        if (p.exitValue() != 0) {
            throw java.lang.Exception("png generation error")
        }
    }

}
