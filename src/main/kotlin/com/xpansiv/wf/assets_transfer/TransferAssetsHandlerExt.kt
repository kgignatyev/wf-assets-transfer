package com.xpansiv.wf.assets_transfer

import com.xpansiv.demo.fsm.asset_transfer.TransferAssetsHandler

class TransferAssetsHandlerExt(fromId: String, toId: String, ein: String, quantity: Long, val cpInfoSupplier: CounterpartsInfoSupplier) :
    TransferAssetsHandler(fromId, toId, ein, quantity) {


    override fun needsCPapproval(): Boolean {
        return ! cpInfoSupplier.orgAutoApprovesTransfers( toId )
    }


}
