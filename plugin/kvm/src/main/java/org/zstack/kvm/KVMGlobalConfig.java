package org.zstack.kvm;

import org.zstack.core.config.GlobalConfig;
import org.zstack.core.config.GlobalConfigDef;
import org.zstack.core.config.GlobalConfigDefinition;
import org.zstack.core.config.GlobalConfigValidation;
import org.zstack.header.cluster.ClusterVO;
import org.zstack.header.host.HostVO;
import org.zstack.header.zone.ZoneVO;
import org.zstack.resourceconfig.BindResourceConfig;

/**
 */
@GlobalConfigDefinition
public class KVMGlobalConfig {
    public static final String CATEGORY = "kvm";

    @GlobalConfigValidation(numberGreaterThan = 0)
    public static GlobalConfig VM_MIGRATION_QUANTITY = new GlobalConfig(CATEGORY, "vm.migrationQuantity");
    @GlobalConfigValidation(numberGreaterThan = -1)
    public static GlobalConfig RESERVED_CPU_CAPACITY = new GlobalConfig(CATEGORY, "reservedCpu");
    @GlobalConfigValidation
    @BindResourceConfig({HostVO.class, ClusterVO.class, ZoneVO.class})
    public static GlobalConfig RESERVED_MEMORY_CAPACITY = new GlobalConfig(CATEGORY, "reservedMemory");
    @GlobalConfigValidation(inNumberRange = {0, 24})
    public static GlobalConfig MAX_DATA_VOLUME_NUM = new GlobalConfig(CATEGORY, "dataVolume.maxNum");
    @GlobalConfigValidation(numberGreaterThan = 2)
    public static GlobalConfig HOST_SYNC_LEVEL = new GlobalConfig(CATEGORY, "host.syncLevel");
    @GlobalConfigValidation(numberGreaterThan = 2)
    public static GlobalConfig HOST_SNAPSHOT_SYNC_LEVEL = new GlobalConfig(CATEGORY, "host.snapshot.syncLevel");
    @GlobalConfigValidation(inNumberRange = {1, 10})
    public static GlobalConfig VM_CREATE_CONCURRENCY = new GlobalConfig(CATEGORY, "vm.createConcurrency");
    @GlobalConfigValidation
    public static GlobalConfig HOST_DNS_CHECK_ALIYUN = new GlobalConfig(CATEGORY, "host.DNSCheckAliyun");
    @GlobalConfigValidation
    public static GlobalConfig HOST_DNS_CHECK_163 = new GlobalConfig(CATEGORY, "host.DNSCheck163");
    @GlobalConfigValidation
    public static GlobalConfig HOST_DNS_CHECK_LIST = new GlobalConfig(CATEGORY, "host.DNSCheckList");
    @GlobalConfigValidation
    public static GlobalConfig ALLOW_LIVE_SNAPSHOT_ON_REDHAT = new GlobalConfig(CATEGORY, "redhat.liveSnapshotOn");
    @GlobalConfigValidation(validValues = {"none", "writethrough", "writeback"})
    public static GlobalConfig LIBVIRT_CACHE_MODE = new GlobalConfig(CATEGORY, "vm.cacheMode");
    @GlobalConfigValidation(validValues = {"none", "host-model", "host-passthrough"})
    public static GlobalConfig NESTED_VIRTUALIZATION = new GlobalConfig(CATEGORY, "vm.cpuMode");
    @GlobalConfigValidation
    public static GlobalConfig VM_SYNC_ON_HOST_PING = new GlobalConfig(CATEGORY, "vmSyncOnHostPing");
    @GlobalConfigValidation
    public static GlobalConfig CHECK_HOST_CPU_MODEL_NAME = new GlobalConfig(CATEGORY, "checkHostCpuModelName");
    @GlobalConfigValidation
    public static GlobalConfig KVM_IGNORE_MSRS = new GlobalConfig(CATEGORY, "ignoreMsrs");
    @GlobalConfigValidation
    public static GlobalConfig MIGRATE_AUTO_CONVERGE = new GlobalConfig(CATEGORY, "migrate.autoConverge");
    @GlobalConfigValidation(numberGreaterThan = 0, numberLessThan = 3600)
    public static GlobalConfig TEST_SSH_PORT_ON_OPEN_TIMEOUT = new GlobalConfig(CATEGORY, "testSshPortOpenTimeout");
    @GlobalConfigValidation(numberGreaterThan = 0, numberLessThan = 300)
    public static GlobalConfig TEST_SSH_PORT_ON_CONNECT_TIMEOUT = new GlobalConfig(CATEGORY, "testSshPortOnConnectTimeout");
    @GlobalConfigValidation
    public static GlobalConfig RESTART_AGENT_IF_FAKE_DEAD = new GlobalConfig(CATEGORY, "restartagentwhenfakedead");

    @GlobalConfigValidation(validValues = {"true", "false"})
    @GlobalConfigDef(defaultValue = "true", type = Boolean.class, description = "enable QEMU native TLS")
    public static GlobalConfig ENABLE_QEMU_NATIVE_TLS = new GlobalConfig(CATEGORY, "enableQemuNativeTLS");
}
