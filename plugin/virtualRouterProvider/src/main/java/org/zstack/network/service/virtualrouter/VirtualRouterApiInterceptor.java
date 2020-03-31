package org.zstack.network.service.virtualrouter;

import org.apache.commons.net.util.SubnetUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.zstack.core.CoreGlobalProperty;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.db.Q;
import org.zstack.core.db.SimpleQuery;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.header.apimediator.ApiMessageInterceptionException;
import org.zstack.header.apimediator.ApiMessageInterceptor;
import org.zstack.header.identity.IdentityErrors;
import org.zstack.header.image.ImageConstant;
import org.zstack.header.image.ImageConstant.ImageMediaType;
import org.zstack.header.image.ImageVO;
import org.zstack.header.image.ImageVO_;
import org.zstack.header.message.APIMessage;
import org.zstack.header.network.l3.*;
import org.zstack.header.network.service.NetworkServiceL3NetworkRefVO;
import org.zstack.header.network.service.NetworkServiceL3NetworkRefVO_;
import org.zstack.header.network.service.NetworkServiceType;
import org.zstack.header.query.QueryCondition;
import org.zstack.header.query.QueryOp;
import org.zstack.header.vm.VmNicHelper;
import org.zstack.header.vm.VmNicVO;
import org.zstack.identity.QuotaUtil;
import org.zstack.network.l3.IpRangeHelper;
import org.zstack.utils.ShellResult;
import org.zstack.utils.ShellUtils;
import org.zstack.utils.network.IPv6Constants;
import org.zstack.utils.network.IPv6NetworkUtils;
import org.zstack.utils.network.NetworkUtils;

import javax.persistence.Tuple;
import java.util.List;

import static org.zstack.core.Platform.*;
import static org.zstack.utils.CollectionDSL.list;

/**
 */
public class VirtualRouterApiInterceptor implements ApiMessageInterceptor {
    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    private ErrorFacade errf;

    @Override
    public APIMessage intercept(APIMessage msg) throws ApiMessageInterceptionException {
        if (msg instanceof APIQueryVirtualRouterOfferingMsg) {
            validate((APIQueryVirtualRouterOfferingMsg) msg);
        } else if (msg instanceof APICreateVirtualRouterOfferingMsg) {
            validate((APICreateVirtualRouterOfferingMsg) msg);
        } else if (msg instanceof APIUpdateVirtualRouterOfferingMsg) {
            validate((APIUpdateVirtualRouterOfferingMsg) msg);
        } else if (msg instanceof APIUpdateVirtualRouterMsg) {
            validate((APIUpdateVirtualRouterMsg) msg);
        }

        return msg;
    }

    private void validate(APIUpdateVirtualRouterMsg msg) {
        VirtualRouterVmVO vrVO = dbf.findByUuid(msg.getVmInstanceUuid(), VirtualRouterVmVO.class);

        if (msg.getDefaultRouteL3NetworkUuid().equals(vrVO.getDefaultRouteL3NetworkUuid())) {
            throw new ApiMessageInterceptionException(argerr("l3 uuid[:%s] is same to default network of virtual router [uuid:%s]",
                    msg.getDefaultRouteL3NetworkUuid(), msg.getVmInstanceUuid()));
        }

        VmNicVO target = null;
        for (VmNicVO nic : vrVO.getVmNics()) {
            if (VmNicHelper.getL3Uuids(nic).contains(msg.getDefaultRouteL3NetworkUuid())) {
                target = nic;
                break;
            }
        }

        if (target == null) {
            throw new ApiMessageInterceptionException(argerr("l3 uuid[:%s] is not attached to virtual router [uuid:%s]", msg.getDefaultRouteL3NetworkUuid(), msg.getVmInstanceUuid()));
        }

        if (!VirtualRouterNicMetaData.isPublicNic(target) && !VirtualRouterNicMetaData.isAddinitionalPublicNic(target)) {
            throw new ApiMessageInterceptionException(argerr("l3 uuid[:%s] is not public network, can not be default network", msg.getDefaultRouteL3NetworkUuid()));
        }
    }

