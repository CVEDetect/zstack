CREATE TABLE IF NOT EXISTS `zstack`.`ClusterDRSVO` (
    `uuid` varchar(32) not null unique,
    `name` varchar(256) NOT NULL,
    `clusterUuid` varchar(32) not null,
    `state`  varchar(255) not null,
    `automationLevel`  varchar(255) not null,
    `thresholds`  text not null,
    `thresholdDuration`  varchar(255) not null,
    `description`  varchar(255) default null,
    `createDate` timestamp not null default '0000-00-00 00:00:00',
    `lastOpDate` timestamp not null default '0000-00-00 00:00:00' ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`uuid`),
    UNIQUE KEY `clusterUuid` (`clusterUuid`),
    CONSTRAINT `fkClusterDRSVOClusterEO` FOREIGN KEY (`clusterUuid`) REFERENCES `ClusterEO` (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS `zstack`.`DRSAdviceVO` (
    `uuid` varchar(32) not null unique,
    `drsUuid` varchar(32) not null,
    `adviceGroupUuid` varchar(32) not null,
    `vmUuid` varchar(32) not null,
    `vmSourceHostUuid` varchar(32) not null,
    `vmTargetHostUuid` varchar(32) not null,
    `reason`  varchar(255) not null,
    `createDate` timestamp not null default '0000-00-00 00:00:00',
    `lastOpDate` timestamp not null default '0000-00-00 00:00:00' ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`uuid`),
    CONSTRAINT `fkDRSAdviceVOClusterDRSVO` FOREIGN KEY (`drsUuid`) REFERENCES `ClusterDRSVO` (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS `zstack`.`DRSVmMigrationActivityVO` (
    `uuid` varchar(32) not null unique,
    `drsUuid` varchar(32) default null,
    `vmUuid` varchar(32) not null,
    `vmSourceHostUuid` varchar(32) not null,
    `vmTargetHostUuid` varchar(32) not null,
    `status`  varchar(255) not null,
    `endDate` datetime default null,
    `adviceUuid` varchar(32) default null,
    `reason`  varchar(255) not null,
    `result`  varchar(1024) default null,
    `createDate` timestamp not null default '0000-00-00 00:00:00',
    `lastOpDate` timestamp not null default '0000-00-00 00:00:00' ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`uuid`),
    CONSTRAINT `fkDRSVmMigrationActivityVOClusterDRSVO` FOREIGN KEY (`drsUuid`) REFERENCES `ClusterDRSVO` (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;