package org.zstack.core.errorcode;

import org.zstack.header.message.APIReply;
import org.zstack.header.rest.RestResponse;

/**
 * Created by mingjian.deng on 2018/12/3.
 */
@RestResponse(allTo = "inventories")
@Deprecated
public class APIGetMissedElaborationReply extends APIReply {
    private String inventories;

    public String getInventories() {
        return inventories;
    }

    public void setInventories(String inventories) {
        this.inventories = inventories;
    }

    public static APIGetMissedElaborationReply __example__() {
        APIGetMissedElaborationReply reply = new APIGetMissedElaborationReply();

        return reply;
    }
}
