package com.sky;

import com.jayway.jsonpath.JsonPath;
import com.sky.context.BaseContext;
import com.sky.support.TestDataHelper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 秒杀接口测试：走完整的 Spring MVC 链路（含 JWT 拦截器）
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SeckillApiTest {

    private static final long SECKILL_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        TestDataHelper helper = new TestDataHelper(jdbcTemplate, stringRedisTemplate);
        helper.resetSeckill(SECKILL_ID, 10);
        // 秒杀接口加了限流，清掉计数器避免上一个用例的额度影响本用例
        helper.clearRateLimit();

        //走本地兜底登录拿token（application-test.yml 里 dev-mode: true）
        MvcResult login = mockMvc.perform(post("/user/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"test-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andReturn();

        token = JsonPath.read(login.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.data.token");
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    @Test
    void 不带token访问秒杀接口应被拦截返回401() throws Exception {
        mockMvc.perform(get("/user/seckill/list"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 带token应能获取秒杀活动列表() throws Exception {
        mockMvc.perform(get("/user/seckill/list").header("authentication", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].name").isNotEmpty());
    }

    @Test
    void 带token可以抢购并拿到订单号() throws Exception {
        MvcResult result = mockMvc.perform(post("/user/seckill/" + SECKILL_ID)
                        .header("authentication", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andReturn();

        String orderNo = JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data");
        assertThat(orderNo).isNotBlank();
    }

    @Test
    void 抢购后可轮询到下单结果() throws Exception {
        MvcResult result = mockMvc.perform(post("/user/seckill/" + SECKILL_ID)
                        .header("authentication", token))
                .andExpect(jsonPath("$.code").value(1))
                .andReturn();

        String orderNo = JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data");

        TestDataHelper.awaitUntil("异步下单完成", 10000,
                () -> "1".equals(stringRedisTemplate.opsForValue()
                        .get("seckill:result:" + orderNo)));

        mockMvc.perform(get("/user/seckill/result/" + orderNo).header("authentication", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data").value(1));
    }
}
