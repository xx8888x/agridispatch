package com.agridispatch;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 舊中文資料夾網址 301 轉到英文資料夾 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_legacy_path_test;DB_CLOSE_DELAY=-1",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef"
})
@AutoConfigureMockMvc
class LegacyPathRedirectTest {

    @Autowired
    MockMvc mvc;

    private void expect(String from, String to) throws Exception {
        mvc.perform(get(URI.create(from)))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", to));
    }

    @Test
    void oldFolderNamesRedirect() throws Exception {
        expect("/web/%E8%BE%B2%E6%A5%AD%E6%B4%BE%E9%81%A3%E4%B8%BB%E7%B6%B2%E7%AB%99/index.html",
                "/web/AgriDispatch/index.html");
        expect("/demo/%E8%BE%B2%E6%A5%AD%E6%B4%BE%E9%81%A3%E4%B8%BB%E7%B6%B2%E7%AB%99/farmer.html?x=1",
                "/demo/AgriDispatch/farmer.html?x=1");
        expect("/web/%E8%BE%B2%E6%A5%AD%E6%B4%BE%E9%81%A3%E4%B8%BB%E7%B6%B2%E7%AB%99/",
                "/web/AgriDispatch/index.html");
        expect("/web/%E7%A7%91%E6%8A%80%E5%BE%8C%E5%8F%B0/admin.html", "/web/TechBackend/admin.html");
        expect("/demo/%E7%A7%91%E6%8A%80%E5%BE%8C%E5%8F%B0", "/demo/TechBackend/admin-login.html");
    }
}
