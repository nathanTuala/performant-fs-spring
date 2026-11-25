package com.performantfs.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.performantfs.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class RequestLogIT extends IntegrationTest {

    @Test
    void returnsTheTraceIdOfEveryApiRequest() {
        var alice = newUser();
        var res = alice.api().get("/api/nodes/" + alice.rootId() + "/children");
        assertThat(res.header("x-trace-id")).matches("[0-9a-f]{32}");
        var other = alice.api().get("/api/nodes/" + alice.rootId() + "/children");
        assertThat(other.header("x-trace-id")).isNotEqualTo(res.header("x-trace-id"));
    }

    @Test
    void logsOneLinePerRequestWithStatusTimeAndUser(CapturedOutput output) {
        var alice = newUser();
        alice.api().get("/api/nodes/" + alice.rootId() + "/children");
        assertThat(output.getOut()).containsPattern(
                "GET /api/nodes/" + alice.rootId() + "/children 200 \\d+ms user=" + alice.id());
    }

    @Test
    void logsFailuresAndUnauthenticatedRequestsToo(CapturedOutput output) {
        anonymous().get("/api/nodes/00000000-0000-0000-0000-000000000000/children");
        assertThat(output.getOut()).containsPattern("WARN.*GET /api/nodes/0{8}-0{4}-0{4}-0{4}-0{12}/children 401 \\d+ms user=-");
    }

    @Test
    void neverLogsTheQueryString(CapturedOutput output) {
        var alice = newUser();
        alice.api().get("/api/search?q=my+secret+diagnosis");
        assertThat(output.getOut()).contains("GET /api/search 200").doesNotContain("secret");
    }
}
