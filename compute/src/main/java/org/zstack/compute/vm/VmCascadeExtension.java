package org.zstack.compute.vm;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.zstack.core.asyncbatch.While;
import org.zstack.core.cascade.AbstractAsyncCascadeExtension;
import org.zstack.core.cascade.CascadeAction;
import org.zstack.core.cascade.CascadeConstant;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.CloudBusCallBack;
import org.zstack.core.cloudbus.CloudBusListCallBack;
import org.zstack.core.componentloader.PluginRegistry;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.db.Q;
import org.zstack.core.db.SimpleQuery;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.core.db.UpdateQuery;
import org.zstack.header.cluster.ClusterInventory;
import org.zstack.header.cluster.ClusterVO;
import org.zstack.header.configuration.InstanceOfferingInventory;
import org.zstack.header.configuration.InstanceOfferingVO;
import org.zstack.header.core.Completion;
import org.zstack.header.core.NoErrorCompletion;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.host.HostInventory;
import org.zstack.header.host.HostVO;
import org.zstack.header.identity.AccountInventory;
import org.zstack.header.identity.AccountResourceRefVO;
import org.zstack.header.identity.AccountResourceRefVO_;
import org.zstack.header.identity.AccountVO;
import org.zstack.header.message.MessageReply;
import org.zstack.header.network.l2.L2NetworkConstant;
import org.zstack.header.network.l2.L2NetworkDetachStruct;
import org.zstack.header.network.l2.L2NetworkVO;
import org.zstack.header.network.l3.*;
import org.zstack.header.vm.*;
import org.zstack.header.vm.cdrom.VmCdRomInventory;
import org.zstack.header.vm.cdrom.VmCdRomVO;
import org.zstack.header.storage.primary.PrimaryStorageConstant;
import org.zstack.header.storage.primary.PrimaryStorageDetachStruct;
import org.zstack.header.storage.primary.PrimaryStorageInventory;
import org.zstack.header.storage.primary.PrimaryStorageVO;
import org.zstack.header.volume.GetVmUuidFromShareableVolumeExtensionPoint;
import org.zstack.header.volume.VolumeType;
import org.zstack.header.zone.ZoneInventory;
import org.zstack.header.zone.ZoneVO;
import org.zstack.utils.CollectionUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.function.Function;
import org.zstack.utils.logging.CLogger;

import javax.persistence.Query;
import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 */
public class VmCascadeExtension extends AbstractAsyncCascadeExtension {
    private static final CLogger logger = Utils.getLogger(VmCascadeExtension.class);

    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    protected VmInstanceExtensionPointEmitter extEmitter;
    @Autowired
    private CloudBus bus;
    @Autowired
    private VmInstanceDeletionPolicyManager deletionPolicyManager;
    @Autowired
    private PluginRegistry pluginRgty;

    private static final String NAME = VmInstanceVO.class.getSimpleName();

    protected static final int OP_NOPE = 0;
    protected static final int OP_STOP = 1;
    protected static final int OP_DELETION = 2;
    private static final int OP_REMOVE_INSTANCE_OFFERING = 3;
    protected static final int OP_DETACH_NIC = 4;

    protected int toDeletionOpCode(CascadeAction action) {
        if (!CascadeConstant.DELETION_CODES.contains(action.getActionCode())) {
            return OP_NOPE;
        }

        if (PrimaryStorageVO.class.getSimpleName().equals(action.getParentIssuer())) {
            return OP_DELETION;
        }

        if (HostVO.class.getSimpleName().equals(action.getParentIssuer())) {
            if (ZoneVO.class.getSimpleName().equals(action.getRootIssuer())) {
                return OP_DELETION;
            } else {
                return OP_STOP;
            }
        }

        if (L3NetworkVO.class.getSimpleName().equals(action.getParentIssuer())) {
            return OP_DETACH_NIC;
        }

        if (IpRangeVO.class.getSimpleName().equals(action.getParentIssuer())) {
            return OP_DETACH_NIC;
        }

        if (VmInstanceVO.class.getSimpleName().equals(action.getParentIssuer())) {
            return OP_DELETION;
        }

        if (InstanceOfferingVO.class.getSimpleName().equals(action.getParentIssuer())) {
            return OP_REMOVE_INSTANCE_OFFERING;
        }

        if (AccountVO.class.getSimpleName().equals(action.getParentIssuer())) {
            return OP_DELETION;
        }

        return OP_NOPE;
    }

