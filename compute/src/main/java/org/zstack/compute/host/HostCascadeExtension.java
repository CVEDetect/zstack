package org.zstack.compute.host;

import org.springframework.beans.factory.annotation.Autowired;
import org.zstack.core.cascade.*;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.CloudBusListCallBack;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.db.Q;
import org.zstack.core.db.SimpleQuery;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.header.apimediator.ApiMessageInterceptionException;
import org.zstack.header.cluster.ClusterInventory;
import org.zstack.header.cluster.ClusterVO;
import org.zstack.header.core.Completion;
import org.zstack.header.host.*;
import org.zstack.header.message.MessageReply;
import org.zstack.header.vm.VmInstanceVO;
import org.zstack.header.vm.VmInstanceVO_;
import org.zstack.utils.CollectionUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.function.Function;
import org.zstack.utils.logging.CLogger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.zstack.core.Platform.argerr;
import static org.zstack.core.Platform.inerr;

/**
 */
public class HostCascadeExtension extends AbstractAsyncCascadeExtension {
    private static final CLogger logger = Utils.getLogger(HostCascadeExtension.class);

    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    private HostExtensionPointEmitter extpEmitter;
    @Autowired
    private CloudBus bus;
    @Autowired
    private ErrorFacade errf;

    private static final String NAME = HostVO.class.getSimpleName();

    @Override
    public void asyncCascade(CascadeAction action, Completion completion) {
        if (action.isActionCode(CascadeConstant.DELETION_CHECK_CODE)) {
            handleDeletionCheck(action, completion);
        } else if (action.isActionCode(CascadeConstant.DELETION_DELETE_CODE, CascadeConstant.DELETION_FORCE_DELETE_CODE)) {
            handleDeletion(action, completion);
        } else if (action.isActionCode(CascadeConstant.DELETION_CLEANUP_CODE)) {
            handleDeletionCleanup(action, completion);
        } else {
            completion.success();
        }

    }

    private void handleDeletionCleanup(CascadeAction action, Completion completion) {
        try {
            final List<HostInventory> hinvs = hostFromAction(action);
            if (hinvs != null) {
                hinvs.forEach(h -> dbf.eoCleanup(HostVO.class, h.getUuid()));
            } else {
                dbf.eoCleanup(HostVO.class);
            }
        } catch (NullPointerException e) {
            logger.warn(e.getLocalizedMessage());
            dbf.eoCleanup(HostVO.class);
        } finally {
            completion.success();
        }
    }

    private void handleDeletion(final CascadeAction action, final Completion completion) {
        final List<HostInventory> hinvs = hostFromAction(action);
        if (hinvs == null) {
            completion.success();
            return;
        }

        List<HostDeletionMsg> msgs = new ArrayList<HostDeletionMsg>();
        for (HostInventory hinv : hinvs) {
            HostDeletionMsg msg = new HostDeletionMsg();
            msg.setForceDelete(action.isActionCode(CascadeConstant.DELETION_FORCE_DELETE_CODE));
            msg.setHostUuid(hinv.getUuid());
            bus.makeTargetServiceIdByResourceUuid(msg, HostConstant.SERVICE_ID, hinv.getUuid());
            msgs.add(msg);
        }

        bus.send(msgs, 10, new CloudBusListCallBack(completion) {
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

                List<String> uuids = new ArrayList<String>();
                for (MessageReply r : replies) {
                    HostInventory hinv = hinvs.get(replies.indexOf(r));
                    uuids.add(hinv.getUuid());
                    logger.debug(String.format("delete host[uuid:%s, name:%s]", hinv.getUuid(), hinv.getName()));
                }

                dbf.removeByPrimaryKeys(uuids, HostVO.class);
                completion.success();
            }
        });
    }

    private void handleDeletionCheck(CascadeAction action, Completion completion) {
        List<HostInventory> hinvs = hostFromAction(action);
        if (hinvs == null) {
            completion.success();
            return;
        }

        try {
            for (HostInventory hinv : hinvs) {
                if (!HostGlobalConfig.DELETION_POLICY.value().equals("Force") && Q.New(VmInstanceVO.class).eq(VmInstanceVO_.hostUuid, hinv.getUuid()).isExists()) {
                    throw new HostException(String.format("there are still vm on host[uuid:%s], can not delete it", hinv.getUuid()));
                }

                extpEmitter.preDelete(hinv);
            }

            completion.success();
        } catch (HostException e) {
            completion.fail(inerr(e.getMessage()));
        }
    }

    @Override
    public List<String> getEdgeNames() {
        return Arrays.asList(ClusterVO.class.getSimpleName());
    }

    @Override
    public String getCascadeResourceName() {
        return NAME;
    }

    private List<HostInventory> hostFromAction(CascadeAction action) {
        List<HostInventory> ret = null;
        if (ClusterVO.class.getSimpleName().equals(action.getParentIssuer())) {
            List<ClusterInventory> clusters = action.getParentIssuerContext();
            List<String> cuuids = CollectionUtils.transformToList(clusters, new Function<String, ClusterInventory>() {
                @Override
                public String call(ClusterInventory arg) {
                    return arg.getUuid();
                }
            });

            SimpleQuery<HostVO> q = dbf.createQuery(HostVO.class);
            q.add(HostVO_.clusterUuid, SimpleQuery.Op.IN, cuuids);
            List<HostVO> hvos = q.list();
            if (!hvos.isEmpty()) {
                ret = HostInventory.valueOf(hvos);
            }
        } else if (NAME.equals(action.getParentIssuer())) {
            ret = action.getParentIssuerContext();
        }

        return ret;
    }

    @Override
    public CascadeAction createActionForChildResource(CascadeAction action) {
        if (CascadeConstant.DELETION_CODES.contains(action.getActionCode())) {
            List<HostInventory> invs = hostFromAction(action);
            if (invs != null) {
                return action.copy().setParentIssuer(NAME).setParentIssuerContext(invs);
            }
        }

        return null;
    }
}