    private void validate(APIUpdateVirtualRouterOfferingMsg msg) {
        if (msg.getIsDefault() != null) {
            if (!new QuotaUtil().isAdminAccount(msg.getSession().getAccountUuid())) {
                throw new ApiMessageInterceptionException(err(IdentityErrors.PERMISSION_DENIED,
                        "cannot change the default field of a virtual router offering; only admin can do the operation"
                ));
            }
        }

        if (msg.getImageUuid() != null) {
            SimpleQuery<ImageVO> q = dbf.createQuery(ImageVO.class);
            q.select(ImageVO_.mediaType, ImageVO_.format);
            q.add(ImageVO_.uuid, Op.EQ, msg.getImageUuid());
            Tuple t = q.findTuple();
            ImageMediaType type = t.get(0, ImageMediaType.class);
            String format = t.get(1, String.class);

            if (type != ImageMediaType.RootVolumeTemplate) {
                throw new ApiMessageInterceptionException(argerr("image[uuid:%s]'s mediaType is %s, the mediaType of a virtual router image must be %s",
                                msg.getImageUuid(), type, ImageMediaType.RootVolumeTemplate));
            }

            if (ImageConstant.ISO_FORMAT_STRING.equals(format)) {
                throw new ApiMessageInterceptionException(argerr("image[uuid:%s] is of format %s, cannot be used for virtual router", msg.getImageUuid(), format));
            }
        }
    }
    private Boolean isNetworkAddressEqual(String networkUuid1, String networkUuid2) {
        if (networkUuid1.equals(networkUuid2)) {
            return true;
        }
        L3NetworkVO l3vo1 = Q.New(L3NetworkVO.class).eq(L3NetworkVO_.uuid, networkUuid1).find();
        L3NetworkVO l3vo2 = Q.New(L3NetworkVO.class).eq(L3NetworkVO_.uuid, networkUuid2).find();
        if (!l3vo1.getIpVersion().equals(l3vo2.getIpVersion())) {
            return false;
        }
        List<IpRangeInventory> ipInvs1 = IpRangeHelper.getNormalIpRanges(l3vo1);
        List<IpRangeInventory> ipInvs2 = IpRangeHelper.getNormalIpRanges(l3vo2);

        if (l3vo1.getIpVersion() == IPv6Constants.IPv4) {
            String netAddr1 = new SubnetUtils(ipInvs1.get(0).getGateway(), ipInvs1.get(0).getNetmask()).getInfo().getNetworkAddress();
            String netAddr2 = new SubnetUtils(ipInvs2.get(0).getGateway(), ipInvs2.get(0).getNetmask()).getInfo().getNetworkAddress();
            return netAddr1.equals(netAddr2);
        } else if (l3vo1.getIpVersion() == IPv6Constants.IPv6) {
            return IPv6NetworkUtils.isIpv6CidrEqual(ipInvs1.get(0).getNetworkCidr(), ipInvs2.get(0).getNetworkCidr());
        }
        return false;
    }
    private void validate(APICreateVirtualRouterOfferingMsg msg) {
        if (msg.isDefault() != null) {
            if (!new QuotaUtil().isAdminAccount(msg.getSession().getAccountUuid())) {
                throw new ApiMessageInterceptionException(err(IdentityErrors.PERMISSION_DENIED,
                        "cannot create a virtual router offering with the default field set; only admin can do the operation"
                ));
            }
        }

        if (msg.getPublicNetworkUuid() == null) {
            msg.setPublicNetworkUuid(msg.getManagementNetworkUuid());
        }

        SimpleQuery<L3NetworkVO> q = dbf.createQuery(L3NetworkVO.class);
        q.select(L3NetworkVO_.zoneUuid);
        q.add(L3NetworkVO_.uuid, Op.EQ, msg.getManagementNetworkUuid());
        String zoneUuid = q.findValue();
        if (!zoneUuid.equals(msg.getZoneUuid()))  {
            throw new ApiMessageInterceptionException(argerr("management network[uuid:%s] is not in the same zone[uuid:%s] this offering is going to create",
                            msg.getManagementNetworkUuid(), msg.getZoneUuid()));
        }

        if (!CoreGlobalProperty.UNIT_TEST_ON) {
            checkIfManagementNetworkReachable(msg.getManagementNetworkUuid());
        }

        q = dbf.createQuery(L3NetworkVO.class);
        q.select(L3NetworkVO_.zoneUuid);
        q.add(L3NetworkVO_.uuid, Op.EQ, msg.getPublicNetworkUuid());
        zoneUuid = q.findValue();
        if (!zoneUuid.equals(msg.getZoneUuid()))  {
            throw new ApiMessageInterceptionException(argerr("public network[uuid:%s] is not in the same zone[uuid:%s] this offering is going to create",
                            msg.getManagementNetworkUuid(), msg.getZoneUuid()));
        }

        SimpleQuery<ImageVO> imq = dbf.createQuery(ImageVO.class);
        imq.select(ImageVO_.mediaType, ImageVO_.format);
        imq.add(ImageVO_.uuid, Op.EQ, msg.getImageUuid());
        Tuple t = imq.findTuple();

        ImageMediaType type = t.get(0, ImageMediaType.class);
        if (type != ImageMediaType.RootVolumeTemplate) {
            throw new ApiMessageInterceptionException(argerr("image[uuid:%s]'s mediaType is %s, the mediaType of a virtual router image must be %s",
                            msg.getImageUuid(), type, ImageMediaType.RootVolumeTemplate));
        }

        String format = t.get(1, String.class);
        if (ImageConstant.ISO_FORMAT_STRING.equals(format)) {
            throw new ApiMessageInterceptionException(argerr("image[uuid:%s] is of format %s, cannot be used for virtual router", msg.getImageUuid(), format));
        }

        SimpleQuery<NetworkServiceL3NetworkRefVO> nq = dbf.createQuery(NetworkServiceL3NetworkRefVO.class);
        nq.add(NetworkServiceL3NetworkRefVO_.l3NetworkUuid, Op.IN, list(msg.getPublicNetworkUuid(), msg.getManagementNetworkUuid()));
        List<NetworkServiceL3NetworkRefVO> nrefs= nq.list();
        for (NetworkServiceL3NetworkRefVO nref : nrefs) {
            if (NetworkServiceType.SNAT.toString().equals(nref.getNetworkServiceType())) {
                if (nref.getL3NetworkUuid().equals(msg.getManagementNetworkUuid())) {
                    throw new ApiMessageInterceptionException(argerr("the L3 network[uuid: %s] has the SNAT service enabled, it cannot be used as a management network", msg.getManagementNetworkUuid()));
                } else if (nref.getL3NetworkUuid().equals(msg.getPublicNetworkUuid())) {
                    throw new ApiMessageInterceptionException(argerr("the L3 network[uuid: %s] has the SNAT service enabled, it cannot be used as a public network", msg.getPublicNetworkUuid()));
                }
            }
        }

        if (!msg.getManagementNetworkUuid().equals(msg.getPublicNetworkUuid())) {
            if (isNetworkAddressEqual(msg.getManagementNetworkUuid(), msg.getPublicNetworkUuid())) {
     throw new ApiMessageInterceptionException(argerr("the L3 network[uuid: %s] is same network address with [uuid: %s], it cannot be used for virtual router", msg.getManagementNetworkUuid(),msg.getPublicNetworkUuid()));
            }
        }
    }