    @Override
    public void asyncCascade(CascadeAction action, Completion completion) {
        if (action.isActionCode(CascadeConstant.DELETION_CHECK_CODE)) {
            handleDeletionCheck(action, completion);
        } else if (action.isActionCode(CascadeConstant.DELETION_DELETE_CODE, CascadeConstant.DELETION_FORCE_DELETE_CODE)) {
            handleDeletion(action, completion);
        } else if (action.isActionCode(CascadeConstant.DELETION_CLEANUP_CODE)) {
            handleDeletionCleanup(action, completion);
        } else if (action.isActionCode(PrimaryStorageConstant.PRIMARY_STORAGE_DETACH_CODE)) {
            handlePrimaryStorageDetach(action, completion);
        } else if (action.isActionCode(L2NetworkConstant.DETACH_L2NETWORK_CODE)) {
            handleL2NetworkDetach(action, completion);
        } else {
            completion.success();
        }
    }

    class VmNicDetachResult {
        List<DetachNicFromVmMsg> dmsgs;
        List<DetachIpAddressFromVmNicMsg> nicMsgs;
    }

    @Transactional(readOnly = true)
    private VmNicDetachResult getVmNicDetachMsgs(List<L2NetworkDetachStruct> structs) {
        List<DetachNicFromVmMsg> dmsgs  = new ArrayList<>();
        List<DetachIpAddressFromVmNicMsg> nicMsgs  = new ArrayList<>();

        for (L2NetworkDetachStruct s : structs) {
            String sql = "select vm.uuid, nic.uuid, ip.uuid from VmInstanceVO vm, VmNicVO nic, L3NetworkVO l3, UsedIpVO ip"
                    + " where vm.clusterUuid = :clusterUuid"
                    + " and l3.l2NetworkUuid = :l2NetworkUuid"
                    + " and nic.uuid = ip.vmNicUuid and ip.l3NetworkUuid = l3.uuid "
                    + " and nic.vmInstanceUuid = vm.uuid";
            TypedQuery<Tuple> q = dbf.getEntityManager().createQuery(sql, Tuple.class);
            q.setParameter("clusterUuid", s.getClusterUuid());
            q.setParameter("l2NetworkUuid", s.getL2NetworkUuid());
            List<Tuple> result = q.getResultList();
            Map<String, List<String>> nicIpMap = new HashMap<>();
            for (Tuple t : result) {
                String nicUuid = t.get(1, String.class);
                String ipUuid = t.get(2, String.class);
                List<String> l3Uuids = nicIpMap.get(nicUuid);
                if (l3Uuids == null) {
                    l3Uuids = new ArrayList<>();
                    nicIpMap.put(nicUuid, l3Uuids);
                }
                l3Uuids.add(ipUuid);
            }

            for (Map.Entry<String, List<String>> entry : nicIpMap.entrySet()) {
                String nicUuid = entry.getKey();
                List<String> ipUuids = entry.getValue();
                VmNicVO nicVO = dbf.findByUuid(nicUuid, VmNicVO.class);
                if (VmNicHelper.nicIncludeAllIps(VmNicInventory.valueOf(nicVO), ipUuids)) {
                    DetachNicFromVmMsg msg = new DetachNicFromVmMsg();
                    msg.setVmInstanceUuid(nicVO.getVmInstanceUuid());
                    msg.setVmNicUuid(nicUuid);
                    bus.makeTargetServiceIdByResourceUuid(msg, VmInstanceConstant.SERVICE_ID, msg.getVmInstanceUuid());
                    dmsgs.add(msg);
                } else {
                    for (String ipUuid : ipUuids) {
                        DetachIpAddressFromVmNicMsg nicMsg = new DetachIpAddressFromVmNicMsg();
                        nicMsg.setVmNicUuid(nicUuid);
                        nicMsg.setId(ipUuid);
                        bus.makeTargetServiceIdByResourceUuid(nicMsg, VmInstanceConstant.SERVICE_ID, nicVO.getVmInstanceUuid());
                        nicMsgs.add(nicMsg);
                    }
                }
            }
        }

        VmNicDetachResult result = new VmNicDetachResult();
        result.dmsgs = dmsgs;
        result.nicMsgs = nicMsgs;
        return result;
    }

