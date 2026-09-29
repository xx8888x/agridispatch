package com.agridispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 整合測試共用工具（MockMvc + 記憶體 H2）
 */
public abstract class ApiTestSupport {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper om;

    protected JsonNode json(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder b, String token) {
        return token == null ? b : b.header("Authorization", "Bearer " + token);
    }

    protected MvcResult call(String method, String url, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder b = switch (method) {
            case "GET" -> get(url);
            case "POST" -> post(url);
            case "PUT" -> put(url);
            case "DELETE" -> delete(url);
            default -> throw new IllegalArgumentException(method);
        };
        b = withToken(b, token);
        if (body != null) {
            b = b.contentType(MediaType.APPLICATION_JSON)
                    .content(body instanceof String s ? s : om.writeValueAsString(body));
        }
        return mvc.perform(b).andReturn();
    }

    protected String login(String identifier, String password) throws Exception {
        MvcResult r = call("POST", "/api/auth/login", null, Map.of("identifier", identifier, "password", password));
        if (r.getResponse().getStatus() != 200) {
            throw new AssertionError("登入失敗 " + identifier + "：" + r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        }
        return json(r).get("token").asText();
    }
}
