package com.agentmonitor.live;

import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Submits a verified notification approval/denial decision over the authenticated control API. */
final class NativeApprovalSender {
    private NativeApprovalSender() { }

    static void send(String taskId, String requestId, String revision, String decision, String token) throws NativeApi.Failure {
        JSONObject body = new JSONObject();
        try {
            body.put("task_id", taskId);
            body.put("request_id", requestId);
            body.put("revision", revision);
            body.put("decision_id", UUID.randomUUID().toString());
            body.put("decision", decision);
            body.put("answers", new JSONArray());
        } catch (Exception invalid) {
            throw new NativeApi.Failure(0, "无效的审批参数");
        }
        NativeApi.call("POST", "/api/native/tasks/controls/decision", body, token);
    }
}