    private void detachVmNicCascade(List<DetachNicFromVmMsg> msgs, boolean deleteFromDb, final Completion completion) {
        if (msgs.isEmpty()) {
            completion.success();
            return;
        }

        List<String> vmNicUuids = new ArrayList<String>();
        new While<>(msgs).all((msg, compl) -> bus.send(msg, new CloudBusCallBack(compl) {
            @Override
            public void run(MessageReply reply) {
                if (!reply.isSuccess()) {
                    logger.warn(String.format("failed to detach nic[uuid:%s] from the vm[uuid:%s], %s",
                            msg.getVmNicUuid(), msg.getVmInstanceUuid(), reply.getError()));
                } else {
                    vmNicUuids.add(msg.getVmNicUuid());
                }
                compl.done();
            }
        })).run(new NoErrorCompletion(completion) {
            @Override
            public void done() {
                if (!vmNicUuids.isEmpty() && deleteFromDb) {
                    UpdateQuery q = UpdateQuery.New(AccountResourceRefVO.class)
                            .condAnd(AccountResourceRefVO_.resourceUuid, Op.IN, vmNicUuids)
                            .condAnd(AccountResourceRefVO_.resourceType, Op.EQ, VmNicVO.class.getSimpleName());
                    q.delete();
                }

                completion.success();
            }
        });
    }

    private void detachIpFromVmNicCascade(List<DetachIpAddressFromVmNicMsg> msgs, final Completion completion) {
        if (msgs.isEmpty()) {
            completion.success();
            return;
        }

        new While<>(msgs).all((msg, compl) -> bus.send(msg, new CloudBusCallBack(compl) {
            @Override
            public void run(MessageReply reply) {
                if (!reply.isSuccess()) {
                    logger.warn(String.format("failed to detach ip[uuid:%s] from vmNic [uuid:%s], %s." +
                            " However, detaching will go on", msg.getUsedIpUuid(), msg.getVmNicUuid(), reply.getError()));
                }
                compl.done();
            }
        })).run(new NoErrorCompletion(completion) {
            @Override
            public void done() {
                completion.success();
            }
        });
    }