    private void checkIfManagementNetworkReachable(String managementNetworkUuid) {
        SimpleQuery<NormalIpRangeVO> q = dbf.createQuery(NormalIpRangeVO.class);
        q.add(NormalIpRangeVO_.l3NetworkUuid, Op.EQ, managementNetworkUuid);
        List<NormalIpRangeVO> iprs = q.list();
        if (iprs.isEmpty()) {
            throw new ApiMessageInterceptionException(operr("the management network[uuid:%s] doesn't have any IP range", managementNetworkUuid));
        }

        String startIp = iprs.get(0).getStartIp();
        if (!NetworkUtils.isIpRoutedByDefaultGateway(startIp)) {
            // the mgmt server is in the same subnet of the mgmt network
            return;
        }

        String gateway = iprs.get(0).getGateway();
        for (int i=0; i<3; i++) {
            ShellResult ret = ShellUtils.runAndReturn(String.format("ping -c 1 -W 2 %s", gateway));
            if (ret.isReturnCode(0)) {
                return;
            }
        }

        throw new ApiMessageInterceptionException(argerr("the management network[uuid:%s, gateway:%s] is not reachable", managementNetworkUuid, gateway));
    }

    private void validate(APIQueryVirtualRouterOfferingMsg msg) {
        boolean found = false;
        for (QueryCondition qcond : msg.getConditions()) {
            if ("type".equals(qcond.getName())) {
                qcond.setOp(QueryOp.EQ.toString());
                qcond.setValue(VirtualRouterConstant.VIRTUAL_ROUTER_OFFERING_TYPE);
                found = true;
                break;
            }
        }

        if (!found) {
            msg.addQueryCondition("type", QueryOp.EQ, VirtualRouterConstant.VIRTUAL_ROUTER_OFFERING_TYPE);
        }
    }
}
