package org.zstack.compute.vm;

import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.zstack.core.Platform;
import org.zstack.core.asyncbatch.While;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.CloudBusCallBack;
import org.zstack.core.cloudbus.CloudBusListCallBack;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.db.Q;
import org.zstack.core.db.SQLBatch;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.header.core.NoErrorCompletion;
import org.zstack.header.core.workflow.Flow;
import org.zstack.header.core.workflow.FlowRollback;
import org.zstack.header.core.workflow.FlowTrigger;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.errorcode.OperationFailureException;
import org.zstack.header.image.ImagePlatform;
import org.zstack.header.message.MessageReply;
import org.zstack.header.network.l3.*;
import org.zstack.header.tag.SystemTagVO;
import org.zstack.header.tag.SystemTagVO_;
import org.zstack.header.vm.*;
import org.zstack.network.l3.L3NetworkManager;
import org.zstack.utils.Utils;
import org.zstack.utils.logging.CLogger;
import org.zstack.utils.network.NetworkUtils;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.zstack.core.Platform.operr;
import static org.zstack.core.progress.ProgressReportService.taskProgress;

@Configurable(preConstruction = true, autowire = Autowire.BY_TYPE)
public class VmAllocateNicFlow implements Flow {
    private static final CLogger logger = Utils.getLogger(VmAllocateNicFlow.class);
    @Autowired
    protected DatabaseFacade dbf;
    @Autowired
    protected CloudBus bus;
    @Autowired
    protected ErrorFacade errf;
    @Autowired
    protected L3NetworkManager l3nm;
    @Autowired
    private VmNicManager nicManager;
    @Autowired
    protected VmInstanceManager vmMgr;