    private void handleL2NetworkDetach(CascadeAction action, final Completion completion) {
        List<L2NetworkDetachStruct> structs = action.getParentIssuerContext();
        final VmNicDetachResult result = getVmNicDetachMsgs(structs);

        detachIpFromVmNicCascade(result.nicMsgs, new Completion(completion) {
            @Override
            public void success() {
                detachVmNicCascade(result.dmsgs, false, completion);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Transactional(readOnly = true)
    private List<String> getVmUuidForPrimaryStorageDetached(List<PrimaryStorageDetachStruct> structs) {
        List<String> vmUuids = new ArrayList<>();
        for (PrimaryStorageDetachStruct s : structs) {
            String sql = "select distinct vm.uuid" +
                    " from VmInstanceVO vm, VolumeVO vol" +
                    " where vm.type = :vmType" +
                    " and vm.state in (:vmStates)" +
                    " and vm.clusterUuid = :clusterUuid" +
                    " and vm.uuid = vol.vmInstanceUuid" +
                    " and vol.primaryStorageUuid = :psUuid";
            TypedQuery<String> q = dbf.getEntityManager().createQuery(sql, String.class);
            q.setParameter("vmType", VmInstanceConstant.USER_VM_TYPE);
            q.setParameter("vmStates", Arrays.asList(
                    VmInstanceState.Unknown,
                    VmInstanceState.Running,
                    VmInstanceState.Pausing,
                    VmInstanceState.Paused));
            q.setParameter("clusterUuid", s.getClusterUuid());
            q.setParameter("psUuid", s.getPrimaryStorageUuid());
            vmUuids.addAll(q.getResultList());
            //Get the vmuuid attached by the ShareableVolume
            for (GetVmUuidFromShareableVolumeExtensionPoint exp : pluginRgty.getExtensionList(GetVmUuidFromShareableVolumeExtensionPoint.class)) {
                vmUuids.addAll(exp.getVmUuidFromShareableVolumeByPrimaryStorage(s));
            }
        }

        return vmUuids;
    }

    private void handlePrimaryStorageDetach(CascadeAction action, final Completion completion) {
        List<PrimaryStorageDetachStruct> structs = action.getParentIssuerContext();
        final List<String> vmUuids = getVmUuidForPrimaryStorageDetached(structs);
        if (vmUuids.isEmpty()) {
            completion.success();
            return;
        }

        List<StopVmInstanceMsg> msgs = CollectionUtils.transformToList(vmUuids, (Function<StopVmInstanceMsg, String>) arg -> {
            StopVmInstanceMsg msg = new StopVmInstanceMsg();
            msg.setVmInstanceUuid(arg);
            bus.makeTargetServiceIdByResourceUuid(msg, VmInstanceConstant.SERVICE_ID, arg);
            return msg;
        });

        bus.send(msgs, 20, new CloudBusListCallBack(completion) {
            @Override
            public void run(List<MessageReply> replies) {
                for (MessageReply r : replies) {
                    if (!r.isSuccess()) {
                        String vmUuid = vmUuids.get(replies.indexOf(r));
                        logger.warn(String.format("failed to stop vm[uuid:%s] for primary storage detached, %s." +
                                " However, detaching will go on", vmUuid, r.getError()));
                    }
                }

                completion.success();
            }
        });
    }

    private void handleDeletionCleanup(CascadeAction action, Completion completion) {
        int op = toDeletionOpCode(action);
        if (op == OP_NOPE) {
            completion.success();
            return;
        }

        final List<VmDeletionStruct> vminvs = vmFromDeleteAction(action);
        if (vminvs == null || vminvs.isEmpty()) {
            completion.success();
            return;
        }

        for (VmDeletionStruct struct : vminvs) {
            dbf.eoCleanup(VmInstanceVO.class, struct.getInventory().getUuid());
        }

        completion.success();
    }

    protected void handleDeletion(final CascadeAction action, final Completion completion) {
        int op = toDeletionOpCode(action);
        if (op == OP_NOPE) {
            completion.success();
            return;
        }

        if (op == OP_REMOVE_INSTANCE_OFFERING) {
            if (VmGlobalConfig.UPDATE_INSTANCE_OFFERING_TO_NULL_WHEN_DELETING.value(Boolean.class)) {
                new Runnable() {
                    @Override
                    @Transactional
                    public void run() {
                        List<InstanceOfferingInventory> offerings = action.getParentIssuerContext();
                        List<String> offeringUuids = CollectionUtils.transformToList(offerings,
                                new Function<String, InstanceOfferingInventory>() {
                                    @Override
                                    public String call(InstanceOfferingInventory arg) {
                                        return arg.getUuid();
                                    }
                                });

                        String sql = "update VmInstanceVO vm" +
                                " set vm.instanceOfferingUuid = null" +
                                " where vm.instanceOfferingUuid in (:offeringUuids)";
                        Query q = dbf.getEntityManager().createQuery(sql);
                        q.setParameter("offeringUuids", offeringUuids);
                        q.executeUpdate();
                    }
                }.run();
            }

            completion.success();
            return;
        }

        final List<VmDeletionStruct> vminvs = vmFromDeleteAction(action);
        if (vminvs == null || vminvs.isEmpty()) {
            completion.success();
            return;
        }

        if (op == OP_STOP) {
            List<StopVmInstanceMsg> msgs = new ArrayList<>();
            Set<String> vmStateCanStop = AbstractVmInstance.getAllowedStatesForOperation(StopVmInstanceMsg.class);

            boolean ignoreResourceReleaseFailure = Arrays.asList(
                    HostVO.class.getSimpleName(), PrimaryStorageVO.class.getSimpleName()
            ).contains(action.getParentIssuer());
            for (VmDeletionStruct inv : vminvs) {
                if (!vmStateCanStop.contains(inv.getInventory().getState())) {
                    continue;
                }
                StopVmInstanceMsg msg = new StopVmInstanceMsg();
                msg.setVmInstanceUuid(inv.getInventory().getUuid());
                msg.setGcOnFailure(true);
                msg.setIgnoreResourceReleaseFailure(ignoreResourceReleaseFailure);
                bus.makeTargetServiceIdByResourceUuid(msg, VmInstanceConstant.SERVICE_ID, inv.getInventory().getUuid());
                msgs.add(msg);
            }

            if (msgs.isEmpty()) {
                completion.success();
                return;
            }

            bus.send(msgs, 20, new CloudBusListCallBack(completion) {
                @Override
                public void run(List<MessageReply> replies) {
                    if (!action.isActionCode(CascadeConstant.DELETION_FORCE_DELETE_CODE)) {
                        for (MessageReply r : replies) {
                            if (!r.isSuccess()) {
                                completion.fail(r.getError());
                                return;
                            }
                        }
                    }

                    completion.success();
                }
            });
        } else if (op == OP_DELETION) {
            boolean ignoreResourceReleaseFailure = Arrays.asList(
                    HostVO.class.getSimpleName(), PrimaryStorageVO.class.getSimpleName()
            ).contains(action.getParentIssuer());


            int parallelism = 10;
            new While<>(vminvs).step((inv, noErrorCompletion) -> {
                VmInstanceDeletionMsg msg = new VmInstanceDeletionMsg();
                if (PrimaryStorageVO.class.getSimpleName().equals(action.getParentIssuer()) ||
                        ZoneVO.class.getSimpleName().equals(action.getRootIssuer())) {
                    msg.setDeletionPolicy(VmInstanceDeletionPolicyManager.VmInstanceDeletionPolicy.KeepVolume.toString());
                }
                if (VmInstanceVO.class.getSimpleName().equals(action.getParentIssuer())) {
                    if (inv.getDeletionPolicy() != null) {
                        msg.setDeletionPolicy(inv.getDeletionPolicy().toString());
                    }
                }

                if (VmInstanceDeletionPolicyManager.VmInstanceDeletionPolicy.Direct.toString().equals(msg.getDeletionPolicy())) {
                    msg.setIgnoreResourceReleaseFailure(true);
                } else {
                    msg.setIgnoreResourceReleaseFailure(ignoreResourceReleaseFailure);
                }

                msg.setForceDelete(action.isActionCode(CascadeConstant.DELETION_FORCE_DELETE_CODE));
                msg.setVmInstanceUuid(inv.getInventory().getUuid());
                bus.makeTargetServiceIdByResourceUuid(msg, VmInstanceConstant.SERVICE_ID, inv.getInventory().getUuid());
                bus.send(msg, new CloudBusCallBack(noErrorCompletion) {
                    @Override
                    public void run(MessageReply reply) {
                        if (!action.isActionCode(CascadeConstant.DELETION_FORCE_DELETE_CODE)) {
                            if (!reply.isSuccess()) {
                                // TODO
                                logger.warn(reply.getError().toString());
                            }
                        }

                        noErrorCompletion.done();
                    }
                });
            }, parallelism).run(new NoErrorCompletion(completion) {
                @Override
                public void done() {
                    if (ZoneVO.class.getSimpleName().equals(action.getRootIssuer())) {
                        dbf.removeByPrimaryKeys(vminvs.stream().map(vm -> vm.getInventory().getVmNics())
                                        .flatMap(List::stream).map(VmNicInventory::getUuid)
                                        .collect(Collectors.toList()),
                                VmNicVO.class);
                        List<String> cdRomUuids = vminvs.stream().map(vm -> vm.getInventory().getVmCdRoms())
                                .flatMap(List::stream).map(VmCdRomInventory::getUuid)
                                .collect(Collectors.toList());
                        dbf.removeByPrimaryKeys(cdRomUuids, VmCdRomVO.class);
                        dbf.removeByPrimaryKeys(vminvs.stream().map(p -> p.getInventory().getUuid())
                                        .collect(Collectors.toList()),
                                VmInstanceVO.class);
                    }

                    completion.success();
                }
            });
        } else if (op == OP_DETACH_NIC) {
            final List<DetachNicFromVmMsg> msgs = new ArrayList<>();
            final List<DetachIpAddressFromVmNicMsg> nicMsgs = new ArrayList<>();
            if (L3NetworkVO.class.getSimpleName().equals(action.getParentIssuer())) {
                List<L3NetworkInventory> l3s = action.getParentIssuerContext();
                List<String> l3Uuids = l3s.stream().map(l3inv -> l3inv.getUuid()).collect(Collectors.toList());
                for (VmDeletionStruct vm : vminvs) {
                    for (L3NetworkInventory l3 : l3s) {
                        VmNicInventory nic = vm.getInventory().findNic(l3.getUuid());
                        if (nic == null) {
                            continue;
                        }

                        if (VmNicHelper.includeAllNicL3s(nic, l3Uuids)) {
                            DetachNicFromVmMsg msg = new DetachNicFromVmMsg();
                            msg.setVmInstanceUuid(vm.getInventory().getUuid());
                            msg.setVmNicUuid(nic.getUuid());
                            bus.makeTargetServiceIdByResourceUuid(msg, VmInstanceConstant.SERVICE_ID, vm.getInventory().getUuid());
                            msgs.add(msg);
                        } else {
                            for (UsedIpInventory ip : nic.getUsedIps()) {
                                if (l3Uuids.contains(ip.getL3NetworkUuid())) {
                                    DetachIpAddressFromVmNicMsg nicMsg = new DetachIpAddressFromVmNicMsg();
                                    nicMsg.setVmNicUuid(nic.getUuid());
                                    nicMsg.setUsedIpUuid(ip.getUuid());
                                    bus.makeTargetServiceIdByResourceUuid(nicMsg, VmInstanceConstant.SERVICE_ID, nic.getVmInstanceUuid());
                                    nicMsgs.add(nicMsg);
                                }
                            }
                        }
                    }
                }
            } else if (IpRangeVO.class.getSimpleName().equals(action.getParentIssuer())) {
                List<IpRangeInventory> iprs = action.getParentIssuerContext();
                List<String> uuids = iprs.stream().map(ipr -> ipr.getUuid()).collect(Collectors.toList());
                List<String> iprUuids = Q.New(NormalIpRangeVO.class).in(NormalIpRangeVO_.uuid, uuids).select(NormalIpRangeVO_.uuid).listValues();
                for (VmDeletionStruct vm : vminvs) {
                    for (VmNicInventory nic : vm.getInventory().getVmNics()) {
                        List<String> rangeUuids = nic.getUsedIps().stream().map(n -> n.getIpRangeUuid()).collect(Collectors.toList());
                        rangeUuids.removeAll(iprUuids);
                        if(rangeUuids.isEmpty()) {
                            DetachNicFromVmMsg msg = new DetachNicFromVmMsg();
                            msg.setVmInstanceUuid(vm.getInventory().getUuid());
                            msg.setVmNicUuid(nic.getUuid());
                            bus.makeTargetServiceIdByResourceUuid(msg, VmInstanceConstant.SERVICE_ID, vm.getInventory().getUuid());
                            msgs.add(msg);
                        } else {
                            for (UsedIpInventory ip : nic.getUsedIps()) {
                                if (iprUuids.contains(ip.getIpRangeUuid())) {
                                    DetachIpAddressFromVmNicMsg nicMsg = new DetachIpAddressFromVmNicMsg();
                                    nicMsg.setVmNicUuid(nic.getUuid());
                                    nicMsg.setUsedIpUuid(ip.getUuid());
                                    bus.makeTargetServiceIdByResourceUuid(nicMsg, VmInstanceConstant.SERVICE_ID, nic.getVmInstanceUuid());
                                    nicMsgs.add(nicMsg);
                                }
                            }
                        }
                    }
                }
            }

            boolean deleteFromDb = action.getParentIssuer().equals(L3NetworkVO.class.getSimpleName());
            detachIpFromVmNicCascade(nicMsgs, new Completion(completion) {
                @Override
                public void success() {
                    detachVmNicCascade(msgs, false, completion);
                }

                @Override
                public void fail(ErrorCode errorCode) {
                    completion.fail(errorCode);
                }
            });
        }
    }

    private void handleDeletionCheck(CascadeAction action, Completion completion) {
        int op = toDeletionOpCode(action);
        if (op == OP_NOPE || op == OP_STOP) {
            completion.success();
            return;
        }

        List<VmDeletionStruct> vminvs = vmFromDeleteAction(action);
        if (vminvs == null) {
            completion.success();
            return;
        }

        for (VmDeletionStruct inv : vminvs) {
            ErrorCode err = extEmitter.preDestroyVm(inv.getInventory());
            if (err != null) {
                completion.fail(err);
                return;
            }
        }

        completion.success();
    }

    @Override
    public List<String> getEdgeNames() {
        return Arrays.asList(
                HostVO.class.getSimpleName(),
                L3NetworkVO.class.getSimpleName(),
                IpRangeVO.class.getSimpleName(),
                PrimaryStorageVO.class.getSimpleName(),
                L2NetworkVO.class.getSimpleName(),
                InstanceOfferingVO.class.getSimpleName(),
                AccountVO.class.getSimpleName());
    }

    @Override
    public String getCascadeResourceName() {
        return NAME;
    }

    private List<VmDeletionStruct> toVmDeletionStruct(Collection<VmInstanceVO> vos) {
        List<VmDeletionStruct> structs = new ArrayList<>();
        for (VmInstanceVO vo : vos) {
            VmDeletionStruct s = new VmDeletionStruct();
            s.setInventory(VmInstanceInventory.valueOf(vo));
            s.setDeletionPolicy(deletionPolicyManager.getDeletionPolicy(vo.getUuid()));
            structs.add(s);
        }
        return structs;
    }

    protected List<VmDeletionStruct> vmFromDeleteAction(CascadeAction action) {
        List<VmDeletionStruct> ret = null;
        if (HostVO.class.getSimpleName().equals(action.getParentIssuer())) {
            List<HostInventory> hosts = action.getParentIssuerContext();
            List<String> huuids = CollectionUtils.transformToList(hosts, new Function<String, HostInventory>() {
                @Override
                public String call(HostInventory arg) {
                    return arg.getUuid();
                }
            });

            Map<String, VmInstanceVO> vmvos = new HashMap<>();
            SimpleQuery<VmInstanceVO> q = dbf.createQuery(VmInstanceVO.class);
            q.add(VmInstanceVO_.hostUuid, SimpleQuery.Op.IN, huuids);
            q.add(VmInstanceVO_.type, Op.EQ, VmInstanceConstant.USER_VM_TYPE);
            List<VmInstanceVO> lst = q.list();
            for (VmInstanceVO vo : lst) {
                vmvos.put(vo.getUuid(), vo);
            }

            if (ClusterVO.class.getSimpleName().equals(action.getRootIssuer())) {
                List<ClusterInventory> clusters = action.getRootIssuerContext();
                List<String> clusterUuids = CollectionUtils.transformToList(clusters, new Function<String, ClusterInventory>() {
                    @Override
                    public String call(ClusterInventory arg) {
                        return arg.getUuid();
                    }
                });
                q = dbf.createQuery(VmInstanceVO.class);
                q.add(VmInstanceVO_.clusterUuid, Op.IN, clusterUuids);
                q.add(VmInstanceVO_.type, Op.EQ, VmInstanceConstant.USER_VM_TYPE);
                lst = q.list();
                for (VmInstanceVO vo : lst) {
                    vmvos.put(vo.getUuid(), vo);
                }
            } else if (ZoneVO.class.getSimpleName().equals(action.getRootIssuer())) {
                List<ZoneInventory> zones = action.getRootIssuerContext();
                List<String> zoneUuids = CollectionUtils.transformToList(zones, new Function<String, ZoneInventory>() {
                    @Override
                    public String call(ZoneInventory arg) {
                        return arg.getUuid();
                    }
                });
                q = dbf.createQuery(VmInstanceVO.class);
                q.add(VmInstanceVO_.zoneUuid, Op.IN, zoneUuids);
                q.add(VmInstanceVO_.type, Op.EQ, VmInstanceConstant.USER_VM_TYPE);
                lst = q.list();
                for (VmInstanceVO vo : lst) {
                    vmvos.put(vo.getUuid(), vo);
                }
            }

            if (!vmvos.isEmpty()) {
                ret = toVmDeletionStruct(vmvos.values());
            }
        } else if (NAME.equals(action.getParentIssuer())) {
            return action.getParentIssuerContext();
        } else if (PrimaryStorageVO.class.getSimpleName().equals(action.getParentIssuer())) {
            final List<String> pruuids = CollectionUtils.transformToList(
                    (List<PrimaryStorageInventory>) action.getParentIssuerContext(),
                    new Function<String, PrimaryStorageInventory>() {
                        @Override
                        public String call(PrimaryStorageInventory arg) {
                            return arg.getUuid();
                        }
                    });


            List<VmInstanceVO> vmvos = new Callable<List<VmInstanceVO>>() {
                @Override
                @Transactional(readOnly = true)
                public List<VmInstanceVO> call() {
                    String sql = "select vm from VmInstanceVO vm, VolumeVO vol, PrimaryStorageVO pr" +
                            " where vm.type = :vmType" +
                            " and vm.uuid = vol.vmInstanceUuid" +
                            " and vol.primaryStorageUuid = pr.uuid" +
                            " and vol.type = :volType" +
                            " and pr.uuid in (:uuids)" +
                            " group by vm.uuid";
                    TypedQuery<VmInstanceVO> q = dbf.getEntityManager().createQuery(sql, VmInstanceVO.class);
                    q.setParameter("vmType", VmInstanceConstant.USER_VM_TYPE);
                    q.setParameter("uuids", pruuids);
                    q.setParameter("volType", VolumeType.Root);
                    return q.getResultList();
                }
            }.call();

            if (!vmvos.isEmpty()) {
                ret = toVmDeletionStruct(vmvos);
            }
        } else if (L3NetworkVO.class.getSimpleName().equals(action.getParentIssuer())) {
            final List<String> l3uuids = CollectionUtils.transformToList(
                    (List<L3NetworkInventory>) action.getParentIssuerContext(),
                    new Function<String, L3NetworkInventory>() {
                        @Override
                        public String call(L3NetworkInventory arg) {
                            return arg.getUuid();
                        }
                    });

            List<VmInstanceVO> vmvos = new Callable<List<VmInstanceVO>>() {
                @Override
                @Transactional(readOnly = true)
                public List<VmInstanceVO> call() {
                    String sql = "select vm from VmInstanceVO vm, L3NetworkVO l3, VmNicVO nic, UsedIpVO ip" +
                            " where vm.type = :vmType" +
                            " and vm.uuid = nic.vmInstanceUuid" +
                            " and vm.state in (:vmStates)" +
                            " and nic.uuid = ip.vmNicUuid and ip.l3NetworkUuid = l3.uuid" +
                            " and l3.uuid in (:uuids)" +
                            " group by vm.uuid";
                    TypedQuery<VmInstanceVO> q = dbf.getEntityManager().createQuery(sql, VmInstanceVO.class);
                    q.setParameter("vmType", VmInstanceConstant.USER_VM_TYPE);
                    q.setParameter("vmStates", Arrays.asList(
                            VmInstanceState.Stopped, VmInstanceState.Paused,
                            VmInstanceState.Running, VmInstanceState.Destroyed));
                    q.setParameter("uuids", l3uuids);
                    return q.getResultList();
                }
            }.call();

            if (!vmvos.isEmpty()) {
                ret = toVmDeletionStruct(vmvos);
            }
        } else if (IpRangeVO.class.getSimpleName().equals(action.getParentIssuer())) {
            final List<String> ipruuids = CollectionUtils.transformToList(
                    (List<IpRangeInventory>) action.getParentIssuerContext(),
                    new Function<String, IpRangeInventory>() {
                        @Override
                        public String call(IpRangeInventory arg) {
                            return Q.New(NormalIpRangeVO.class).select(NormalIpRangeVO_.uuid).eq(NormalIpRangeVO_.uuid, arg.getUuid()).findValue();
                        }
                    });

            if (ipruuids.isEmpty()) {
                return new ArrayList<>();
            }

            List<VmInstanceVO> vmvos = new Callable<List<VmInstanceVO>>() {
                @Override
                @Transactional(readOnly = true)
                public List<VmInstanceVO> call() {
                    String sql = "select vm from VmInstanceVO vm, VmNicVO nic, UsedIpVO ip, NormalIpRangeVO ipr" +
                            " where vm.type = :vmType" +
                            " and vm.uuid = nic.vmInstanceUuid" +
                            " and vm.state in (:vmStates)" +
                            " and nic.uuid = ip.vmNicUuid" +
                            " and ip.ipRangeUuid = ipr.uuid" +
                            " and ipr.uuid in (:uuids)" +
                            " group by vm.uuid";
                    TypedQuery<VmInstanceVO> q = dbf.getEntityManager().createQuery(sql, VmInstanceVO.class);
                    q.setParameter("vmType", VmInstanceConstant.USER_VM_TYPE);
                    q.setParameter("vmStates", Arrays.asList(
                            VmInstanceState.Stopped, VmInstanceState.Paused,
                            VmInstanceState.Running, VmInstanceState.Destroyed));
                    q.setParameter("uuids", ipruuids);
                    return q.getResultList();
                }
            }.call();

            if (!vmvos.isEmpty()) {
                ret = toVmDeletionStruct(vmvos);
            }
        } else if (AccountVO.class.getSimpleName().equals(action.getParentIssuer())) {
            final List<String> auuids = CollectionUtils.transformToList(
                    (List<AccountInventory>) action.getParentIssuerContext(),
                    new Function<String, AccountInventory>() {
                        @Override
                        public String call(AccountInventory arg) {
                            return arg.getUuid();
                        }
                    });

            List<VmInstanceVO> vmvos = new Callable<List<VmInstanceVO>>() {
                @Override
                @Transactional(readOnly = true)
                public List<VmInstanceVO> call() {
                    String sql = "select d from VmInstanceVO d, AccountResourceRefVO r" +
                            " where d.uuid = r.resourceUuid" +
                            " and r.resourceType = :rtype" +
                            " and r.accountUuid in (:auuids)" +
                            " group by d.uuid";
                    TypedQuery<VmInstanceVO> q = dbf.getEntityManager().createQuery(sql, VmInstanceVO.class);
                    q.setParameter("rtype", VmInstanceVO.class.getSimpleName());
                    q.setParameter("auuids", auuids);
                    return q.getResultList();
                }
            }.call();

            if (!vmvos.isEmpty()) {
                ret = toVmDeletionStruct(vmvos);
            }
        }

        return ret;
    }

    @Override
    public CascadeAction createActionForChildResource(CascadeAction action) {
        if (CascadeConstant.DELETION_CODES.contains(action.getActionCode())) {
            int op = toDeletionOpCode(action);
            if (op == OP_NOPE || op == OP_STOP || op == OP_REMOVE_INSTANCE_OFFERING || op == OP_DETACH_NIC) {
                return null;
            }

            List<VmDeletionStruct> vms = vmFromDeleteAction(action);
            if (vms == null) {
                return null;
            }

            return action.copy().setParentIssuer(NAME).setParentIssuerContext(vms);
        }

        return null;
    }
}
