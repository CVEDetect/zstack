package org.zstack.header.vm;

import org.zstack.header.message.NeedReplyMessage;

import java.util.HashMap;
import java.util.Map;

/**
 */
public class VmAttachNicMsg extends NeedReplyMessage implements VmInstanceMessage {
    private String vmInstanceUuid;
    private String l3NetworkUuid;
    private Map<String, String> staticIpMap = new HashMap<>();
    private boolean allowDuplicatedAddress = false;
    private boolean applyToBacked = true;

    public boolean isAllowDuplicatedAddress() {
        return allowDuplicatedAddress;
    }

    public void setAllowDuplicatedAddress(boolean allowDuplicatedAddress) {
        this.allowDuplicatedAddress = allowDuplicatedAddress;
    }

    @Override
    public String getVmInstanceUuid() {
        return vmInstanceUuid;
    }

    public void setVmInstanceUuid(String vmInstanceUuid) {
        this.vmInstanceUuid = vmInstanceUuid;
    }

    public String getL3NetworkUuid() {
        return l3NetworkUuid;
    }

    public void setL3NetworkUuid(String l3NetworkUuid) {
        this.l3NetworkUuid = l3NetworkUuid;
    }

    public Map<String, String> getStaticIpMap() {
        return staticIpMap;
    }

    public void setStaticIpMap(Map<String, String> staticIpMap) {
        this.staticIpMap = staticIpMap;
    }

    public boolean isApplyToBacked() {
        return applyToBacked;
    }

    public void setApplyToBacked(boolean applyToBacked) {
        this.applyToBacked = applyToBacked;
    }
}