    @Override
    public void run(final FlowTrigger trigger, final Map data) {
        taskProgress("create nics");

        final VmInstanceSpec spec = (VmInstanceSpec) data.get(VmInstanceConstant.Params.VmInstanceSpec.toString());

        if (spec.isSkipIpAllocation()) {
            trigger.next();
            return;
        }

        Boolean allowDuplicatedAddress = (Boolean)data.get(VmInstanceConstant.Params.VmAllocateNicFlow_allowDuplicatedAddress.toString());

        // it's unlikely a vm having more than 512 nics
        final BitSet deviceIdBitmap = new BitSet(512);
        for (VmNicInventory nic : spec.getVmInventory().getVmNics()) {
            deviceIdBitmap.set(nic.getDeviceId());
        }

        List<UsedIpInventory> ips = new ArrayList<>();
        List<VmNicInventory> nics = new ArrayList<>();
        data.put(VmInstanceConstant.Params.VmAllocateNicFlow_ips.toString(), ips);
        data.put(VmInstanceConstant.Params.VmAllocateNicFlow_nics.toString(), nics);
        List<ErrorCode> errs = new ArrayList<>();
        Map<String, String> vmStaticIps = new StaticIpOperator().getStaticIpbyVmUuid(spec.getVmInventory().getUuid());
        List<VmNicSpec> firstL3s = VmNicSpec.getFirstL3NetworkInventoryOfSpec(spec.getL3Networks())
                .stream()
                .peek(v -> {
                    if (!Q.New(NormalIpRangeVO.class)
                            .eq(NormalIpRangeVO_.l3NetworkUuid, v.getL3Invs().get(0).getUuid())
                            .isExists()) {
                        throw new OperationFailureException(Platform.operr("there is no available ipRange on L3 network [%s]", v.getL3Invs().get(0).getUuid()));
                    }
                })
                .collect(Collectors.toList());
        new While<>(firstL3s).each((nicSpec, wcomp) -> {
            L3NetworkInventory nw = nicSpec.getL3Invs().get(0);
            int deviceId = deviceIdBitmap.nextClearBit(0);
            deviceIdBitmap.set(deviceId);
            MacOperator mo = new MacOperator();
            String customMac = mo.getMac(spec.getVmInventory().getUuid(), nw.getUuid());
            if (customMac != null){
                mo.deleteCustomMacSystemTag(spec.getVmInventory().getUuid(), nw.getUuid(), customMac);
                customMac = customMac.toLowerCase();
            } else {
                customMac = NetworkUtils.generateMacWithDeviceId((short) deviceId);
            }
            final String mac = customMac;

            // choose vnic factory based on enableSRIOV system tag
            VmInstanceNicFactory vnicFactory;
            boolean enableSriov = Q.New(SystemTagVO.class)
                    .eq(SystemTagVO_.resourceType, VmInstanceVO.class.getSimpleName())
                    .eq(SystemTagVO_.resourceUuid, spec.getVmInventory().getUuid())
                    .eq(SystemTagVO_.tag, String.format("enableSRIOV::%s", nw.getUuid()))
                    .isExists();
            logger.debug(String.format("create %s on l3 network[uuid:%s] inside VmAllocateNicFlow",
                    enableSriov ? "vf nic" : "vnic", nw.getUuid()));

            if (enableSriov) {
                vnicFactory = vmMgr.getVmInstanceNicFactory(VmNicType.valueOf("VF"));
            } else {
                vnicFactory = vmMgr.getVmInstanceNicFactory(VmNicType.valueOf("VNIC"));
            }

            AllocateIpMsg msg = new AllocateIpMsg();
            msg.setL3NetworkUuid(nw.getUuid());
            msg.setAllocateStrategy(spec.getIpAllocatorStrategy());
            String staticIp = vmStaticIps.get(nw.getUuid());
            if (staticIp != null) {
                msg.setRequiredIp(staticIp);
            } else {
                l3nm.updateIpAllocationMsg(msg, customMac);
            }
            if (allowDuplicatedAddress != null) {
                msg.setDuplicatedIpAllowed(allowDuplicatedAddress);
            }
            bus.makeTargetServiceIdByResourceUuid(msg, L3NetworkConstant.SERVICE_ID, nw.getUuid());
            bus.send(msg, new CloudBusCallBack(wcomp) {
                @Override
                public void run(MessageReply reply) {
                    if (reply.isSuccess()) {
                        AllocateIpReply areply = reply.castReply();
                        ips.add(areply.getIpInventory());
                        VmNicInventory nic = new VmNicInventory();
                        nic.setUuid(Platform.getUuid());
                        nic.setIp(areply.getIpInventory().getIp());
                        nic.setIpVersion(areply.getIpInventory().getIpVersion());
                        nic.setUsedIpUuid(areply.getIpInventory().getUuid());
                        nic.setVmInstanceUuid(spec.getVmInventory().getUuid());
                        nic.setL3NetworkUuid(areply.getIpInventory().getL3NetworkUuid());
                        nic.setMac(mac);
                        nic.setHypervisorType(spec.getDestHost() == null ?
                                spec.getVmInventory().getHypervisorType() : spec.getDestHost().getHypervisorType());
                        if (mo.checkDuplicateMac(nic.getHypervisorType(), nic.getMac())) {
                            trigger.fail(operr("Duplicate mac address [%s]", nic.getMac()));
                            return;
                        }

                        if (nicSpec.getNicDriverType() != null) {
                            nic.setDriverType(nicSpec.getNicDriverType());
                        } else {
                            nic.setDriverType(ImagePlatform.valueOf(spec.getVmInventory().getPlatform()).isParaVirtualization() ?
                                    nicManager.getDefaultPVNicDriver() : nicManager.getDefaultNicDriver());
                        }

                        assert nic.getL3NetworkUuid() != null;

                        nic.setDeviceId(deviceId);
                        nic.setNetmask(areply.getIpInventory().getNetmask());
                        nic.setGateway(areply.getIpInventory().getGateway());
                        nic.setInternalName(VmNicVO.generateNicInternalName(spec.getVmInventory().getInternalId(), nic.getDeviceId()));

                        new SQLBatch() {
                            @Override
                            protected void scripts() {
                                vnicFactory.createVmNic(nic, spec);
                                nics.add(nic);
                            }
                        }.execute();
                        wcomp.done();
                    } else {
                        errs.add(reply.getError());
                        wcomp.allDone();
                    }
                }
            });
        }).run(new NoErrorCompletion() {
            @Override
            public void done() {
                if (errs.size() > 0) {
                    trigger.fail(errs.get(0));
                } else {
                    trigger.next();
                }
            }
        });
    }

    @Override
    public void rollback(final FlowRollback chain, Map data) {
        final List<VmNicInventory> destNics = (List<VmNicInventory>) data.get(VmInstanceConstant.Params.VmAllocateNicFlow_nics.toString());
        final List<String> nicUuids = destNics.stream().map(VmNicInventory::getUuid).collect(Collectors.toList());

        List<UsedIpInventory> ips = (List<UsedIpInventory>) data.get(VmInstanceConstant.Params.VmAllocateNicFlow_ips.toString());
        List<ReturnIpMsg> msgs = new ArrayList<ReturnIpMsg>();
        for (UsedIpInventory ip : ips) {
            ReturnIpMsg msg = new ReturnIpMsg();
            msg.setL3NetworkUuid(ip.getL3NetworkUuid());
            msg.setUsedIpUuid(ip.getUuid());
            bus.makeTargetServiceIdByResourceUuid(msg, L3NetworkConstant.SERVICE_ID, ip.getL3NetworkUuid());
            msgs.add(msg);
        }

        if (msgs.isEmpty()) {
            dbf.removeByPrimaryKeys(nicUuids, VmNicVO.class);
            chain.rollback();
            return;
        }

        bus.send(msgs, 1, new CloudBusListCallBack(chain) {
            @Override
            public void run(List<MessageReply> replies) {
                dbf.removeByPrimaryKeys(nicUuids, VmNicVO.class);
                chain.rollback();
            }
        });
    }
}
