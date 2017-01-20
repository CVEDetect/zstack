package org.zstack.header.storage.backup

doc {
    title "删除镜像服务器(DeleteBackupStorage)"

    category "storage.backup"

    desc "删除指定的镜像服务器"

    rest {
        request {
			url "DELETE /v1/backup-storage/{uuid}"


            header (OAuth: 'the-session-uuid')

            clz APIDeleteBackupStorageMsg.class

            desc ""
            
			params {

				column {
					name "uuid"
					enclosedIn "params"
					desc "资源的UUID，唯一标示该资源"
					location "url"
					type "String"
					optional false
					since "0.6"
					
				}
				column {
					name "deleteMode"
					enclosedIn "params"
					desc "删除模式(Permissive 或者 Enforcing, 默认 Permissive)"
					location "body"
					type "String"
					optional true
					since "0.6"
					
				}
				column {
					name "systemTags"
					enclosedIn "params"
					desc "系统标签"
					location "body"
					type "List"
					optional true
					since "0.6"
					
				}
				column {
					name "userTags"
					enclosedIn "params"
					desc "用户标签"
					location "body"
					type "List"
					optional true
					since "0.6"
					
				}
			}
        }

        response {
            clz APIDeleteBackupStorageEvent.class
        }
    }
}