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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口幂等测试：Token 机制 + Redis DEL 原子性
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdempotentTest {

    private static final String TOKEN_HEADER = "idempotent-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestDataHelper helper;
    private String userToken;
    private long userId;
    private long addressBookId;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestDataHelper(jdbcTemplate, stringRedisTemplate);
        helper.clearIdempotent();
        helper.clearRateLimit();

        //登录，拿到用户id和token
        MvcResult login = mockMvc.perform(post("/user/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"idempotent-test\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String loginBody = login.getResponse().getContentAsString(StandardCharsets.UTF_8);
        userToken = JsonPath.read(loginBody, "$.data.token");
        userId = ((Number) JsonPath.read(loginBody, "$.data.id")).longValue();

        //准备下单前置数据：一条默认地址 + 一条购物车
        helper.resetUserOrderData(userId);
        addressBookId = helper.createDefaultAddress(userId);
        helper.createShoppingCartItem(userId, 51L, "老坛酸菜鱼", "9.90");
    }

    @AfterEach
    void tearDown() {
        helper.resetUserOrderData(userId);
        BaseContext.removeCurrentId();
    }

    @Test
    void 同一个token只能提交一次订单() throws Exception {
        String idemToken = fetchIdempotentToken();

        //第一次提交：成功
        String first = submit(idemToken);
        assertThat(first).as("第一次提交应成功").contains("\"code\":1");

        //第二次用同一个token：被拦截
        String second = submit(idemToken);
        assertThat(second).as("重复提交应被拦截").contains("订单正在处理中");

        //数据库里只应有一笔订单
        Integer orders = jdbcTemplate.queryForObject(
                "select count(*) from orders where user_id = ?", Integer.class, userId);
        assertThat(orders).as("重复提交不能产生第二笔订单").isEqualTo(1);
    }

    @Test
    void 不带token提交应被拒绝() throws Exception {
        String body = submitWithHeader(null);
        assertThat(body).as("缺少幂等token应报错").contains("缺少幂等token");
    }

    @Test
    void 业务失败后token应被还回来允许重试() throws Exception {
        String idemToken = fetchIdempotentToken();

        //把购物车清空，制造业务失败
        jdbcTemplate.update("delete from shopping_cart where user_id = ?", userId);

        String failed = submit(idemToken);
        assertThat(failed).as("购物车为空应报错").contains("购物车数据为空");

        //补齐购物车，用同一个token重试——如果token没被还回来，这里会报"重复提交"
        helper.createShoppingCartItem(userId, 51L, "老坛酸菜鱼", "9.90");

        String retried = submit(idemToken);
        assertThat(retried).as("业务失败后应允许用同一token重试").contains("\"code\":1");
    }

    /**
     * 获取一次性幂等token
     */
    private String fetchIdempotentToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/user/idempotent/token")
                        .header("authentication", userToken))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data");
    }

    private String submit(String idemToken) throws Exception {
        return submitWithHeader(idemToken);
    }

    private String submitWithHeader(String idemToken) throws Exception {
        MockHttpServletRequestBuilder request = post("/user/order/submit")
                .header("authentication", userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody());
        if (idemToken != null) {
            request = request.header(TOKEN_HEADER, idemToken);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String submitBody() {
        return "{\"addressBookId\":" + addressBookId + ",\"payMethod\":1,\"remark\":\"幂等测试\","
                + "\"amount\":9.90,\"deliveryStatus\":1,\"tablewareNumber\":1,"
                + "\"tablewareStatus\":1,\"packAmount\":0}";
    }
}
