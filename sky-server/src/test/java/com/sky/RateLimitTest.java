package com.sky;

import com.jayway.jsonpath.JsonPath;
import com.sky.context.BaseContext;
import com.sky.properties.JwtProperties;
import com.sky.support.TestDataHelper;
import com.sky.utils.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口限流测试：Redis + Lua 滑动窗口
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitTest {

    private static final long SECKILL_ID = 1L;
    private static final String RATE_LIMITED = "请求过于频繁";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtProperties jwtProperties;

    private TestDataHelper helper;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestDataHelper(jdbcTemplate, stringRedisTemplate);
        helper.clearRateLimit();
        helper.clearIdempotent();

        //库存置0：让业务层快速返回"售罄"，这样测的就是限流本身，不受库存和建单影响
        helper.resetSeckill(SECKILL_ID, 0);

        MvcResult login = mockMvc.perform(post("/user/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"rate-limit-test\"}"))
                .andExpect(status().isOk())
                .andReturn();
        token = JsonPath.read(login.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.data.token");
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    @Test
    void 同一用户一秒内超过五次应被限流() throws Exception {
        long start = System.currentTimeMillis();
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            bodies.add(callSeckill(token));
        }
        long cost = System.currentTimeMillis() - start;

        //6次必须在同一秒内完成，否则窗口滑过去就测不出限流了
        assertThat(cost).as("6次请求应能在1秒内完成").isLessThan(1000L);

        //前5次放行（业务上是"售罄"，但没被限流）
        for (int i = 0; i < 5; i++) {
            assertThat(bodies.get(i))
                    .as("第%d次请求不应被限流", i + 1)
                    .doesNotContain(RATE_LIMITED);
        }
        //第6次被限流
        assertThat(bodies.get(5))
                .as("第6次请求应被限流")
                .contains(RATE_LIMITED);
    }

    @Test
    void 不同用户的额度互不影响() throws Exception {
        //用户A把额度打满
        for (int i = 0; i < 5; i++) {
            callSeckill(token);
        }
        //用户B第一次请求，不应该被A的额度影响
        //jjwt的setClaims会修改传入的Map，所以必须用可变的HashMap
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", 99999L);
        String otherUserToken = JwtUtil.createJWT(
                jwtProperties.getUserSecretKey(), 7200000L, claims);

        assertThat(callSeckill(otherUserToken))
                .as("限流按用户维度隔离，B用户不应被A用户影响")
                .doesNotContain(RATE_LIMITED);
    }

    private String callSeckill(String userToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/user/seckill/" + SECKILL_ID)
                        .header("authentication", userToken))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
