package org.zstack.test.integration.kvm.vm

import org.springframework.http.HttpEntity
import org.zstack.network.service.flat.FlatUserdataBackend
import org.zstack.sdk.ApiException
import org.zstack.sdk.CreateVmInstanceAction
import org.zstack.sdk.InstanceOfferingInventory
import org.zstack.sdk.VmInstanceInventory
import org.zstack.test.integration.kvm.Env
import org.zstack.test.integration.kvm.KvmTest
import org.zstack.testlib.EnvSpec
import org.zstack.testlib.SubCase
import org.zstack.testlib.Test
import org.zstack.utils.data.SizeUnit

/**
 * Created by lining on 2017/05/01.
 */
class CreateVmWithEmptyNameCase extends SubCase {
    EnvSpec env

    @Override
    void setup() {
        useSpring(KvmTest.springSpec)
    }

    @Override
    void environment() {
        env = Env.oneVmBasicEnv()
    }

    @Override
    void test() {
        env.create {
            createVmWithEmptyNameTest()
            createVmWithNullNameTest()
            createVmWithFullInstanceOffering()
        }
    }

    void createVmWithEmptyNameTest() {
        VmInstanceInventory vm = env.inventoryByName("vm")
        assert null != vm

        int cnt = 0
        env.afterSimulator(FlatUserdataBackend.APPLY_USER_DATA) { rsp, HttpEntity<String> e ->
            cnt += 1
            return rsp
        }

        createVmInstance {
            name = ""
            instanceOfferingUuid =  vm.instanceOfferingUuid
            l3NetworkUuids = [vm.defaultL3NetworkUuid]
            imageUuid = vm.imageUuid
        }

        assert cnt == 0
    }

    void createVmWithNullNameTest() {
        VmInstanceInventory vm = env.inventoryByName("vm")
        assert null != vm

        CreateVmInstanceAction createVmInstanceAction = new CreateVmInstanceAction(
                sessionId: Test.currentEnvSpec.session.uuid,
                instanceOfferingUuid : vm.instanceOfferingUuid,
                l3NetworkUuids : [vm.defaultL3NetworkUuid],
                imageUuid : vm.imageUuid
        )

        try {
            createVmInstanceAction.call()
        }catch (ApiException e){
            assert -1 < e.message.indexOf("missing mandatory field[name]")
        }

    }

    void createVmWithFullInstanceOffering() {
        VmInstanceInventory vm = env.inventoryByName("vm") as VmInstanceInventory

        InstanceOfferingInventory instance = createInstanceOffering {
            name = "1C1G"
            cpuNum = 1
            memorySize = SizeUnit.GIGABYTE.toByte(1)
            imageUuid = vm.imageUuid
            diskSize = 1
        } as InstanceOfferingInventory

        def vm1 = createVmInstance {
            name = "1"
            instanceOfferingUuid =  instance.uuid
            l3NetworkUuids = [vm.defaultL3NetworkUuid]
        } as VmInstanceInventory

        assert vm1.allVolumes[0].size != 1
        
        deleteInstanceOffering {
            uuid = instance.uuid
        }
    }

    @Override
    void clean() {
        env.delete()
    }
}
