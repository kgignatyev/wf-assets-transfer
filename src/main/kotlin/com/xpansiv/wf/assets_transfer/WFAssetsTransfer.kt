package com.xpansiv.wf.assets_transfer

import com.xpansiv.demo.fsm.asset_transfer.TransferAssetsContext
import com.xpansiv.demo.fsm.asset_transfer.TransferAssetsHandler
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.workflow.*
import org.apache.commons.io.IOUtils
import java.io.InputStreamReader
import java.time.Duration

//normally this will be a part of some common library used by other workflows
interface VisualWF {
    @QueryMethod
    fun currentStateName():String

    @QueryMethod
    fun describeWorkflowInDot():String

    @QueryMethod
    fun getFSMDefinition():String
}

@WorkflowInterface
interface WFAssetsTransfer:VisualWF {
    @WorkflowMethod
    fun transferAssets(senderId:String, recipientId:String, ein:String, quantity:Long)


    @SignalMethod
    fun LockSenderFunds()
    @SignalMethod
    fun InitiateTransfer()
    @SignalMethod
    fun ApprovedByCP()
    @SignalMethod
    fun CancelTransfer()
    @SignalMethod
    fun ReceivedByRecepient()
    @SignalMethod
    fun RejectedByCP()
}


object WFAssetsTransferConst {
    const val WF_TASKS_QUEUE = "asset_transfers_wf"
    const val TASKS_QUEUE = "asset_transfers_tasks"
}

@ActivityInterface
interface CounterpartsInfoSupplier {
    fun orgAutoApprovesTransfers( orgId:String):Boolean
}



class WFAssetsTransferImpl(): WFAssetsTransfer {

    private lateinit var  decisionContext:TransferAssetsContext
    private lateinit var  dh: TransferAssetsHandler

    private val activityOptions = buildActivityOptions(WFAssetsTransferConst.TASKS_QUEUE)

    private val cpInfoSupplier =  Workflow.newActivityStub(CounterpartsInfoSupplier::class.java, activityOptions)


    private fun buildActivityOptions(queueName: String): ActivityOptions {
        return ActivityOptions.newBuilder()
            .setTaskQueue(queueName)
            .setStartToCloseTimeout(Duration.ofSeconds(5)).build()
    }


    val terminalStates = hashSetOf("Main.DONE","Main.CANCELLED","Main.REJECTED")

    private var signalHandled = false

    override fun transferAssets(senderId: String, recipientId: String, ein: String, quantity: Long) {
        dh = TransferAssetsHandlerExt(senderId, recipientId, ein, quantity, cpInfoSupplier)
        decisionContext = TransferAssetsContext(dh)
        while (! terminalStates.contains( decisionContext.state.name )){
            Workflow.await{signalHandled}
        }
    }

    override fun currentStateName(): String {
        return decisionContext.state.name
    }

    override fun describeWorkflowInDot(): String {
        return IOUtils.toString( InputStreamReader( this::class.java.classLoader.getResourceAsStream("com/xpansiv/demo/fsm/asset_transfer/TransferAssets_sm.dot")!!))
    }

    override fun getFSMDefinition(): String {
        return IOUtils.toString( InputStreamReader( this::class.java.classLoader.getResourceAsStream("TransferAssets.sm")!!))
    }

    override fun LockSenderFunds(){
        decisionContext.LockSenderFunds()
    }
    
    override fun InitiateTransfer(){
        decisionContext.InitiateTransfer()
    }

    override fun ApprovedByCP(){
        decisionContext.ApprovedByCP()
    }

    override fun CancelTransfer(){
        decisionContext.CancelTransfer()
    }

    override fun ReceivedByRecepient(){
        decisionContext.ReceivedByRecepient()
    }

    override fun RejectedByCP(){
        decisionContext.RejectedByCP()
    }
}

