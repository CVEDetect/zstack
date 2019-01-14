package org.zstack.test.integration.kvm.vm.cdrom

import org.zstack.compute.vm.VmCdRomGlobalProperty
import org.zstack.compute.vm.VmSystemTags
import org.zstack.header.image.ImageConstant
import org.zstack.header.vm.VmInstanceConstant
import org.zstack.sdk.DiskOfferingInventory
import org.zstack.sdk.ImageInventory
import org.zstack.sdk.InstanceOfferingInventory
import org.zstack.sdk.L3NetworkInventory
import org.zstack.sdk.VmCdRomInventory
import org.zstack.sdk.VmInstanceInventory
import org.zstack.test.integration.kvm.KvmTest
import org.zstack.testlib.EnvSpec
import org.zstack.testlib.SubCase
import org.zstack.utils.data.SizeUnit

/**
 * Created by lining on 2019/01/13.
 */
class VmIsoSystemTagCase extends SubCase {
    EnvSpec env

    @Override
    void clean() {
        env.delete()
    }

    @Override
    void setup() {
        useSpring(KvmTest.springSpec)
    }

    @Override
    void environment() {
        env = env {
            instanceOffering {
                name = "instanceOffering"
                memory = SizeUnit.GIGABYTE.toByte(1)
                cpu = 4
            }

            diskOffering {
                name = "diskOffering"
                diskSize = SizeUnit.GIGABYTE.toByte(20)
            }

            sftpBackupStorage {
                name = "sftp"
                url = "/sftp"
                username = "root"
                password = "password"
                hostname = "localhost"

                image {
                    name = "iso_0"
                    url = "http://zstack.org/download/test.iso"
                    format = ImageConstant.ISO_FORMAT_STRING.toString()
                }

                image {
                    name = "iso_1"
                    url = "http://zstack.org/download/test.iso"
                    format = ImageConstant.ISO_FORMAT_STRING.toString()
                }

                image {
                    name = "iso_2"
                    url = "http://zstack.org/download/test.iso"
                    format = ImageConstant.ISO_FORMAT_STRING.toString()
                }

                image {
                    name = "image"
                    url = "http://zstack.org/download/image.qcow2"
                }
            }

            zone {
                name = "zone"
                description = "test"

                cluster {
                    name = "cluster"
                    hypervisorType = "KVM"

                    kvm {
                        name = "kvm"
                        managementIp = "localhost"
                        username = "root"
                        password = "password"
                    }

                    attachPrimaryStorage("local")
                    attachL2Network("l2")
                }

                localPrimaryStorage {
                    name = "local"
                    url = "/local_ps"
                }

                l2NoVlanNetwork {
                    name = "l2"
                    physicalInterface = "eth0"

                    l3Network {
                        name = "l3"

                        ip {
                            startIp = "12.16.10.10"
                            endIp = "12.16.10.100"
                            netmask = "255.255.255.0"
                            gateway = "12.16.10.1"
                        }
                    }
                }
                attachBackupStorage("sftp")
            }
        }

    }

    @Override
    void test() {
        env.create {
            testVmIsoSystemTag()
        }
    }

    void testVmIsoSystemTag() {
        VmCdRomGlobalProperty.syncVmIsoSystemTag = true

        DiskOfferingInventory diskOffering = env.inventoryByName("diskOffering")
        ImageInventory iso = env.inventoryByName("iso_0")
        ImageInventory iso1 = env.inventoryByName("iso_1")
        ImageInventory iso2 = env.inventoryByName("iso_2")
        L3NetworkInventory l3 = env.inventoryByName("l3")
        InstanceOfferingInventory instanceOfferingInventory = env.inventoryByName("instanceOffering")

        VmInstanceInventory vm = createVmInstance {
            name = "vm"
            instanceOfferingUuid = instanceOfferingInventory.uuid
            rootDiskOfferingUuid = diskOffering.uuid
            imageUuid = iso.uuid
            l3NetworkUuids = [l3.uuid]
            systemTags = [
                    "${VmSystemTags.CD_ROM_LIST_TOKEN}::${iso.uuid}::${iso1.uuid}::${VmInstanceConstant.EMPTY_CDROM}".toString()
            ]
        }

        retryInSecs {
            List<String> isoUuids = getIsoUuidByVmUuid(vm.uuid)
            assert 2 == isoUuids.size()
            assert isoUuids.containsAll([
                    iso.uuid,
                    iso1.uuid
            ])
        }

        attachIsoToVmInstance {
            vmInstanceUuid = vm.uuid
            isoUuid = iso2.uuid
        }
        retryInSecs {
            List<String> isoUuids = getIsoUuidByVmUuid(vm.uuid)
            assert 3 == isoUuids.size()
            assert isoUuids.containsAll([
                    iso.uuid,
                    iso1.uuid,
                    iso2.uuid
            ])
        }

        detachIsoFromVmInstance {
            vmInstanceUuid = vm.uuid
            isoUuid = iso2.uuid
        }
        retryInSecs {
            List<String> isoUuids = getIsoUuidByVmUuid(vm.uuid)
            assert 2 == isoUuids.size()
            assert isoUuids.containsAll([
                    iso.uuid,
                    iso1.uuid
            ])
        }

        detachIsoFromVmInstance {
            vmInstanceUuid = vm.uuid
            isoUuid = iso1.uuid
        }
        retryInSecs {
            List<String> isoUuids = getIsoUuidByVmUuid(vm.uuid)
            assert 1 == isoUuids.size()
            assert isoUuids.containsAll([
                    iso.uuid
            ])
        }
    }

    List<String> getIsoUuidByVmUuid(String vmUuid) {
        List<String> result = []

        List<Map<String, String>> tokenList = VmSystemTags.ISO.getTokensOfTagsByResourceUuid(vmUuid)
        for (Map<String, String> tokens : tokenList) {
            result.add(tokens.get(VmSystemTags.ISO_TOKEN))
        }

        return result
    }

}