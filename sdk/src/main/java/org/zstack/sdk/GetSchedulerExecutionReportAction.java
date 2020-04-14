package org.zstack.sdk;

import java.util.HashMap;
import java.util.Map;

public class GetSchedulerExecutionReportAction extends AbstractAction {

    private static final HashMap<String, Parameter> parameterMap = new HashMap<>();

    private static final HashMap<String, Parameter> nonAPIParameterMap = new HashMap<>();

    public static class Result {
        public ErrorCode error;
        public GetSchedulerExecutionReportResult value;

        public Result throwExceptionIfError() {
            if (error != null) {
                throw new ApiException(
                    String.format("error[code: %s, description: %s, details: %s]", error.code, error.description, error.details)
                );
            }
            
            return this;
        }
    }

    @Param(required = true, nonempty = false, nullElements = false, emptyString = true, noTrim = false)
    public long startTime = 0L;

    @Param(required = true, validValues = {"Hour","Day","Month"}, nonempty = false, nullElements = false, emptyString = true, noTrim = false)
    public java.lang.String intervalTimeUnit;

    @Param(required = true, nonempty = false, nullElements = false, emptyString = true, numberRange = {0L,31L}, noTrim = false)
    public int range = 0;

    @Param(required = true, nonempty = false, nullElements = false, emptyString = true, noTrim = false)
    public java.util.List schedulerJobTypes;

    @Param(required = false)
    public java.util.List systemTags;

    @Param(required = false)
    public java.util.List userTags;

    @Param(required = false)
    public String sessionId;

    @Param(required = false)
    public String accessKeyId;

    @Param(required = false)
    public String accessKeySecret;

    @Param(required = false)
    public String requestIp;


    private Result makeResult(ApiResult res) {
        Result ret = new Result();
        if (res.error != null) {
            ret.error = res.error;
            return ret;
        }
        
        GetSchedulerExecutionReportResult value = res.getResult(GetSchedulerExecutionReportResult.class);
        ret.value = value == null ? new GetSchedulerExecutionReportResult() : value;

        return ret;
    }

    public Result call() {
        ApiResult res = ZSClient.call(this);
        return makeResult(res);
    }

    public void call(final Completion<Result> completion) {
        ZSClient.call(this, new InternalCompletion() {
            @Override
            public void complete(ApiResult res) {
                completion.complete(makeResult(res));
            }
        });
    }

    protected Map<String, Parameter> getParameterMap() {
        return parameterMap;
    }

    protected Map<String, Parameter> getNonAPIParameterMap() {
        return nonAPIParameterMap;
    }

    protected RestInfo getRestInfo() {
        RestInfo info = new RestInfo();
        info.httpMethod = "GET";
        info.path = "/scheduler/report";
        info.needSession = true;
        info.needPoll = false;
        info.parameterName = "";
        return info;
    }

}
